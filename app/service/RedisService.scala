package service

import actors.Msg
import scala.concurrent.Future



case class PostId(pid: String) extends AnyVal

case class UserId(uid: String) extends AnyVal

case class AuthToken(token: String) extends AnyVal

trait RedisService {

    def gen_auth_token(uid: UserId): Future[AuthToken]

    def user_from_auth_token(token: AuthToken): Future[UserId]

    //either register the user with the given username password,
    //or throw an exception signaling invalid credentials
    def login_user(username: String, password: String): Future[UserId]

    // given a username and password, either register a user with that u:p
    // or throw an exception signaling that the username is taken
    def register_user(username: String, password: String): Future[UserId]

    // given a user id, fetch the corresponding user
    def get_user_name(user_id: UserId): Future[String]

    //given a user, get all posts routed to their feed
    def get_user_feed(user_id: UserId, page: Int): Future[Seq[PostId]]

    //get from global feed
    def get_global_feed(page: Int): Future[Seq[PostId]]

    // given a user id and a message, save that message and distribute it to all followers of sender
    def post_message(sender: UserId, body: String): Future[PostId]

    // given a post id, load that post
    // or nosuchelement exception
    def load_post(post_id: PostId): Future[Option[Msg]]

    def load_posts(post_ids: Seq[PostId]): Future[Seq[Msg]]

  def follow_user(uid: UserId, to_follow:UserId): Future[Unit]

    def unfollow_user(uid: UserId, to_unfollow:UserId): Future[Unit]

    def followers_of(uid: UserId): Future[Set[UserId]]

    def followed_by(uid: UserId): Future[Set[UserId]]
}
