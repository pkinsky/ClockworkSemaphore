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
import service.{UserId, PostId, RedisServiceImpl, RedisService}



import scala.util.Failure
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsNumber
import scala.util.Success
import play.api.libs.json.JsObject

import ApplicativeStuff._
import scalaz._



class SocketActor extends Actor {

  val redisService: RedisService = RedisServiceImpl


  case class UserChannel(uid: UserId, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)


  // this map relate every user with his or her UserChannel
  var webSockets: Map[UserId, UserChannel] = Map.empty


  def establishConnection(uid: UserId): UserChannel = {
    //log.debug(s"establish socket connection for user $user_id")

    val userChannel: UserChannel =  webSockets.get(uid) getOrElse {
        val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
        UserChannel(uid, 0, broadcast._1, broadcast._2)

      }

    userChannel.channelsCount = userChannel.channelsCount + 1
    webSockets += (uid -> userChannel)

    userChannel
  }


  private def send(uid: UserId)(msgs: Seq[MsgInfo]): Unit =
    for (msg <- msgs) webSockets(uid).channel push msg.asJson


  override def receive = {
    case StartSocket(user_id) => {
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator
    }

    case SendMessage(user_id, msg) => {
        send(user_id)(msg :: Nil)
    }

    case MakePost(from, message) => {
      log.info(s"$from pushing msg: $message" )

      val r = for {
        post_id <- redisService.post_message(from, message)
      } yield {
	() //don't serialize here. use redis pubsub to handle pushing msg to recipients
      }

      r.onComplete{
        case Failure(t) => log.error("posting msg failed: " + t)
        case _ =>
      }
    }

    case SocketClosed(user_id) => {
        //log debug s"closed socket for $user_id"
        val userChannel = webSockets(user_id)

        if (userChannel.channelsCount > 1) {
          userChannel.channelsCount = userChannel.channelsCount - 1
          webSockets += (user_id -> userChannel)
          //log debug s"channel for user : $user_id count : ${userChannel.channelsCount}"
        } else {
          removeUserChannel(user_id)
        }
      }
  }

  def removeUserChannel(uid: UserId) = {
    //log debug s"removed channel for $user_id"
    webSockets -= uid
  }
}
