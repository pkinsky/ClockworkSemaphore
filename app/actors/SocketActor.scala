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

import utils.Utils
import Utils._


import scala.util.Failure
import play.api.libs.json._
import scala.util.Success

import scalaz._

import entities._


class SocketActor extends Actor with RedisServiceLayerImpl with PubSubServiceLayerImpl {

  //this is weird, need to import from own companion object
  import SocketActor._

  case class UserChannel(uid: UserId, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)

  var webSockets: Map[UserId, UserChannel] = Map.empty


  def establishConnection(uid: UserId): UserChannel = {
    // only the first partial function here is registered as a callback, but subsequent subscribe requests still subscribe.
    // therefore subscribe method will need to be idempotent
    // todo: ensure that 2x users can subscribe using the same actor, move subscribe logic to service
    pubSubService.subscribe(s"${uid.uid}:feed"){
      case RMessage(channel, post_id) => channel.split(":") match {
        case Array(user_id, "feed") => self ! SendMessages("my_feed", UserId(user_id), PostId(post_id) :: Nil )
        case x => log.error(s"unparseable message $x")
      }
      case _ =>
    }

    val userChannel: UserChannel =  webSockets.get(uid) getOrElse {
        val (enumerator, channel): (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
        UserChannel(uid, 0, enumerator, channel)
      }

    userChannel.channelsCount = userChannel.channelsCount + 1
    webSockets += (uid -> userChannel)

    userChannel
  }


  private def send(uid: UserId)(update: Update): Unit =
    webSockets(uid).channel push update.asJson


  override def receive = {
    case StartSocket(user_id) => {
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator
    }

    case SendMessages(src, user_id, posts) => {

        val r = for {
          posts <- redisService.load_posts(posts)
          uids: Set[UserId] = posts.map( msg => msg.uid ).toSet

          following <- redisService.followed_by(user_id)

          users <- Future.sequence(uids.map{ uid => for {
              username <-  redisService.get_user_name(uid)
            } yield User(uid.uid, username, following.contains(uid))
          })

        } yield send(user_id)(Update(src, users.toSeq, posts))

        r.onComplete{
          case Failure(t) => log.error(s"failed to load posts $posts because $t")
          case Success(_) =>
        }

    }

    case MakePost(from, message) => {

      val r = for {
        _ <- predicate(message.nonEmpty, s"$from attempting to post empty message")
        post_id <- redisService.post_message(from, message)
      } yield ()

      r.onComplete{
        case Failure(t) => log.error("posting msg failed: " + t)
        case Success(_) =>
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
          pubSubService.unsubscribe(s"${user_id.uid}:feed")
            removeUserChannel(user_id)
        }
      }
  }

  def removeUserChannel(uid: UserId) = {
    //log debug s"removed channel for $user_id"
    webSockets -= uid
  }
}



object SocketActor {
  sealed trait SocketMessage

  case class SendMessages(src: String, user_id: UserId, posts: Seq[PostId])

  case class MakePost(author_uid: UserId, body: String)

  case class StartSocket(uid: UserId) extends SocketMessage

  case class SocketClosed(uid: UserId) extends SocketMessage
}