import org.specs2.mutable._
import service.RedisServiceImpl
import service._
import play.api.test._
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalacheck.Prop.forAll
import scala.concurrent.duration._
import scala.concurrent._

/*
need to ensure this is run against test redis instance, eventually. Until then manual flushall

 */
import org.scalacheck.Properties
import org.scalacheck.Prop.forAll

object StringSpecification extends Properties("UserOps") {

  val redis_service = RedisServiceImpl


  property("register") = forAll  { (username: String, password: String) =>
        val res = for {
          _ <- redis_service.flushall
          uid <- redis_service.register_user(username, password)
        } yield uid

        val uid = Await.result(res, 1 second)
        uid.forall( c => ('0' to '9').contains(c) )
      }

    property("follow") = forAll  { (username1: String, password1: String, username2: String, password2: String) =>
      val res = for {
        _ <- redis_service.flushall
        follower_uid <- redis_service.register_user(username1, password1)
        following_uid <- redis_service.register_user(username2, password2)
        _ <- redis_service.follow_user(follower_uid, following_uid)
        following <- redis_service.get_following(follower_uid)
        followers <- redis_service.get_followers(following_uid)
      } yield (following.contains(follower_uid) && followers.contains(following_uid))

      Await.result(res, 1 second)
    }



}


















