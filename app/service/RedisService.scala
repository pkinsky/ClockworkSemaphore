package service

import actors.{MsgInfo, Msg, PublicIdentity}
import scala.concurrent.Future

trait RedisService {
    def login_user(username: String, password: String): Future[String]

    def register_user(username: String, password: String): Future[String]

    def save_user(user: User): Future[Unit]

    def delete_post(post_id: String): Future[Unit]

    def get_user(user_id: String): Future[User]

    def get_public_user(current_user: String, user_id: String): Future[PublicIdentity]

    def post(user_id: String, msg: Msg): Future[String]

    def load_post(post_id: String): Future[Option[Msg]]

    def recent_posts(user_id: String): Future[List[MsgInfo]]

    def add_favorite_post(user_id: String, post_id: String): Future[Unit]

    def remove_favorite_post(user_id: String, post_id: String): Future[Unit]

    def load_favorite_posts(user_id: String): Future[Set[String]]

    def establish_alias(user_id: String, alias: String): Future[Boolean]

    def set_about_me(user_id: String, text: String): Future[Unit]

    def get_about_me(user_id: String): Future[Option[String]]

    def load_msg_info(user_id: String, post_id: String): Future[Option[MsgInfo]]

    def follow_user(uid: String, to_follow:String): Future[Unit]

    def unfollow_user(uid: String, to_unfollow:String): Future[Unit]
}