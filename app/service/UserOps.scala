package service

import scala.concurrent.Future
import scalaz.Applicative
import actors.PublicIdentity
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._

import actors.ApplicativeStuff._
import scalaz.syntax.applicative.ToApplyOps

import scredis.parsing.Parser



//what a user sees about themselves, what the system knows about a given user
case class User(uid: String, username: String)

trait UserOps extends RedisSchema with RedisConfig{

  def load_favorite_posts(uid: String): Future[Set[String]] = {
    redis.sMembers[String](user_favorites(uid))
  }

  def remove_favorite_post(uid: String, post_id: String): Future[Unit] = {
    redis.sRem(user_favorites(uid), post_id).map( _ => () ) //return Unit? fail if not a favorite?
  }


  def add_favorite_post(uid: String, post_id: String): Future[Unit] = {
    redis.sAdd(user_favorites(uid), post_id).map( _ => () ) //return Unit? fail if already a favorite?
  }

  //returns user id if successful. note: distinction between wrong password and nonexistent username? nah, maybe later
  def login_user(username: String, password: String): Future[String] =
    for {
      Some(uid) <- redis.get(username_to_id(username))
      _ <- Future( log.info(s"login: $username yields $uid") )
      Some(actual_password) <- redis.get(user_password(uid))
      _ <- Future( log.info(s"login: $uid yields password $actual_password with entered password $password") )
      if (actual_password == password)
    } yield uid

  //future of userid for new user or error if invalid somehow
  def register_user(username: String, password: String): Future[String] = {
    for {
      uid <- redis.incr(next_user_id).map(_.toString)
      //will lead to orphan uuids if validation fails.
      // todo: check username first, then recheck and reserve
      username_not_taken <- establish_alias(uid, username)
      if username_not_taken
      _ <- redis.set(user_password(uid), password)
    } yield uid
  }

  //todo: generate an actual random string, unset previous string
  def gen_auth_string_for_user(uid: String): Future[String] = {
    val auth = "todo: random"
    for {
      _ <- redis.set(user_auth(uid), auth)
      _ <- redis.set(auth_user(auth), uid)
    } yield auth
  }


  //todo: generate an actual random string, unset previous string
  def user_from_auth_string(auth: String): Future[String] = {
    for {
      Some(uid) <- redis.get(auth_user(auth))
      Some(user_auth) <- redis.get(user_auth(uid))
      if user_auth == auth
    } yield uid
  }


  def save_user(user: User): Future[Unit] = {
    redis.hmSetFromMap(user_info_map(user.uid), UserInfoKeys.to_map(user))
  }

  private def get_alias(uid: String): Future[Option[String]] = {
    redis.get[String](id_to_username(uid))
  }

  //monadic 'short circuit' style flow control is iffy. revisit later
  def establish_alias(uid: String, alias: String) = {
    for {
      alias_unique <- redis.sAdd(global_usernames, alias)
      add_result = (alias_unique == 1L)
      _ <- if (add_result)
          (redis.set(id_to_username(uid), alias) |@|
           redis.set(username_to_id(alias), uid)){ (_,_) => ()}
      else Future(false)
    } yield add_result

  }

  def set_about_me(uid: String, text: String): Future[Unit] = {
    redis.set(user_about_me(uid), text)
  }

  def get_about_me(uid: String): Future[Option[String]] = redis.get[String](user_about_me(uid))

  //todo: store in a hashmap, transform hashmap into case class
  def get_user(uid: String): Future[User] =
    for {
      Some(username) <- redis.get(id_to_username(uid))
    } yield User(uid, username)


  def get_public_user(current_user: String, uid: String): Future[PublicIdentity] =
    (get_user(uid) |@|
    get_alias(uid) |@|
    get_user_posts(uid) |@|
    get_about_me(uid)){
    (user, alias, posts, about_me) =>
          PublicIdentity(user.uid, user.username, Some("default_url"), posts, about_me.getOrElse("click here to edit about me"))
    }

  protected def get_user_posts(uid: String): Future[List[String]] =
    redis.lRange[String](user_posts(uid), 0, 50)

}