package service

import IdentityIdConverters._

/**
 * Created by paul on 1/26/14.
 */
trait RedisSchema {

  //list to which all posts are left-pushed
  protected val global_timeline = "global:timeline"

  //info about post identified by post_id
  protected def post_info(post_id: String) = s"post:$post_id:info"

  //set of posts favorited by user identified by user_id
  protected def user_favorites(user_id: IdentityId) = s"user:${user_id.asString}:favorites"

  //pseudonym of user identified by user_id
  protected def user_alias(user_id: IdentityId) = s"user:${user_id.asString}:alias"

  //global set of pseudonyms in use.
  protected val global_aliases = "global:aliases"

  //json-encoded user identity object
  protected def user_identity(user_id: IdentityId) = s"user:${user_id.asString}:identity"

  //global unique post id, increment to atomically get a post id
  protected def next_post_id = "global:nextPostId"

  //posts made by a user
  protected def user_posts(user_id: IdentityId): String = s"user:${user_id.asString}:posts"


  protected def user_about_me(user_id: IdentityId) = s"user:${user_id.asString}:about_me"

}
