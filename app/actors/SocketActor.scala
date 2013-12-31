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
import play.api.Play.current
import RedisActor._

class SocketActor extends Actor {

  private def extract_topics(s: String): Set[String] = s.split(" ").filter(_.startsWith("#")).map(_.tail).toSet


  var _op_id = 0L
  def next_op_id = {
    val prev = _op_id
    _op_id = _op_id + 1
    prev  
  }
  
  var pending = Map.empty[Long, (Long, RedisOp)]


  val init = List("#advert drugs", "tiger-team smart-claymore", "#minespace rebar",
				  "grenade #AI", "#chrome skyscraper #numinous sprawl savant", "#augmented #reality vinyl",
				  "#numinous #space crypto-rain", "sub-orbital corporation #sprawl #hacker",
				  "Tokyo tanto #augmented #reality", "#weathered augmented reality", "military-grade #wristwatch")


  case class UserChannel(user_id: Long, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)

  type User = Long
  
  val redisActor = Akka.system.actorOf(Props[RedisActor])


  // this map relate every user with his or her UserChannel
  var webSockets: Map[User, UserChannel] = Map.empty

  //track use of each topic
  //update to reflect Redis Integer capacity
  //var trending: Map[String, Long] = Map.empty.withDefaultValue(0L)

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
      
      sender ! userChannel.enumerator
      
      
      
    case AckSocket(user_id) =>
      log.debug(s"ack socket $user_id")

      //provision new socket with recent posts
      val op_id = next_op_id
      val op = RecentPosts(op_id)
      pending += op_id -> (user_id, op)
      redisActor ! op

      
      
    case  AckRecentPosts(op_id, result) => {
    
      val (user_id, _) = pending(op_id)
      pending -= op_id
      
      
      val topics = result.flatMap(m => m.topics)
      val trending = topics.foldLeft(Map.empty[String, Long].withDefaultValue(0L))( (t, topic) => t.updated(topic, t(topic) + 1))
      val popular = trending.toList.sortBy{case(k, v) => -v}//.take(5)
          .map{case (k, v) => Trend(k, v)}
    
    
      //todo: batch (?)
      println(s"ack recent posts => $result")
      result.foreach{ msg =>
            webSockets(user_id).channel push Update(
              Some(msg),
              Some(popular).filterNot(_.isEmpty)
            ).asJson
      }
     }

      
      

    case Msg(user_id, topics, msg) => {    

      val op_id = next_op_id
      val op = Post(op_id, user_id, msg)
      pending += op_id -> (user_id, op)
      redisActor ! op
      
    }
    
    
    case AckPost(op_id, post_id) => {
    
	val (user_id, Post(_, _, msg)) = pending(op_id)
	pending -= op_id
	
	println(s"ack post: $msg")

    
            webSockets(user_id).channel push Update(
              Some(Msg(user_id, extract_topics(msg), msg)),
              None
            ).asJson
    
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
      }

  }

  def removeUserChannel(user_id: Long) = {
    log debug s"removed channel for $user_id"
    webSockets -= user_id
  }

}


sealed trait SocketMessage
sealed trait JsonMessage{
  def asJson: JsValue
}


case class AckSocket(user_id: Long)


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
