package service

/**
 * Created by paul on 1/26/14.
 */
object RedisSchema {

  //users following uid
   def followers_of(uid: UserId) = "uid:$uid:followers"
  //users followed by uid
   def followed_by(uid: UserId) = "uid:$uid:following"
  //set of posts favorited by user identified by user_id
   def user_favorites(uid: UserId) = s"uid:$uid:favorites"

  //pseudonym of user identified by user_id, and vice versa
   def id_to_username(uid: UserId) = s"uid:$uid:username"
   def username_to_id(username: String) = s"username:$username:uid"


  //plaintext password! Fix later.
   def user_password(uid: UserId) = s"uid:$uid:password"


  // <OLD> posts made by a user
  //now needs to be all posts of interest to user for display (posts by following)
   def user_posts(uid: UserId): String = s"uid:$uid:posts"

  //hashmap of user info. question: fold in about me, avatar url, username? why not?
   def user_info_map(uid: UserId) = s"user:$uid:info"

  //auth string for user uid. ignoring uniqueness requirement for now
   def user_auth(uid: UserId): String = s"uid:$uid:auth"
 
  //user id for auth string
   def auth_user(auth: AuthToken): String = s"auth:$auth:uid"

  //global set of pseudonyms currently in use.
   val global_usernames = "global:aliases"

  //list to which all posts are left-pushed
   val global_timeline = "global:timeline"

  //global unique post id, increment to atomically get a post id
   def next_post_id = "global:nextPostId"

  //global unique post id, increment to get a user id
   def next_user_id = "global:nextUserId"

  //info about post identified by post_id
   def post_info(post: PostId) = s"post:${post.pid}:info"



   def user_about_me(uid: UserId) = s"uid:$uid:about_me"

}
