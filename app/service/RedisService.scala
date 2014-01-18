package service

import securesocial.core.{Identity, IdentityId}
import actors.{Msg, PublicIdentity}
import scalaz.concurrent.Task

trait RedisService {

  def get_followers_as_task(user_id: IdentityId): Task[Set[String]]

  def save_user_as_task(user: Identity): Task[Unit]

  def get_user_as_task(user_id: IdentityId): Task[Identity]

  def get_public_user_as_task(user_id: IdentityId): Task[PublicIdentity]

  def post_as_task(user_id: IdentityId, msg: String): Task[String]

  def recent_posts_as_task: Task[Seq[Msg]]

  def establish_alias_as_task(user_id: IdentityId, alias: String): Task[Boolean]
}