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
import service.{RedisService, RedisUserService}

class SocketActor extends Actor {

  private def extract_topics(s: String): Set[String] = s.split(" ").filter(_.startsWith("#")).map(_.tail).toSet


  var _op_id = 0L
  def next_op_id = {
    val prev = _op_id
    _op_id = _op_id + 1
    prev  
  }


  val init = List("#advert drugs", "tiger-team smart-claymore", "#minespace rebar",
				  "grenade #AI", "#chrome skyscraper #numinous sprawl savant", "#augmented #reality vinyl",
				  "#numinous #space crypto-rain", "sub-orbital corporation #sprawl #hacker",
				  "Tokyo tanto #augmented #reality", "#weathered augmented reality", "military-grade #wristwatch")


  case class UserChannel(user_id: String, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)

  type User = String


  // this map relate every user with his or her UserChannel
  var webSockets: Map[User, UserChannel] = Map.empty

  //track use of each topic
  //update to reflect Redis Integer capacity
  //var trending: Map[String, Long] = Map.empty.withDefaultValue(0L)




  def establishConnection(user_id: User): UserChannel = {
    log.debug(s"establish socket connection for user $user_id")


    val userChannel: UserChannel =  webSockets.get(user_id) getOrElse {
        val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
        UserChannel(user_id, 0, broadcast._1, broadcast._2)

      }

    userChannel.channelsCount = userChannel.channelsCount + 1
    webSockets += (user_id -> userChannel)

    userChannel
  }


  def onConnect(user_id: User) = {

    val userChannel: UserChannel = establishConnection(user_id)



  }


  def onRecentPosts(posts: Seq[Msg], user_id: User) = {

    //not server side, remove topics.
    val topics = posts.flatMap(m => m.topics)
    val trending = topics.foldLeft(Map.empty[String, Long].withDefaultValue(0L))( (t, topic) => t.updated(topic, t(topic) + 1))
    val popular = trending.toList.sortBy{case(k, v) => -v}//.take(5)
      .map{case (k, v) => Trend(k, v)}


    posts.foreach{ msg =>
      webSockets(user_id).channel push Update(
        Some(msg),
        Some(popular).filterNot(_.isEmpty)
      ).asJson
    }

  }







  override def receive = {
    case StartSocket(user_id) => {
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator
      }
      
      
      
    case AckSocket(user_id) =>
      log.debug(s"ack socket $user_id")

      RedisService.recent_posts.onComplete{
        case Success(messages) =>   onRecentPosts(messages, user_id)
        case Failure(t) => log.error(s"recent posts fail: $t")
      }




    case RequestAlias(user_id, alias) => {
        val alias_f = RedisService.establish_alias(user_id, alias)

        alias_f.foreach{ alias_pass =>

              }

        alias_f.map(result => AckRequestAlias(alias, result)).onComplete{
          case Success(ar) => webSockets(user_id).channel push Update(alias_result = Some(ar)).asJson
          case Failure(t) => log.error(s"error requesting alias: $t")
        }

    }





    case message@Msg(user_id, topics, msg) => {
	RedisService.post(user_id, msg).onComplete{ _ => 
        	webSockets(user_id).channel push Update(
	          Some(message),
        	  None
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
      }

  }

  def removeUserChannel(user_id: String) = {
    log debug s"removed channel for $user_id"
    webSockets -= user_id
  }

}


sealed trait SocketMessage
sealed trait JsonMessage{
  def asJson: JsValue
}


case class AckSocket(user_id: String)


case object Register extends SocketMessage

case class StartSocket(user_id: String) extends SocketMessage

case class SocketClosed(user_id: String) extends SocketMessage

case class Msg(user_id: String, topics: Set[String], msg: String) extends SocketMessage with JsonMessage{
  def asJson = {
    implicit val format = Json.format[Msg]
    Json.toJson(this)
  }
}


case class RequestAlias(user_id: String, alias: String) extends SocketMessage

case class AckRequestAlias(alias: String, pass: Boolean) extends JsonMessage{
  def asJson = {
    implicit val format = Json.format[AckRequestAlias]
    Json.toJson(this)
  }

}


case class Trend(name: String, count: Long)

case class Update(msg: Option[Msg]=None, trending: Option[Seq[Trend]]=None, alias_result: Option[AckRequestAlias]=None) extends JsonMessage {
  def asJson = {
    implicit val format1 = Json.format[Trend]
    implicit val format2 = Json.format[Msg]
    implicit val format3 = Json.format[AckRequestAlias]
    implicit val format4 = Json.format[Update]

    Json.toJson(this)
  }
}
