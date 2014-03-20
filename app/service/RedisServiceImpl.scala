package service


import java.lang.Long.parseLong


import scredis.Redis
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import actors._
import scala.concurrent.Future
import scalaz.syntax.applicative.ToApplyOps
import scredis.parsing.Parser
import play.api.libs.json._

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import play.api.libs.concurrent.Execution.Implicits._
import scredis.exceptions.RedisParsingException
import scala.util.{Success, Failure}
import  scalaz._, std.option._, std.tuple._, syntax.bitraverse._
import  Scalaz._


import actors.ApplicativeStuff._


case class Stop(reason: String) extends Exception(s"stop execution:: $reason")



object RedisServiceImpl extends RedisService with RedisConfig {

  private def zipMsgInfo(post_ids: List[PostId]): Future[List[MsgInfo]] =
    for {
      posts <-  Future.sequence(post_ids.map{post_id =>
        load_post(post_id).map(r =>
            MsgInfo(post_id.pid, r)
          )
      })
    } yield posts



  def recent_posts(user_id: UserId): Future[List[MsgInfo]] =
    for {
      timeline <- redis.lRange[String](RedisSchema.global_timeline, 0, 50)
      msg_info <- zipMsgInfo(timeline.map(PostId(_)))
    } yield msg_info


  def load_post(post_id: PostId): Future[Msg] = {
    log.info(s"load post $post_id")
    for {
      map <- redis.hmGetAsMap[String](RedisSchema.post_info(post_id))("timestamp", "author", "body")
    } yield {
        val opt = for{
          timestamp <- map.get("timestamp")
          author <- map.get("author")
          body <- map.get("body")
        } yield Msg(parseLong(timestamp), UserId(author), body)
        opt.get
    }
  }

  def save_post(post_id: PostId, msg: Msg): Future[Unit] =
    redis.hmSetFromMap(RedisSchema.post_info(post_id), Map(
				"timestamp" -> msg.timestamp,
				"author" -> msg.uid.uid,
				"body" -> msg.body
    ))


  def distribute_post(from: UserId, post_id: PostId): Future[Unit] =
    for {
      recipients <- followers_of(from)
      distribution = for (recipient <- recipients + from) yield {

        for {
          _ <- redis.lPush(RedisSchema.user_posts(recipient), post_id.pid)
        } yield {client.publish(s"${recipient.uid}:feed", post_id.pid); ()}

      }
      _ <- Future.sequence(distribution)
    } yield ()

  def post_message(user_id: UserId, msg: Msg): Future[PostId] = {

    def trim_global = redis.lTrim(RedisSchema.global_timeline,0,1000)

    def handle_post(post_id: PostId) =
      (redis.lPush(RedisSchema.global_timeline, post_id.pid)
        |@| save_post(post_id, msg)
        |@| distribute_post(user_id, post_id)
      ){ (a,b,c) => log.info("done handling post!"); () }


    for {
      post_id <- redis.incr(RedisSchema.next_post_id).map(id => PostId(id.toString))
      _ <- handle_post(post_id)
      _ <- trim_global
    } yield post_id
  }



  def predicate(condition: Boolean)(fail: Exception): Future[Unit] =
    if (condition) Future( () ) else Future.failed(fail)


  def followed_by(uid: UserId): Future[Set[UserId]] =
    for {
      following <- redis.sMembers(RedisSchema.followed_by(uid))
    } yield following.map( id => UserId(id) )

  def followers_of(uid: UserId): Future[Set[UserId]] =
    for {
      followers <- redis.sMembers(RedisSchema.followers_of(uid))
    } yield followers.map( id => UserId(id) )



  //ignore if already followed
  def follow_user(uid: UserId, to_follow: UserId): Future[Unit] = {
    for {
      _ <- redis.sAdd(RedisSchema.followed_by(uid), to_follow)
      _ <- redis.sAdd(RedisSchema.followers_of(to_follow), uid)
    } yield ()
  }

  //ignore if not followed already
  def unfollow_user(uid: UserId, to_unfollow: UserId): Future[Unit] = {
    for {
       _ <- redis.sRem(RedisSchema.followed_by(uid), to_unfollow)
       _ <- redis.sRem(RedisSchema.followers_of(to_unfollow), uid)
    } yield ()
  }


  //returns user id if successful. note: distinction between wrong password and nonexistent username? nah, maybe later
  def login_user(username: String, password: String): Future[UserId] =
    for {
      Some(raw_uid) <- redis.get(RedisSchema.username_to_id(username))
      uid = UserId(raw_uid)
      _ <- Future( log.info(s"login: $username yields $uid") )
      Some(actual_password) <- redis.get(RedisSchema.user_password(uid))
      _ <- Future( log.info(s"login: $uid yields password $actual_password with entered password $password") )
      _ <- if (actual_password === password) Future(()) else Future.failed(Stop(s"passwords '$actual_password' and '$password' not equal"))
    } yield uid

  //future of userid for new user or error if invalid somehow
  def register_user(username: String, password: String): Future[UserId] = {
    for {
      raw_uid <- redis.incr(RedisSchema.next_user_id).map(_.toString)
      uid = UserId(raw_uid)
      //will lead to orphan uuids if validation fails.
      // todo: check username first, then recheck and reserve
      username_not_taken <- set_username(uid, username)
      _ <- predicate(username_not_taken)(Stop(s"username $username is taken"))
      _ <- redis.set(RedisSchema.user_password(uid), password)
    } yield uid
  }

  //todo: generate an actual random string, unset previous string
  def gen_auth_token(uid: UserId): Future[AuthToken] = {
    val auth = AuthToken( new scala.util.Random().nextString(15) )
    for {
      _ <- redis.set(RedisSchema.user_auth(uid), auth.token)
      _ <- redis.set(RedisSchema.auth_user(auth), uid.uid)
    } yield auth
  }


  //todo: generate an actual random string, unset previous string
  def user_from_auth_token(auth: AuthToken): Future[UserId] = {
    for {
      raw_uid_opt <- redis.get(RedisSchema.auth_user(auth))
	_ <- Future{log.info(s"auth token $auth yields uid $raw_uid_opt")}
	Some(raw_uid) = raw_uid_opt
      uid = UserId(raw_uid)
      user_auth_opt <- redis.get(RedisSchema.user_auth(uid))
	_ <- Future{log.info(s"auth token $user_auth_opt fetched for uid $uid")}
	Some(user_auth) = user_auth_opt
      _ <- if (user_auth =/= auth.token) Future.failed(Stop(s"user auth $user_auth doesn't match attempted $auth")) else Future( () )
    } yield uid
  }

  //given a user, get all posts routed to their feed
  def get_user_feed(user_id: UserId): Future[Seq[PostId]] =
    redis.lRange(RedisSchema.user_posts(user_id), 0, 100).map(_.map(PostId(_)))


  //get from global feed
  def get_global_feed(): Future[Seq[PostId]] =
    redis.lRange(RedisSchema.global_timeline, 0, 100).map(_.map(PostId(_)))


  def get_user_name(uid: UserId): Future[String] =
    redis.get[String](RedisSchema.id_to_username(uid)).map(_.get)


  //monadic 'short circuit' style flow control is iffy. revisit later
  private def set_username(uid: UserId, alias: String) = {
    for {
      alias_unique <- redis.sAdd(RedisSchema.global_usernames, alias)
      add_result = (alias_unique === 1L)
      _ <- if (add_result)
          (redis.set(RedisSchema.id_to_username(uid), alias) |@|
           redis.set(RedisSchema.username_to_id(alias), uid)){ (_,_) => ()}
      else Future(false)
    } yield add_result

  }


}
