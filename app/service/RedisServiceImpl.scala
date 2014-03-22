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


import org.mindrot.jbcrypt.BCrypt


import actors.ApplicativeStuff._

import Utils._

object RedisServiceImpl extends RedisService with RedisConfig {

  //todo: expose Option[Msg] sanely
  def load_post(post_id: PostId): Future[Option[Msg]] = {
    log.info(s"load post $post_id")
    for {
      map <- redis.hmGetAsMap[String](RedisSchema.post_info(post_id))("timestamp", "author", "body")
    } yield {
        for{
          timestamp <- map.get("timestamp")
          author <- map.get("author")
          body <- map.get("body")
        } yield Msg(post_id, parseLong(timestamp), UserId(author), body)
    }
  }

  //load a sequence of posts, returning all that exist and omiting those which don't
  def load_posts(post_ids: Seq[PostId]): Future[Seq[Msg]] = {
    for {
      msgs <- Future.sequence(post_ids.map(load_post))
    } yield msgs.collect {case Some(msg) => msg}
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
          channel = s"${recipient.uid}:feed"
        } yield {
          log.info(s"distributing post, push ${post_id.pid} to $channel")
          client.publish(channel, post_id.pid)
          ()
        }

      }
      _ <- Future.sequence(distribution)
    } yield ()

  def post_message(user_id: UserId, body: String): Future[PostId] = {

    val timestamp = System.currentTimeMillis

    def trim_global = redis.lTrim(RedisSchema.global_timeline,0,1000)

    def handle_post(post_id: PostId) = {
       log.info(s"handling post $post_id for $user_id with body $body")
        (redis.lPush(RedisSchema.global_timeline, post_id.pid)
          |@| save_post(post_id, Msg(post_id, timestamp, user_id, body))
          |@| distribute_post(user_id, post_id)
        ){ (a,b,c) => log.info("done handling post!"); () }
      }


    for {
      post_id <- redis.incr(RedisSchema.next_post_id).map(id => PostId(id.toString))
      _ <- handle_post(post_id)
      _ <- trim_global
    } yield post_id
  }



  def followed_by(uid: UserId): Future[Set[UserId]] =
    for {
      following <- redis.sMembers(RedisSchema.followed_by(uid))
    } yield following.map( id => UserId(id) )

  def followers_of(uid: UserId): Future[Set[UserId]] =
    for {
      followers <- redis.sMembers(RedisSchema.followers_of(uid))
    } yield followers.map( id => UserId(id) )



  //idempotent, this function is a no-op if uid is already followed by to_follow
  def follow_user(uid: UserId, to_follow: UserId): Future[Unit] = {
    for {
      _ <- redis.sAdd(RedisSchema.followed_by(uid), to_follow.uid)
      _ <- redis.sAdd(RedisSchema.followers_of(to_follow), uid.uid)
    } yield ()
  }

  //idempotent, this function is a no-op if uid is not already followed by to_unfollow
  def unfollow_user(uid: UserId, to_unfollow: UserId): Future[Unit] = {
    for {
       _ <- redis.sRem(RedisSchema.followed_by(uid), to_unfollow.uid)
       _ <- redis.sRem(RedisSchema.followers_of(to_unfollow), uid.uid)
    } yield ()
  }


  //returns user id if successful. note: distinction between wrong password and nonexistent username? nah, maybe later
  def login_user(username: String, password: String): Future[UserId] = {
    for {
      Some(raw_uid) <- redis.get(RedisSchema.username_to_id(username))
      uid = UserId(raw_uid)
      Some(passwordHash) <- redis.get(RedisSchema.user_password(uid)) //todo: predicate, but for pattern matching
      _ <- predicate(BCrypt.checkpw(password, passwordHash), "password hashes don't match")
    } yield uid
  }


  /*
  todo: clean up
   */
  def register_user(username: String, password: String): Future[UserId] = {

    val hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt())

    for {
      raw_uid <- redis.incr(RedisSchema.next_user_id).map(_.toString)
      uid = UserId(raw_uid)
      //will lead to orphan uuids if validation fails.
      // todo: check username first, then recheck and reserve
      username_not_taken <- set_username(uid, username)
      _ <- predicate(username_not_taken, s"username $username is taken")
      _ <- redis.set(RedisSchema.user_password(uid), hashedPassword)
    } yield uid
  }

  def gen_auth_token(uid: UserId): Future[AuthToken] = {
    val auth = AuthToken( new scala.util.Random().nextString(15) )
    for {
      _ <- redis.set(RedisSchema.user_auth(uid), auth.token)
      _ <- redis.set(RedisSchema.auth_user(auth), uid.uid)
    } yield auth
  }


  def user_from_auth_token(auth: AuthToken): Future[UserId] = {
    for {
      raw_uid_opt <- redis.get(RedisSchema.auth_user(auth))
      uid <- match_or_else(raw_uid_opt, s"uid not found for auth token: $auth"){ case Some(u) => UserId(u) }
      user_auth_opt <- redis.get(RedisSchema.user_auth(uid))
      user_auth <- match_or_else(user_auth_opt, s"auth string not found for uid: $uid"){ case Some(a) => AuthToken(a) }
      _ <- predicate(user_auth === auth, s"user auth $user_auth doesn't match attempted $auth")
    } yield uid
  }

  val page_size = 5 //small page size to demonstrate pagination

  //given a user, get all posts routed to their feed
  def get_user_feed(user_id: UserId, page: Int): Future[Seq[PostId]] = {
    val start = page_size * page
    val end = start + page_size
    redis.lRange(RedisSchema.user_posts(user_id), start, end).map(_.map(PostId(_)))
  }


  //get from global feed
  def get_global_feed(page: Int): Future[Seq[PostId]] = {
    val start = page_size * page
    val end = start + page_size
    redis.lRange(RedisSchema.global_timeline, start, end).map(_.map(PostId(_)))
  }

  //todo: handle Option[A] sanely, need to bite the bullet and return Future[Option[String]]
  def get_user_name(uid: UserId): Future[String] =
    redis.get[String](RedisSchema.id_to_username(uid)).map(_.get)


  //todo: replace if with predicate, or something. Maybe just fall back to exact case study method, no set
  private def set_username(uid: UserId, alias: String) = {
    for {
      alias_unique <- redis.sAdd(RedisSchema.global_usernames, alias)
      add_result = (alias_unique === 1L)
      _ <- if (add_result)
          (redis.set(RedisSchema.id_to_username(uid), alias) |@|
           redis.set(RedisSchema.username_to_id(alias), uid.uid)){ (_,_) => ()}
      else Future(false)
    } yield add_result

  }


}















