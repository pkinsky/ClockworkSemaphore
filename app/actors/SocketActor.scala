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
    case StartSocket(user_id) =>
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator


    case RecentPosts(user_id) =>
      redisService.recent_posts(user_id).onComplete{
        case Success(messages) =>
            send(user_id)(Update(
              msg = messages,
              recent_messages = messages.map( msgInfo => msgInfo.post_id )
            ))
        case Failure(t) => log.error(s"recent posts fail: ${t}");
      }

	
    case SetAboutMe(user_id, about_me) =>
      log.info(s"set about me for $user_id to $about_me")
      redisService.set_about_me(user_id, about_me).onComplete{
        case Failure(t) => log.error(s"set about me fail: $t")
        case Success(_) =>  //todo: ack success? Just set new about_me client-side and assume set succeeded? Let's start with #2 because fuckit, and move to #1 later
    }



    case RequestAlias(user_id, alias) =>
      val trimmed = alias.trim

      if (trimmed.length > 32){
        log.error(s"oversized alias $trimmed made it through client side validation.")
      }else{
        val alias_f = redisService.establish_alias(user_id, trimmed)

        alias_f.map(result => AckRequestAlias(trimmed, result)).onComplete{
          case Success(ar) => webSockets(user_id).channel push Update(alias_result = Some(ar)).asJson
          case Failure(t) => log.error(s"error requesting alias: $t")
        }
      }



    case RequestInfo(requester, user_id) =>
      redisService.get_public_user(requester, user_id).onComplete{
          case Success(user_info) => webSockets(requester).channel push Update(user_info = Some(user_info)).asJson
          case Failure(t) => log.error(s"error: ${t}");
        }


    case MakePost(from, message) => {
      log.info(s"$from pushing msg: $message" )
      redisService.post(from, message).onComplete{
        case Success(post_id) => self ! PushPost(from, MsgInfo(post_id, false, message))
        case Failure(t) => log.error("posting msg failed: " + t)
      }
    }


    case PushPost(to, msg_info) => {
      log.info(s"pushing msg info: $msg_info to $to" )
      webSockets(to).channel push Update(
        msg = msg_info :: Nil,
        recent_messages = msg_info.post_id :: Nil
      ).asJson
    }


    case RequestPost(to, post_id) => {
      log.info(s"load msg info for post_id:$post_id")
      redisService.load_msg_info(to, post_id).onComplete{
        case Success(Some(msg)) => self ! PushPost(to, msg)
        case Success(None) => log.error(s"msg not found") //every time a user reqs a deleted message
        case Failure(t) => log.error(s"msg not found due to error $t")
      }
    }

    //ui will only present option for unfavorited messages. In (edge) case where already favorite, silent no-op
    case FavoriteMessage(user_id, post_id) => {
      log.info(s"$user_id favorite $post_id")
      redisService.add_favorite_post(user_id, post_id)
    }

    //ui will only present option for favorited messages. In (edge) case where not favorite already, silent no-op
    case UnFavoriteMessage(user_id, post_id) => {
      log.info(s"$user_id unfavorite $post_id")
      redisService.remove_favorite_post(user_id, post_id)
    }


    case UnFollowUser(uid, to_unfollow) =>
      redisService.unfollow_user(uid, to_unfollow)
        .onComplete( res => log.info(s"$uid unfollowed $to_unfollow: with result $res"))


    case FollowUser(uid, to_follow) =>
      redisService.follow_user(uid, to_follow)
        .onComplete( res => log.info(s"$uid followed $to_follow: with result $res"))


    case DeleteMessage(userId, post_id) => {
      log.info(s"delete message $post_id")


      val delete = for {
          post <- redisService.load_post(post_id)
          if !post.isEmpty && post.get.uid == userId //check that user is deleting own post
          _ <- redisService.delete_post(post_id)
      } yield ()


      delete.onComplete{
        case Success(_) => send(userId)( Update(deleted = Set(post_id)))
        case Failure(t) => log.error(s"failed to delete post due to $t") //if nosuch element exception, if statement filtered out result
      }
    }

    case SocketClosed(user_id) =>
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

  def removeUserChannel(uid: String) = {
    //log debug s"removed channel for $user_id"
    webSockets -= uid
  }
}
