package service

import securesocial.core._
import scala.concurrent.Future
import scalaz.Applicative
import actors.PublicIdentity
import IdentityIdConverters._
import IdentityToJson._
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._

import actors.ApplicativeStuff._
import scalaz.syntax.applicative.ToApplyOps



trait UserOps extends RedisSchema with RedisConfig{

  def load_favorite_posts(user_id: IdentityId): Future[Set[String]] = {
    redis.sMembers[String](user_favorites(user_id))
  }

  def remove_favorite_post(user_id: IdentityId, post_id: String): Future[Unit] = {
    redis.sRem(user_favorites(user_id), post_id).map( _ => () ) //return Unit? different if not a favorite?
  }


  def add_favorite_post(user_id: IdentityId, post_id: String): Future[Unit] = {
    redis.sAdd(user_favorites(user_id), post_id).map( _ => () ) //return Unit? different if already a favorite?
  }

  def save_user(user: Identity): Future[Unit] = {
    val user_json = Json.toJson[Identity](user).toString
    redis.set(user_identity(user.identityId), user_json)
  }

  private def get_alias(user_id: IdentityId): Future[Option[String]] = {
    redis.get[String](user_alias(user_id))
  }


  def establish_alias(user_id: IdentityId, alias: String) = {
    redis.sAdd(global_aliases, alias)
      .map(_==1L).flatMap{
      case true => redis.set(user_alias(user_id), alias).map(_ => true)
      case false => Future(false)
    }
  }

  def set_about_me(user_id: IdentityId, text: String): Future[Unit] = {
    redis.set(user_about_me(user_id), text)
  }

  def get_about_me(user_id: IdentityId): Future[Option[String]] = redis.get[String](user_about_me(user_id))

  def get_user(user_id: IdentityId): Future[Identity] =
    redis.get[JsValue](user_identity(user_id))(parser=ParseJs).map{ json =>
      for {
        js <- json
        res <- Json.fromJson[Identity](js).asOpt
      } yield res
    }.flatMap{
      case Some(u) => Applicative[Future].point(u)
      case None => Future.failed(new Exception(s"user id $user_id not found"))
    }


  def get_public_user(current_user: IdentityId, user_id: IdentityId): Future[PublicIdentity] = 
    (get_user(user_id) |@|
    get_alias(user_id) |@|
    get_user_posts(user_id) |@|
    get_about_me(user_id)){ 
    (id, alias, posts, about_me) =>
          PublicIdentity(id.identityId.asString, alias.getOrElse(""), id.avatarUrl, posts, about_me.getOrElse("click here to edit about me"))
    }

  protected def get_user_posts(user_id: IdentityId): Future[List[String]] =
    redis.lRange[String](user_posts(user_id), 0, 50)

}
