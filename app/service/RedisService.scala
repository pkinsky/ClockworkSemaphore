package service


import java.lang.Long.parseLong

import scala.concurrent.Future
import scalaz.syntax.applicative.ToApplyOps

import play.api.libs.concurrent.Execution.Implicits._
import  scalaz._
import  Scalaz._

import org.mindrot.jbcrypt.BCrypt

import utils.{Logging, Utils}
import Utils._
import entities._

/**
 * Global object, handles actual interaction with Redis.
 * All methods are non-blocking and thread safe.
 */
object RedisService extends RedisConfig with Logging {

  //small page size to demonstrate pagination
  val page_size = 10


  /**
   * Load a post
   * @param post_id the post id to load
   * @return (Future of) Some message if the given post_id maps to an actual message else None
   */
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


  /**
   * Load a series of posts
   * @param post_ids sequence of post ids to load
   * @return (Future of) messages
   */
    def load_posts(post_ids: Seq[PostId]): Future[Seq[Msg]] = {
      for {
        msgs <- Future.sequence(post_ids.map(load_post))
      } yield msgs.collect {case Some(msg) => msg} //filter out posts that have been deleted
    }


  /**
   * Write a Message to Redis
   * @param post_id post id of message being saved
   * @param msg message to save
   * @return (Future of) Unit
   */
    private def save_post(post_id: PostId, msg: Msg): Future[Unit] =
      redis.hmSetFromMap(RedisSchema.post_info(post_id), Map(
        "timestamp" -> msg.timestamp,
        "author" -> msg.uid.uid,
        "body" -> msg.body
      ))


  /**
   * Add a post's id to the feed of its author and her followers
   * @param from author of the post
   * @param post_id id of the post
   * @return (Future of) Unit
   */
    private def distribute_post(from: UserId, post_id: PostId): Future[Unit] =
      for {
        recipients <- followed_by(from)
        distribution = for (recipient <- recipients + from) yield {

          for {
            _ <- redis.lPush(RedisSchema.user_posts(recipient), post_id.pid)
            _ <- redis.publish(s"${recipient.uid}:feed", post_id.pid)
          } yield ()

        }
        _ <- Future.sequence(distribution)
      } yield ()


  /**
   * Post a message by a given user with a given body. Reserves a global post id
   * and distributes that post id to the feeds of all followers of this user.
   * @param author author of the post
   * @param body body of the post
   * @return (Future of) the id of the post after it is created
   */
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


  /**
   * Get the set of users which are following this user.
   * @param uid user being followed
   * @return users following user
   */
    def is_following(uid: UserId): Future[Set[UserId]] =
      for {
        following <- redis.sMembers(RedisSchema.is_following(uid))
      } yield following.map( id => UserId(id) )

  /**
   * Get the set of users followed by this user
   * @param uid user doing the following
   * @return users followed by user
   */
    def followed_by(uid: UserId): Future[Set[UserId]] =
      for {
        followers <- redis.sMembers(RedisSchema.followed_by(uid))
      } yield followers.map( id => UserId(id) )


  /**
   * Start following a user
   * @param uid user doing the following
   * @param to_follow user being followed
   * @return (Future of) Unit
   */
    def follow_user(uid: UserId, to_follow: UserId): Future[Unit] = {
      log.info(s"$uid following $to_follow")
      for {
        _ <- predicate(uid =/= to_follow, s"user $uid just tried to follow himself! probably a client-side bug")
        _ <- redis.sAdd(RedisSchema.is_following(uid), to_follow.uid)
        _ <- redis.sAdd(RedisSchema.followed_by(to_follow), uid.uid)
      } yield ()
    }

  /**
   * Stop following a user
   * @param uid user doing the unfollowing
   * @param to_unfollow user being unfollowed
   * @return (Future of) Unit
   */
    def unfollow_user(uid: UserId, to_unfollow: UserId): Future[Unit] = {
      log.info(s"$uid unfollowing $to_unfollow)")
      for {
        _ <- predicate(uid =/= to_unfollow, s"user $uid just tried to unfollow himself! probably a client-side bug")
        _ <- (redis.sRem(RedisSchema.is_following(uid), to_unfollow.uid) |@|
          redis.sRem(RedisSchema.followed_by(to_unfollow), uid.uid)){ (_,_) => () }
      } yield ()
    }


  /**
   * Attempt to log a user in with the provided credentials
   * @param username username to login with
   * @param password password to login with
   * @return (Future of) the UID assigned existing user with the above credentials
   */
    def login_user(username: String, password: String): Future[UserId] = {
      for {
        raw_uid_opt <- redis.get(RedisSchema.username_to_id(username))
        uid <- match_or_else(raw_uid_opt, s"no uid for username $username"){case Some(u) => UserId(u)}
        passwordHashOpt <- redis.get(RedisSchema.user_password(uid))
        passwordHash <- match_or_else(passwordHashOpt, s"no hashed password for user $username with uid $uid"){case Some(pw) => pw}
        _ <- predicate(BCrypt.checkpw(password, passwordHash), "password doesn't match hashed password")
      } yield uid
    }


  /**
   * Attempt to register a user with the provided credentials
   * @param username username to register with
   * @param password password to register with
   * @return (Future of) a fresh UID assigned to a new user with the above credentials
   */
    def register_user(username: String, password: String): Future[UserId] = {
      val hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt())

      for {
        raw_uid <- redis.incr(RedisSchema.next_user_id).map(_.toString)
        uid = UserId(raw_uid)
        _ <- set_username(uid, username)
        _ <- redis.set(RedisSchema.user_password(uid), hashedPassword)
      } yield uid
    }

  /**
   * Generate and register a new auth token for a given user
   * @param uid user to generate an auth token for
   * @return (Future of) an auth token
   */
    def gen_auth_token(uid: UserId): Future[AuthToken] = {
      val auth = AuthToken( new scala.util.Random().nextString(15) )
      for {
        _ <- (redis.set(RedisSchema.user_auth(uid), auth.token) |@|
          redis.set(RedisSchema.auth_user(auth), uid.uid)){ (_, _) => () }
      } yield auth
    }


  /**
   * Get the user associated with some auth token
   * @param auth an auth token
   * @return associated user
   */
    def user_from_auth_token(auth: AuthToken): Future[UserId] = {
      for {
        raw_uid_opt <- redis.get(RedisSchema.auth_user(auth))
        uid <- match_or_else(raw_uid_opt, s"uid not found for auth token: $auth"){ case Some(u) => UserId(u) }
        user_auth_opt <- redis.get(RedisSchema.user_auth(uid))
        user_auth <- match_or_else(user_auth_opt, s"auth string not found for uid: $uid"){ case Some(a) => AuthToken(a) }
        _ <- predicate(user_auth === auth, s"user auth $user_auth doesn't match attempted $auth")
      } yield uid
    }


  /**
   * Given a user, fetch posts routed to their feed
   * (represented as a linked list, random access to a node requires traversing all precursors of that node.)
   * @param user_id a user
   * @param page pages to offset the fetched slice of the feed by
   * @return (Future of) posts in the requested page of the given user's feed
   */
    def get_user_feed(user_id: UserId, page: Int): Future[Seq[PostId]] = {
      val start = page_size * page
      val end = start + page_size
      redis.lRange(RedisSchema.user_posts(user_id), start, end).map(_.map(PostId(_)))
    }


  /**
   * Fetch posts routed to the global feed
   * (represented as a linked list, random access to a node requires traversing all precursors of that node.)
   * @param page pages to offset the fetched slice of the feed by
   * @return (Future of) posts in the requested page of the global feed
   */
    def get_global_feed(page: Int): Future[Seq[PostId]] = {
      val start = page_size * page
      val end = start + page_size
      redis.lRange(RedisSchema.global_timeline, start, end).map(_.map(PostId(_)))
    }


  /**
   * Fetch a user's username
   * @param uid some user
   * @return (Future of) this user's username
   */
    def get_user_name(uid: UserId): Future[String] =
      for {
        username_opt <- redis.get[String](RedisSchema.id_to_username(uid))
        username <- match_or_else(username_opt, s"no username for uid $uid"){case Some(s) => s}
      } yield username


  /**
   * Reserve a username for a user. Checks availability of that username
   * @param uid some user
   * @param username username to register for the given user
   * @return (Future of) Unit
   */
    private def set_username(uid: UserId, username: String): Future[Unit] =
      for {
        uid_for_username <- redis.get(RedisSchema.username_to_id(username))
        _ <- match_or_else(uid_for_username , s"user $uid attempting to reserve taken username $username, already in use by user with uid $uid_for_username"){
          case None =>
        }
        _ <- (redis.set(RedisSchema.id_to_username(uid), username) |@|
              redis.set(RedisSchema.username_to_id(username), uid.uid)){
                (_,_) => ()
              }
      } yield ()


}

