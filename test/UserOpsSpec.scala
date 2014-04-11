import entities.Msg
import service.RedisService
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.test._


/*
need to ensure this is only ever run against test redis instances, somehow. Until then manual flushall.
todo: before/after cleanup tasks

Look into:
    weird test failures, from redis response parsing errors deep in scredis.
    These errors have never happened when running the app,
    either on heroku or locally. Investigate further.
    --Hypothesis: generating pubsub messages that get parsed weirdly when no clients are active
 */


object StringSpecification extends PlaySpecification  {

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


  "user ops" should {
    "register" in {
      val res = for {
        _ <- RedisService.flushall
        uid <- RedisService.register_user("user1", "pwd")
      } yield uid

      val uid = await(res)
      uid.uid.forall( c => ('0' to '9').contains(c) ) should beTrue
    }


    "follow" in {
      val res = for {
        _ <- RedisService.flushall
        follower_user <- RedisService.register_user("user1", "pwd")
        followed_user <- RedisService.register_user("user2", "pwd")
        _ <- RedisService.follow_user(follower_user, followed_user)
        is_following <- RedisService.is_following(follower_user)
        followed_by <- RedisService.followed_by(followed_user)
      } yield (is_following.contains(followed_user) && followed_by.contains(follower_user))

      await(res) should beTrue
    }

    "distribute posts" in {
      val res = for {
        _ <- RedisService.flushall
        follower_user <- RedisService.register_user("user1", "pwd")
        followed_user <- RedisService.register_user("user2", "pwd")
        _ <- RedisService.follow_user(follower_user, followed_user)
        post_id <- RedisService.post_message(followed_user, "test post")
        is_following <- RedisService.is_following(follower_user)
        followed_by <- RedisService.followed_by(followed_user)
        follower_user_feed <- RedisService.get_user_feed(follower_user, 0)
        followed_user_feed <- RedisService.get_user_feed(followed_user, 0)
      } yield {
        println(s"following($followed_user) posts $followed_user_feed")
        println(s"follower($follower_user) posts $follower_user_feed")
        follower_user_feed.contains(post_id) && followed_user_feed.contains(post_id)
      }

      await(res) should beTrue
    }

    /*
    register user 1. make 1 post.
    register user 2. make 1 post. follow user 1.
    register user 3. view global feed, check that only appropriate users are followed

    UPDATE: bug is fixed, turns out user followers,
            following were being saved to the same 2 sets,
            due to string interpolation mistake. Still don't have a better name for this test case.
     */
    "not have that one weird bug" in {
      val res = for {
        _ <- RedisService.flushall

        //register user 1 and make a post
        user1 <- RedisService.register_user("user1", "password")
        post_1 <- RedisService.post_message(user1, "test post 1")

        //register user 2 and make a post
        user2 <- RedisService.register_user("user2", "password")
        _ <- RedisService.follow_user(user2, user1)
        post_2 <- RedisService.post_message(user1, "test post 2")
        user2_is_following <- RedisService.is_following(user2)
        user2_followed_by <- RedisService.is_following(user2)


        //register user 2 and make a post
        user3 <- RedisService.register_user("user3", "password")
        user3_is_following <- RedisService.is_following(user3)
        user3_followed_by <- RedisService.is_following(user3)

      } yield {
        println(s"$user2 user2_is_following: $user2_is_following user2_followed_by: $user2_followed_by")
        println(s"$user3 user3_is_following: $user3_is_following user3_followed_by: $user3_followed_by")
        val r = user3_is_following.isEmpty && user3_followed_by.isEmpty
        println(s"=> $r")
        r
      }

      await(res) should beTrue
    }




  }
}


















