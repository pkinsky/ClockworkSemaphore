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
import service._

import scredis.pubsub.{Message => RMessage}



import scala.util.Failure
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsNumber
import scala.util.Success
import play.api.libs.json.JsObject

import ApplicativeStuff._
import scalaz._


//handles websocket, listens as redis client for pubsub messages signaling new posts
class SocketActor extends Actor with RedisConfig {

  val redisService: RedisService = RedisServiceImpl

  case class UserChannel(uid: UserId, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  //lazy val log = Logger("application." + this.getClass.getName)


  // this map relate every user with his or her UserChannel
  var webSockets: Map[UserId, UserChannel] = Map.empty


  def establishConnection(uid: UserId): UserChannel = {

    //only the first partial function here is registered as a callback, but subsequent subscribe requests still subscribe.
    // therefore subscribe method will need to be idempotent
    client.subscribe(s"${uid.uid}:feed"){
      case RMessage(channel, post_id) => channel.split(":") match {
        case Array(user_id, "feed") => self ! SendMessage(UserId(user_id), PostId(post_id))
        case x => log.error(s"unparseable message $x")
      }
      case _ =>

    }
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

    case SendMessage(user_id, post_id) => {
        redisService.load_post(post_id).onComplete{
          case Success(msg) => send(user_id)(MsgInfo(post_id.pid, msg) :: Nil)
          case Failure(t) => log.error(s"failed to load post $post_id because of $t")
        }
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
