package actors

import akka.actor.{Props, Actor}

import play.api.libs.iteratee.{Concurrent, Enumerator}

import play.api.libs.iteratee.Concurrent.Channel
import play.api.Logger
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.util.{ Success, Failure }
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.Akka
import scala.concurrent.{ExecutionContext, Future}

import scredis._
import scredis.parsing.Implicits._

import scalaz._
import Scalaz._
/*
import scalaz.Applicative
import scalaz.syntax.ApplySyntax
*/

trait RedisSchema {
  val global_timeline = "global:timeline"  //list

  def post_body(post_id: Long) = s"post:$post_id:body"
  def post_author(post_id: Long) = s"post:$post_id:author"

  def user_followers(user_id: Long) =  s"uid:$user_id:followers" //uids of followers
}


trait RedisOps extends RedisSchema {
    import ApplicativeStuff._

  val redis = Redis()

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


}


object RedisActor {
	case class RecentPosts(op_id: Long)
	case class AckRecentPosts(op_id: Long, result: Seq[Msg])

	case class Post(op_id: Long, user_id: Long, msg: String)
	case class AckPost(op_Id: Long, post_id: Long)
	case class Fail(op_Id: Long, t: Throwable)
}

class RedisActor extends Actor with RedisOps {
	import RedisActor._

	//needs prop redis ref

		
	

	  override def receive = {
	  
		case Post(op_id, user_id, msg) => {
				post(user_id, msg).onComplete{
					case Success(post_id) => sender ! AckPost(op_id, post_id)
					case Failure(t) => sender ! Fail(op_id, t)
				}
			}
		
		case RecentPosts(op_id) => {
				recent_posts.onComplete{
					case Success(msgs) => sender ! AckRecentPosts(op_id, msgs)
					case Failure(t) => sender ! Fail(op_id, t)
				}
			}
		
	  }

	

}



class SocketActor extends Actor with RedisOps {

  val init = List("#advert drugs", "tiger-team smart-claymore", "#minespace rebar",
				  "grenade #AI", "#chrome skyscraper #numinous sprawl savant", "#augmented #reality vinyl",
				  "#numinous #space crypto-rain", "sub-orbital corporation #sprawl #hacker",
				  "Tokyo tanto #augmented #reality", "#weathered augmented reality", "military-grade #wristwatch")


  case class UserChannel(user_id: Long, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)

  type User = Long

  // this map relate every user with his UserChannel
  var webSockets: Map[User, UserChannel] = Map.empty

  //subscriptions
  var subscriptions: Map[User, Seq[String]] = Map.empty

  //track use of each topic
  //update to reflect Redis Integer capacity
  var trending: Map[String, Long] = Map.empty.withDefaultValue(0L)

  override def receive = {
    case StartSocket(user_id) =>
      log.debug(s"start new socket for user $user_id")

      val userChannel: UserChannel = webSockets.get(user_id) getOrElse {
        val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
        UserChannel(user_id, 0, broadcast._1, broadcast._2)
      }

      userChannel.channelsCount = userChannel.channelsCount + 1
      webSockets += (user_id -> userChannel)

      log debug s"channel for user : $user_id count : ${userChannel.channelsCount}"
      log debug s"channel count : ${webSockets.size}"

	  
      recent_posts.foreach{_.foreach{m => println(s"recent post: $m"); self ! m}}

      sender ! userChannel.enumerator




    case Msg(user_id, topics, msg) => {

      trending = topics.foldLeft(trending)( (t, topic) => t.updated(topic, t(topic) + 1))

      //this is not efficient. Should keep running tally in redis
      val popular = trending.toList.sortBy{case(k, v) => -v}//.take(5)
          .map{case (k, v) => Trend(k, v)}

      post(user_id, msg).foreach{ unit => 

        //should only trigger on message save
        webSockets(user_id).channel push Update(
              Some(Msg(user_id, topics, msg)),
              Some(popular).filterNot(_.isEmpty)
            ).asJson

      }
    }


    case SocketClosed(user_id) =>
      log debug s"closed socket for $user_id"

      val userChannel = webSockets(user_id)

      if (userChannel.channelsCount > 1) {
        userChannel.channelsCount = userChannel.channelsCount - 1
        webSockets += (user_id -> userChannel)
        log debug s"channel for user : $user_id count : ${userChannel.channelsCount}"
      } else {
        removeUserChannel(user_id)
        log debug s"removed channel for $user_id"
      }

  }

  def removeUserChannel(user_id: Long) = webSockets -= user_id

}


sealed trait SocketMessage
sealed trait JsonMessage{
  def asJson: JsValue
}


case class Register extends SocketMessage

case class StartSocket(user_id: Long) extends SocketMessage

case class SocketClosed(user_id: Long) extends SocketMessage

case class Msg(user_id: Long, topics: Set[String], msg: String) extends SocketMessage with JsonMessage{
  def asJson = {
    implicit val format = Json.format[Msg]
    Json.toJson(this)
  }
}


case class Trend(name: String, count: Long)

case class Update(msg: Option[Msg], trending: Option[Seq[Trend]]) extends JsonMessage {
  def asJson = {
    implicit val format1 = Json.format[Trend]
    implicit val format2 = Json.format[Msg]
    implicit val format4 = Json.format[Update]

    Json.toJson(this)
  }
}
