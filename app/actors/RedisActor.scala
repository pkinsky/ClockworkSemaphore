package actors


import scredis._
import scredis.parsing.Implicits._

import scalaz.syntax.applicative.ToApplyOps


import scala.util.{ Success, Failure }
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.Akka
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{Props, Actor}

import service.RedisBase





trait RedisOps extends RedisBase {
    import ApplicativeStuff._

  import service.RedisUserService.redis

  private def next_post_id: Future[String] = redis.incr("global:nextPostId").map(_.toString)

  private def topics(s: String): Set[String] = s.split(" ").filter(_.startsWith("#")).map(_.tail).toSet

  def recent_posts: Future[Seq[Msg]] =
    for {
      timeline <- redis.lRange[String](global_timeline, 0, 50)
      posts <-  Future.sequence(timeline.map{post_id =>

        for {
          a <- redis.get[String](post_author(post_id))
          b <- redis.get[String](post_body(post_id))
        } yield (a, b)

      })
    } yield posts.map{case (Some(author), Some(post)) => Msg(author,  topics(post), post)} //fail horribly on failed lookup...

  def followers(user_id: String): Future[Set[String]] = {
		redis.sMembers[String](user_followers(user_id))
  }


  def post(user_id: String, msg: String) = {
  
	def trim_global = redis.lTrim(global_timeline,0,1000)
	
    def handle_post(post_id: String, audience: Set[String]) =
        (redis.lPush(global_timeline,post_id)
           |@| redis.set(post_body(post_id), msg)
           |@| redis.set(post_author(post_id), user_id)
           |@| Future.sequence(
                  audience.map{u =>
                      redis.lPush(s"user:$u:posts", post_id)
                  }
               )
        ){ (a,b,c,d) => () }


    for {
      (post_id, followers) <- (next_post_id |@| followers(user_id))((a,b) => (a,b))
	   _ <- handle_post(post_id, followers)
	   _ <- trim_global
    } yield post_id
  }
  
}


object RedisActor {

	trait RedisOp{
	  val op_id: Long
	}

	case class RecentPosts(op_id: Long) extends RedisOp
	case class AckRecentPosts(op_id: Long, result: Seq[Msg])

	case class Post(op_id: Long, user_id: String, msg: String) extends RedisOp
	case class AckPost(op_Id: Long, post_id: String)
	
	case class Fail(op_Id: Long, t: Throwable)
	
}




class RedisActor extends Actor with RedisOps {
	import RedisActor._	
	

	  override def receive = {
	  
		case Post(op_id, user_id, msg) => {
		
			val s = sender
		
				post(user_id, msg).onComplete{
					case Success(post_id) => s ! AckPost(op_id, post_id)
					case Failure(t) => s ! Fail(op_id, t)
				}
			}
		
		case RecentPosts(op_id) => {
		
			val s = sender
		  
				recent_posts.onComplete{
					case Success(msgs) => s ! AckRecentPosts(op_id, msgs)
					case Failure(t) => s ! Fail(op_id, t)
				}
			}
		
	  }

	

}

