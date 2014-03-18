import actors.Msg
import service.RedisServiceImpl
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.test._


/*
need to ensure this is run against test redis instance, eventually. Until then manual flushall

 */


object StringSpecification extends PlaySpecification {

  /*

  features from case study

  1. set up a user with a unique id and related data
     (GET username:username:uid => if isDefined, error
     INCR global:nextUserId => unique, no sequential guarantee (don't test for order, only uniqueness)
     SET uid:1000:username antirez
     SET uid:1000:password p1pp0
     SET username:antirez:uid 1000

  test: register N users with name and password equal to prefix + iteration #, check that users can be retrieved with same info

  functions
    register_user(username, user_id) => incr uid, use below setter functions
    get/set user_id(username)
    get/set username(user id)
    get/set password



  2. follower/following, posts (of interest to user) (this section only describes data structure, not actions)
    uid:1000:followers => Set of uids of all the followers users
    uid:1000:following => Set of uids of all the following users

    test:
      follow, check following list for follower, followee

    functions:
      follow_user(follower, following)
      get following
      get followers

  make a post
      uid:1000:posts => a List of post ids - every new post is LPUSHed here.
      INCR global:nextPostId => 10343
      SET post:10343 "$owner_id|$time|I'm having fun with Retwis

  distribute a post to recipients
      foreach($followers as $fid) {
        $r->push("uid:$fid:posts",$postid,false);

  fetch posts
    LRANGE uid:1000:posts

  test: have N users follow user a and not user b. users a and b post M messages. check that all messages availible from uid:1000:posts

  function:
    make post (user, post body)
    fetch user posts(user) //single range for now, add pagination later



  3.authentication (test getter/setter, I suppose (also, setter will encapsulate generating a new auth string)
    SET uid:1000:auth fea5e81ac8ca77622bed1c2132a021f9
    SET auth:fea5e81ac8ca77622bed1c2132a021f9 1000

  test: generate and fetch some auth tokens

  functions
    gen_auth_token(user id)
    get user(token)
    get token(user id)



   */

  val redis_service = RedisServiceImpl

  "user ops" should {
    "register" in {
      val res = for {
        _ <- redis_service.flushall
        uid <- redis_service.register_user("user1", "pwd")
      } yield uid

      val uid = await(res)
      uid.forall( c => ('0' to '9').contains(c) ) should beTrue
    }


    "follow" in {
      val res = for {
        _ <- redis_service.flushall
        follower_uid <- redis_service.register_user("user1", "pwd")
        following_uid <- redis_service.register_user("user2", "pwd")
        _ <- redis_service.follow_user(follower_uid, following_uid)
        following <- redis_service.get_following(follower_uid)
        followers <- redis_service.get_followers(following_uid)
      } yield (following.contains(follower_uid) && followers.contains(following_uid))

      await(res) should beTrue
    }

    "distribute posts" in {

      //timestamp: Long, uid: String, body: String

      val res = for {
        _ <- redis_service.flushall
        follower_uid <- redis_service.register_user("user1", "pwd")
        following_uid <- redis_service.register_user("user2", "pwd")
        _ <- redis_service.follow_user(follower_uid, following_uid)
        post_id <- redis_service.post(following_uid, Msg(System.currentTimeMillis, following_uid, "test post"))
        following <- redis_service.get_following(follower_uid)
        followers <- redis_service.get_followers(following_uid)
        follower_posts <- redis_service.get_user_posts(follower_uid)
        following_posts <- redis_service.get_user_posts(following_uid)
      } yield {
        println(s"following($following_uid) posts $following_posts")
        println(s"follower($follower_uid) posts $follower_posts")
        follower_posts.contains(post_id) && following_posts.contains(post_id)
      }

      await(res) should beTrue
    }

  }
}


















