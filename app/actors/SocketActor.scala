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
import service.{RedisServiceImpl, RedisUserService, RedisService}


import securesocial.core._


import service.IdentityIdConverters._
import actors.RequestAlias
import securesocial.core.IdentityId
import actors.AckSocket
import scala.util.Failure
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsNumber
import actors.StartSocket
import actors.RequestInfo
import scala.util.Success
import play.api.libs.json.JsObject
import actors.SocketClosed

class SocketActor extends Actor {

  val redisService: RedisService = RedisServiceImpl


  case class UserChannel(user_id: IdentityId, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)


  // this map relate every user with his or her UserChannel
  var webSockets: Map[IdentityId, UserChannel] = Map.empty


  def establishConnection(user_id: IdentityId): UserChannel = {
    log.debug(s"establish socket connection for user $user_id")

    val userChannel: UserChannel =  webSockets.get(user_id) getOrElse {
        val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
        UserChannel(user_id, 0, broadcast._1, broadcast._2)

      }

    userChannel.channelsCount = userChannel.channelsCount + 1
    webSockets += (user_id -> userChannel)

    userChannel
  }


  def onRecentPosts(posts: Seq[Msg], user_id: IdentityId) = {

    posts.reverse.foreach{ msg =>
      webSockets(user_id).channel push Update(
        msg = Some(msg)
      ).asJson
    }

  }



  override def receive = {
    case StartSocket(user_id) =>
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator




    case AckSocket(user_id) =>
      log.debug(s"ack socket $user_id")

      val result = for {
        posts <- redisService.recent_posts
      } yield posts

      result.onComplete{
        case Success(messages) =>   onRecentPosts(messages, user_id)
        case Failure(t) => log.error(s"recent posts fail: ${t}");
      }



    case RequestAlias(user_id, alias) =>
        log.info(s"user $user_id requesting alias $alias")
        val trimmed = alias.trim

        if (trimmed.contains(" ")){ //seperate validation somehow
          log.info(s"bad user id: $user_id")
        } else {

          val alias_f = redisService.establish_alias(user_id, trimmed)

          alias_f.map(result => AckRequestAlias(trimmed, result)).onComplete{
            case Success(ar) => webSockets(user_id).channel push Update(alias_result = Some(ar)).asJson
            case Failure(t) => log.error(s"error requesting alias: $t")
          }
        }



    case RequestInfo(requester, user_id) =>
      redisService.get_public_user(user_id).onComplete{
          case Success(user_info) => webSockets(requester).channel push Update(user_info = Some(user_info)).asJson
          case Failure(t) => log.error(s"error: ${t}");
        }



    case message@Msg(timestamp, user_id, msg) =>
        redisService.post(message).onComplete{ _ =>
                webSockets(user_id).channel push Update(
                  Some(message),
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

  def removeUserChannel(user_id: IdentityId) = {
    log debug s"removed channel for $user_id"
    webSockets -= user_id
  }
}


sealed trait SocketMessage
sealed trait JsonMessage{
  def asJson: JsValue
}


case class AckSocket(user_id: IdentityId)

case object Register extends SocketMessage

case class StartSocket(user_id: IdentityId) extends SocketMessage

case class SocketClosed(user_id: IdentityId) extends SocketMessage

case class RequestAlias(user_id: IdentityId, alias: String) extends SocketMessage

case class RequestInfo(requester: IdentityId, user_id: IdentityId) extends SocketMessage


object Msg {

  implicit val format = new Format[Msg]{
    def writes(msg: Msg): JsValue = {
      JsObject(Seq(
        ("timestamp", JsNumber(msg.timestamp)),
        ("user_id", JsString(idToString(msg.user_id))),
        ("msg", JsString(msg.body))
      ))
    }

    def reads(json: JsValue): JsResult[Msg] =
      for{
        timeStamp <- Json.fromJson[Long](json \ "timestamp")
        identityId <- Json.fromJson[String](json \ "user_id").map(stringToId(_))
        msg <- Json.fromJson[String](json \ "msg")
      } yield Msg(timeStamp,identityId, msg)





  }

}

object AckRequestAlias {
  implicit val format = Json.format[AckRequestAlias]
}

object PublicIdentity {
  implicit val format1 = Json.format[IdentityId]
  implicit val format2 = Json.format[PublicIdentity]}

object Update {
  implicit val format = Json.format[Update]
}

//todo: add post id to Msg for absolute ordering
case class Msg(timestamp: Long, user_id: IdentityId, body: String) extends JsonMessage with SocketMessage{
  def asJson = Json.toJson(this)
}

case class AckRequestAlias(alias: String, pass: Boolean) extends JsonMessage{
  def asJson = Json.toJson(this)
}

case class PublicIdentity(user_id: String, alias: String, avatar_url: Option[String])

case class Update(msg: Option[Msg]=None,
				  alias_result: Option[AckRequestAlias]=None,
				  user_info: Option[PublicIdentity]=None) extends JsonMessage {

  def asJson = Json.toJson(this)
}