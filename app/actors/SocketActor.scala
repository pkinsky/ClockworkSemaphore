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
import securesocial.core.IdentityId
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


  private def send(user_id: IdentityId)(update: Update): Unit = webSockets(user_id).channel push update.asJson



  def onRecentPosts(posts: List[MsgInfo], user_id: IdentityId) =
      send(user_id)(Update(
          msg = posts
        ))


  override def receive = {
    case StartSocket(user_id) =>
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator


    case AckSocket(user_id) =>
      log.debug(s"ack socket $user_id")

      redisService.recent_posts(user_id).onComplete{
        case Success(messages) => onRecentPosts(messages, user_id)
        case Failure(t) => log.error(s"recent posts fail: ${t.getStackTraceString}");
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



      //when a user posts a message.
    case message@Msg(timestamp, user_id, msg) =>
        redisService.post(message).onComplete{
          case Success(post_id) =>
                webSockets(user_id).channel push Update(
                  msg = MsgInfo(post_id, false, message) :: Nil
                ).asJson
          case Failure(t) => log.error("posting msg failed: " + t)
        }


    case FavoriteMessage(userId, post_id) => {


      redisService.add_favorite_post(userId, post_id)

      //todo (?): ack

    }

    case UnFavoriteMessage(userId, post_id) => {

      redisService.remove_favorite_post(userId, post_id)

      //todo (?): ack

    }


    case DeleteMessage(userId, post_id) => {

      //check that requester is author of post before deletion
      for {
        post <- redisService.load_post(post_id)
        _ <- { if (post.user_id == userId)
                    Future.failed(new Exception("can't delete another user's posts"))
               else
                    Applicative[Future].point{ () }
             }
        _ <- redisService.delete_post(post_id)
      } yield ()

      //get post
      //if user_id same as post.user_id delete,
      //else error
      //need to send deleted_posts with update


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
