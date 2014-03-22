package service

import entities.{PostId, AuthToken, UserId}

/**
 * Created by paul on 1/26/14.
 */
object RedisSchema {

  //users following uid
   def followers_of(uid: UserId) = "uid:$uid:followers"
  //users followed by uid
   def followed_by(uid: UserId) = "uid:$uid:following"

  //pseudonym of user identified by user_id, and vice versa
   def id_to_username(uid: UserId) = s"uid:$uid:username"
   def username_to_id(username: String) = s"username:$username:uid"

   //hashed password
   def user_password(uid: UserId) = s"uid:$uid:password"

   //a user's feed: all posts by them and those they are following
   def user_posts(uid: UserId): String = s"uid:$uid:posts"

  //auth token for user id
   def user_auth(uid: UserId): String = s"uid:$uid:auth"
 
  //user id for auth token
   def auth_user(auth: AuthToken): String = s"auth:$auth:uid"

  //list to which all posts are left-pushed
   val global_timeline = "global:timeline"

  //global unique post id, increment to atomically get a post id
   def next_post_id = "global:nextPostId"

  //global unique post id, increment to get a user id
   def next_user_id = "global:nextUserId"

  //info about post identified by post_id
   def post_info(post: PostId) = s"post:${post.pid}:info"

}
