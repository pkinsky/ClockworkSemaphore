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
import service.{RedisServiceImpl, RedisService}



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


  case class UserChannel(uid: String, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)


  // this map relate every user with his or her UserChannel
  var webSockets: Map[String, UserChannel] = Map.empty


  def establishConnection(uid: String): UserChannel = {
    //log.debug(s"establish socket connection for user $user_id")

    val userChannel: UserChannel =  webSockets.get(uid) getOrElse {
        val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
        UserChannel(uid, 0, broadcast._1, broadcast._2)

      }

    userChannel.channelsCount = userChannel.channelsCount + 1
    webSockets += (uid -> userChannel)

    userChannel
  }


  private def send(uid: String)(update: Update): Unit =
    webSockets(uid).channel push update.asJson


  override def receive = {
    case StartSocket(user_id) => {
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator
    }

    case RecentPosts(user_id) => {
      redisService.recent_posts(user_id).onComplete{
        case Success(messages) =>
            send(user_id)(Update(
              msg = messages,
              recent_messages = messages.map( msgInfo => msgInfo.post_id )
            ))
        case Failure(t) => log.error(s"recent posts fail: ${t}");
      }
    }

	
    case SetAboutMe(user_id, about_me) => {
        log.info(s"set about me for $user_id to $about_me")
        redisService.set_about_me(user_id, about_me).onComplete{
          case Failure(t) => log.error(s"set about me fail: $t")
          case Success(_) => ()
        }
      }

    case MakePost(from, message) => {
      log.info(s"$from pushing msg: $message" )

      val r = for {
        post_id <- redisService.post(from, message)
        recipients <- redisService.get_followers(from)
      } yield {

        for {
          recipient <- recipients + from
        } yield {
          //push to each recipient
          val msg_info = MsgInfo(post_id, false, message)
          webSockets(recipient).channel push Update(
            msg = msg_info :: Nil,
            recent_messages = post_id :: Nil
          ).asJson
        }

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

  def removeUserChannel(uid: String) = {
    //log debug s"removed channel for $user_id"
    webSockets -= uid
  }
}
