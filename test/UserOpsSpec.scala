import actors.Msg
import service.RedisServiceImpl
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.test._


/*
need to ensure this is run against test redis instance, eventually. Until then manual flushall

 */


object StringSpecification extends PlaySpecification {

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


















