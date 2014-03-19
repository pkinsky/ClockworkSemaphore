package service

import actors.{MsgInfo, Msg}
import scala.concurrent.Future



case class PostId(pid: String) extends AnyVal

case class UserId(uid: String) extends AnyVal

case class AuthToken(token: String) extends AnyVal

trait RedisService {
  /*
  register_user(username, user_id) => incr uid, use below setter functions
  get/set user_id(username)
  get/set username(user id)
  get/set password
    follow_user(follower, following)
    get following
    get followers
  make post (user, post body)
  fetch user posts(user) //single range for now, add pagination later
  gen_auth_token(user id)
  get user(token)
  get token(user id)
 */
    def gen_auth_token(uid: UserId): Future[AuthToken]

    def user_from_auth_token(token: AuthToken): Future[UserId]

    //either register the user with the given username password,
    //or throw an exception signaling invalid credentials
    def login_user(username: String, password: String): Future[UserId]

    // given a username and password, either register a user with that u:p
    // or throw an exception signaling that the username is taken
    def register_user(username: String, password: String): Future[UserId]

    // given a user id, fetch the corresponding user
    def get_user(user_id: UserId): Future[User]

    // given a user id and a message, save that message and distribute it to all followers of sender
    def post_message(sender: UserId, msg: Msg): Future[PostId]

    // given a post id, load that post
    // or nosuchelement exception
    def load_post(post_id: PostId): Future[Msg]

    //load the last N posts by a user
    def recent_posts(uid: UserId): Future[List[MsgInfo]]


    //def add_favorite_post(uid: UserId, post_id: String): Future[Unit]

    //def remove_favorite_post(uid: UserId, post_id: String): Future[Unit]

    //def load_favorite_posts(uid: UserId): Future[Set[String]]

    //def establish_alias(uid: UserId, alias: String): Future[Boolean]

    //def set_about_me(uid: UserId, text: String): Future[Unit]

    //def get_about_me(uid: UserId): Future[Option[String]]

    //def load_msg_info(uid: UserId, post_id: String): Future[Option[MsgInfo]]

    def follow_user(uid: UserId, to_follow:UserId): Future[Unit]

    def unfollow_user(uid: UserId, to_unfollow:UserId): Future[Unit]

    def followers_of(uid: UserId): Future[Set[UserId]]

    def followed_by(uid: UserId): Future[Set[UserId]]
}
