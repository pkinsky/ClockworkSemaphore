package actors


import scredis._
import scredis.parsing.Implicits._

import scalaz.syntax.applicative.ToApplyOps


import scala.util.{ Success, Failure }
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.Akka
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{Props, Actor}
import com.typesafe.config.{ConfigValueFactory, ConfigValue, Config, ConfigFactory}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._




trait RedisBase {
  val global_timeline = "global:timeline"  //list

  def post_body(post_id: Long) = s"post:$post_id:body"
  def post_author(post_id: Long) = s"post:$post_id:author"

  def user_followers(user_id: Long) =  s"uid:$user_id:followers" //uids of followers

  val redisUri = new java.net.URI(sys.env.get("REDISCLOUD_URL").getOrElse("redis://rediscloud:raRzMQoBfJTFtwIu@pub-redis-18175.us-east-1-2.2.ec2.garantiadata.com:18175"))

  val config = ConfigFactory.empty
    .withValue("client",
      ConfigValueFactory.fromMap(
        Map(
          "host" -> redisUri.getHost(),
          "port" -> redisUri.getPort(),
          "password" -> "raRzMQoBfJTFtwIu"
        ).asJava
      )
    )


  val redis = Redis(config)

}


trait RedisOps extends RedisBase {
    import ApplicativeStuff._


  private def next_post_id: Future[Long] = redis.incr("global:nextPostId")

  private def topics(s: String): Set[String] = s.split(" ").filter(_.startsWith("#")).map(_.tail).toSet

  def recent_posts: Future[Seq[Msg]] =
    for {
      timeline <- redis.lRange[Long](global_timeline, 0, 50)
      posts <-  Future.sequence(timeline.map{post_id =>

        for {
          a <- redis.get[Long](post_author(post_id))
          b <- redis.get[String](post_body(post_id))
        } yield (a, b)

      })
    } yield posts.map{case (Some(author), Some(post)) => Msg(author,  topics(post), post)} //fail horribly on failed lookup...

  def followers(user_id: Long): Future[Set[Long]] = {
		redis.sMembers[Long](user_followers(user_id))
			.map(_.map(java.lang.Long.parseLong)) 
			//sMembers return type is not parameterized with [Long], despite taking an implicit Parser[Long]
			//ticket filed, will fix if neccesary. Meanwhile, fuck you scredis.
	}

  def post(user_id: Long, msg: String) = {
  
	def trim_global = redis.lTrim(global_timeline,0,1000)
	
    def handle_post(post_id: Long, audience: Set[Long]) =
        (redis.lPush(global_timeline,post_id)
           |@| redis.set(post_body(post_id), msg)
           |@| redis.set(post_author(post_id), user_id)
           |@| Future.sequence(
                  audience.map{u =>
                      redis.lPush(s"uid:$u:posts", post_id)
                  }
               )
        ){ (a,b,c,d) => () }


    for {
      (post_id, followers) <- (next_post_id |@| followers(user_id))((a,b) => (a,b))
	   _ <- handle_post(post_id, followers)
	   _ <- trim_global
    } yield post_id
  }
  
  
  
  
  
  
  def register_user = 
      for {
	uid <- redis.incr("global:nextUserId")
	  name = s"nameof-$uid"
	  auth = s"auth-$uid"
	_ <- (redis.set(s"uid:$uid:username", name)
	     |@| redis.set(s"uid:$uid:password", "foobar")
	     |@| redis.set(s"username:$name:uid", uid)
	     |@| redis.set(s"uid:$uid:auth", auth)
	     |@| redis.set(s"auth:$auth", uid))( (a,b,c,d,e) => () )
      } yield {
	println(s"uid => $uid => $auth")
	uid
      }
  
  
  


}


object RedisActor {

	trait RedisOp{
	  val op_id: Long
	}

	case class RecentPosts(op_id: Long) extends RedisOp
	case class AckRecentPosts(op_id: Long, result: Seq[Msg])

	case class Post(op_id: Long, user_id: Long, msg: String) extends RedisOp
	case class AckPost(op_Id: Long, post_id: Long)
	
	case class RegisterUser(op_id: Long) extends RedisOp //TODO: username, password
	case class AckRegisterUser(op_id: Long, user_id: Long) //TODO: username, password
	
	
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
			
		case RegisterUser(op_id) => {
		
			val s = sender
		  
				register_user.onComplete{
					case Success(uid) => s ! AckRegisterUser(op_id, uid)
					case Failure(t) => s ! Fail(op_id, t)
				}
		}
		
	  }

	

}

