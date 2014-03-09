package service

/**
 * Created by paul on 1/26/14.
 */
trait RedisSchema {

  //users following uid
  protected def followers(uid: String) = "uid:$uid:followers"
  //users followed by uid
  protected def following(uid: String) = "uid:$uid:following"
  //set of posts favorited by user identified by user_id
  protected def user_favorites(uid: String) = s"uid:$uid:favorites"

  //pseudonym of user identified by user_id, and vice versa
  protected def id_to_username(uid: String) = s"uid:$uid:username"
  protected def username_to_id(username: String) = s"username:$username:uid"


  //plaintext password! Fix later.
  protected def user_password(uid: String) = s"uid:$uid:password"


  // <OLD> posts made by a user
  //now needs to be all posts of interest to user for display (posts by following)
  protected def user_posts(uid: String): String = s"uid:$uid:posts"

  //hashmap of user info. question: fold in about me, avatar url, username? why not?
  protected def user_info_map(uid: String) = s"user:$uid:info"

  protected object UserInfoKeys {
    val about_me = "about_me"
    val avatar_url = "avatar_url"
    val username = "username"


    def to_map(user: User): Map[String, String] =
      Map(username -> user.username)



    def from_map(uid: String, map: Map[String, String]): Option[User] =
        map.get(username).map{User(uid, _)}

  }


  //auth string for user uid. ignoring uniqueness requirement for now
  protected def user_auth(uid: String): String = s"uid:$uid:auth"
  //user id for auth string
  protected def auth_user(auth: String): String = s"auth:$auth:uid"





  //global set of pseudonyms currently in use.
  protected val global_usernames = "global:aliases"

  //list to which all posts are left-pushed
  protected val global_timeline = "global:timeline"

  //global unique post id, increment to atomically get a post id
  protected def next_post_id = "global:nextPostId"

  //global unique post id, increment to get a user id
  protected def next_user_id = "global:nextUserId"

  //info about post identified by post_id
  protected def post_info(post_id: String) = s"post:$post_id:info"



  protected def user_about_me(uid: String) = s"uid:$uid:about_me"

}
