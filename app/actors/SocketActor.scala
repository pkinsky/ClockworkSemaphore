package actors

import akka.actor.{Props, Actor}
import entities._
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.functional.syntax._
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.{Concurrent, Enumerator}
import play.api.libs.json._
import play.api.libs.json._
import play.api.Logger
import play.api.Play.current
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import scala.util.Success
import scala.util.{ Success, Failure }
import scalaz._
import scredis.pubsub.{Message => RMessage}
import service._
import utils.Logging
import utils.Utils._



class SocketActor extends Actor with Logging {
  import SocketActor._ //import messages from companion object

  case class UserChannel(uid: UserId, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  var webSockets: Map[UserId, UserChannel] = Map.empty

  //akka actors are single threaded, so we can grab our own (non-thread safe) redis client to handle subscriptions
  val client = RedisService.getClient

  override def postStop(): Unit = client.quit()

  def establishConnection(uid: UserId): UserChannel = {
    // only the first partial function here is registered as a callback, but subsequent subscribe requests still subscribe.
    // therefore subscribe method will need to be idempotent
    // todo: ensure that 2x users can subscribe using the same actor, move subscribe logic to service

    client.subscribe(s"${uid.uid}:feed"){
      case RMessage(channel, post_id) => channel.split(":") match {
        case Array(user_id, "feed") => {
          log.info(s"received message $post_id to channel $channel")
          self ! SendMessages("my_feed", UserId(user_id), PostId(post_id) :: Nil )
        }
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

    // Request that messages with post id's in posts be sent to user user_id, associated with feed src
    case SendMessages(src, user_id, posts) => {

        val r = for {
          posts <- RedisService.load_posts(posts)
          following <- RedisService.is_following(user_id)

          //load user info for each unique user in the loaded posts
          uids: Set[UserId] = posts.map( msg => msg.uid ).toSet
          users <- Future.sequence(uids.map{ uid => for {
              username <-  RedisService.get_user_name(uid)
            } yield User(uid.uid, username, following.contains(uid))
          })
        } yield {
          val update = Update(src, users.toSeq, posts)
          log.info(s"SendMessages(src: $src, user_id: $user_id, posts: $posts) =>  $update")
          send(user_id)(update)
        }

        r.onFailure{
          case t => log.error(s"failed to load posts $posts because $t")
        }

    }

    case MakePost(from, message) => {

      val r = for {
        _ <- predicate(message.nonEmpty, s"$from attempting to post empty message")
        post_id <- RedisService.post_message(from, message)
      } yield ()

      r.onFailure{
        case t => log.error("posting msg failed: " + t)
      }
    }

    case SocketClosed(user_id) => {
        val userChannel = webSockets(user_id)

        if (userChannel.channelsCount > 1) {
          userChannel.channelsCount = userChannel.channelsCount - 1
          webSockets += (user_id -> userChannel)
        } else {
          client.unsubscribe(s"${user_id.uid}:feed")
            removeUserChannel(user_id)
        }
      }
  }

  def removeUserChannel(uid: UserId) = {
    webSockets -= uid
  }
}


/**
 * Companion object of SocketActor. holds messages accepted by SocketActor
 */
object SocketActor {

  /**
   * Marker trait for messages accepted by this actor
   */
  sealed trait SocketMessage

  /**
   * Deliver existing messages to a user via their websocket connection
   * @param src global_feed or my_feed.
   * @param user_id user to deliver the messages to
   * @param posts sequence of post id's which should be loaded and sent to user_id
   */
  case class SendMessages(src: String, user_id: UserId, posts: Seq[PostId]) extends SocketMessage

  /**
   * Persist and distribute a message
   * @param author_uid ui of the user making this post
   * @param body body of the message
   */
  case class MakePost(author_uid: UserId, body: String) extends SocketMessage

  /**
   * establish a websocket connection and return an Enumerator[JsValue] to the sender of this message.
   * start listening for updates to that user's feed
   * @param uid user id with which to associate this websocket
   */
  case class StartSocket(uid: UserId) extends SocketMessage

  /**
   * terminate a user's websocket connection and stop listening for updates to that user's feed
   * @param uid user id with which to associate this websocket
   */
  case class SocketClosed(uid: UserId) extends SocketMessage
}

