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
          //oh dear god what have I done so many lambdas


          following <- redisService.followed_by(user_id)

          users <- Future.sequence(uids.map{ uid => for {
              username <-  redisService.get_user_name(uid)
            } yield User(uid.uid, username, following.contains(uid))
          })


        } yield send(user_id)(Update(src, users.toSeq, posts))

        r.onComplete{
          case Failure(t) => log.error(s"failed to load posts $posts because $t")
          case Success(msg) =>
        }

    }

    case MakePost(from, message) => {

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
            client.unsubscribe(s"${user_id.uid}:feed")
            removeUserChannel(user_id)
        }
      }
  }

  def removeUserChannel(uid: UserId) = {
    //log debug s"removed channel for $user_id"
    webSockets -= uid
  }
}
