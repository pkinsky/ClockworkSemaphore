package service

import securesocial.core.{Identity, IdentityId}
import actors.{Msg, PublicIdentity}
import scala.concurrent.Future

trait RedisService {

  def get_followers(user_id: IdentityId): Future[Set[String]]

  def save_user(user: Identity): Future[Unit]

  def get_user(user_id: IdentityId): Future[Identity]

  def get_public_user(user_id: IdentityId): Future[PublicIdentity]

  def post(user_id: IdentityId, msg: String): Future[String]

  def recent_posts: Future[Seq[Msg]]

  def establish_alias(user_id: IdentityId, alias: String): Future[Boolean]
}