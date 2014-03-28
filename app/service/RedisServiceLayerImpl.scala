package service


import java.lang.Long.parseLong

import scala.concurrent.Future
import scalaz.syntax.applicative.ToApplyOps

import play.api.libs.concurrent.Execution.Implicits._
import  scalaz._
import  Scalaz._

import org.mindrot.jbcrypt.BCrypt

import utils.{Logging, ApplicativeFuture, Utils}
import ApplicativeFuture._
import Utils._
import entities._


trait RedisServiceLayerImpl extends RedisServiceLayer with Logging{

  override val redisService = new RedisServiceImpl()

  type RedisServiceLike = RedisServiceImpl

  class RedisServiceImpl extends RedisService with RedisConfig {

    def load_post(post_id: PostId): Future[Option[Msg]] = {
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
        recipients <- followed_by(from)
        distribution = for (recipient <- recipients + from) yield {

          for {
            _ <- redis.lPush(RedisSchema.user_posts(recipient), post_id.pid)
            channel = s"${recipient.uid}:feed"
          } yield {
            log.info(s"distributing post, push ${post_id.pid} to $channel")
            println(s"client.publish(${channel}, ${post_id.pid})")
            client.publish(channel, post_id.pid)
          }

        }
        _ <- Future.sequence(distribution)
      } yield ()

    def post_message(author: UserId, body: String): Future[PostId] = {

      val timestamp = System.currentTimeMillis

      def trim_global = redis.lTrim(RedisSchema.global_timeline,0,1000)

      def handle_post(post_id: PostId) = {
        log.info(s"handling post $post_id for $author with body $body")
        (redis.lPush(RedisSchema.global_timeline, post_id.pid)
          |@| save_post(post_id, Msg(post_id, timestamp, author, body))
          |@| distribute_post(author, post_id)
          ){ (a,b,c) => () }
      }

      for {
        post_id <- redis.incr(RedisSchema.next_post_id).map(id => PostId(id.toString))
        _ <- handle_post(post_id)
        _ <- trim_global
      } yield post_id
    }



    def is_following(uid: UserId): Future[Set[UserId]] =
      for {
        following <- redis.sMembers(RedisSchema.is_following(uid))
      } yield following.map( id => UserId(id) )

    def followed_by(uid: UserId): Future[Set[UserId]] =
      for {
        followers <- redis.sMembers(RedisSchema.followed_by(uid))
      } yield followers.map( id => UserId(id) )



    //todo: don't name vals uid, the type already states that they are one!
    //idempotent, this function is a no-op if uid is already followed by to_follow
    def follow_user(uid: UserId, to_follow: UserId): Future[Unit] = {
      println(s"follow_user($uid: UserId, $to_follow: UserId)")
      for {
        _ <- predicate(uid =/= to_follow, s"user $uid just tried to follow himself! probably a client-side bug")
        _ <- redis.sAdd(RedisSchema.is_following(uid), to_follow.uid)
        _ <- redis.sAdd(RedisSchema.followed_by(to_follow), uid.uid)
      } yield ()
    }

    //idempotent, this function is a no-op if uid is not already followed by to_unfollow
    def unfollow_user(uid: UserId, to_unfollow: UserId): Future[Unit] = {
      println(s"follow_user($uid: UserId, $to_unfollow: UserId)")
      for {
        _ <- predicate(uid =/= to_unfollow, s"user $uid just tried to unfollow himself! probably a client-side bug")
        _ <- (redis.sRem(RedisSchema.is_following(uid), to_unfollow.uid) |@|
          redis.sRem(RedisSchema.followed_by(to_unfollow), uid.uid)){ (_,_) => () }
      } yield ()
    }


    //returns user id if successful. note: distinction between wrong password and nonexistent username? nah, maybe later
    def login_user(username: String, password: String): Future[UserId] = {
      for {
        raw_uid_opt <- redis.get(RedisSchema.username_to_id(username))
        uid <- match_or_else(raw_uid_opt, s"no uid for username $username"){case Some(u) => UserId(u)}
        passwordHashOpt <- redis.get(RedisSchema.user_password(uid))
        passwordHash <- match_or_else(passwordHashOpt, s"no hashed password for user $username with uid $uid"){case Some(pw) => pw}
        _ <- predicate(BCrypt.checkpw(password, passwordHash), "password doesn't match hashed password")
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
        // todo: check username first, only then reserve new user id
        _ <- set_username(uid, username)
        _ <- redis.set(RedisSchema.user_password(uid), hashedPassword)
      } yield uid
    }

    def gen_auth_token(uid: UserId): Future[AuthToken] = {
      val auth = AuthToken( new scala.util.Random().nextString(15) )
      for {
        _ <- (redis.set(RedisSchema.user_auth(uid), auth.token) |@|
          redis.set(RedisSchema.auth_user(auth), uid.uid)){ (_, _) => () }
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

    def get_user_name(uid: UserId): Future[String] =
      for {
        username_opt <- redis.get[String](RedisSchema.id_to_username(uid))
        username <- match_or_else(username_opt, s"no username for uid $uid"){case Some(s) => s}
      } yield username


    private def set_username(uid: UserId, username: String) = {
      for {
        uid_for_username <- redis.get(RedisSchema.username_to_id(username))
        _ <- match_or_else(uid_for_username , s"user $uid attempting to reserve taken username $username, already in use by user with uid $uid_for_username"){
          case None =>
        }
        // todo: in cases where actions are taken simultaneously, have a separate function (eg: set A => B, B => A)
        _ <- (redis.set(RedisSchema.id_to_username(uid), username) |@|
          redis.set(RedisSchema.username_to_id(username), uid.uid)){ (_,_) => ()}
      } yield ()

    }


  }


}
















