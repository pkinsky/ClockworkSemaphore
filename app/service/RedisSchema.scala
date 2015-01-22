package service

import entities.{PostId, AuthToken, UserId}


/**
* Encapsulates generation of redis keys from domain objects
**/
object RedisSchema {

  // set of users followed by uid
   def followed_by(uid: UserId): String = s"uid:${uid.uid}:followers"

  // set of users following uid
   def is_following(uid: UserId): String = s"uid:${uid.uid}:following"

  //pseudonym of user identified by user_id, and vice versa
   def id_to_username(uid: UserId): String = s"uid:${uid.uid}:username"
   def username_to_id(username: String): String = s"username:$username:uid"

   //hashed password for uid
   def user_password(uid: UserId): String = s"uid:${uid.uid}:password"

   //a user's feed: all posts by them and users they follow
   def user_posts(uid: UserId): String = s"uid:${uid.uid}:posts"

  //auth token for uid
   def user_auth(uid: UserId): String = s"uid:${uid.uid}:auth"

  //uid for auth token
   def auth_user(auth: AuthToken): String = s"auth:${auth.token}:uid"

  //list to which all posts are left-pushed
   val global_timeline: String = "global:timeline"

  //global unique post id, increment to get a new post id
   def next_post_id: String = "global:nextPostId"

  //global unique user id, increment to get a new user id
   def next_user_id: String = "global:nextUserId"

  //map containing attributes of post with post_id
   def post_info(post: PostId): String = s"post:${post.pid}:info"
}

