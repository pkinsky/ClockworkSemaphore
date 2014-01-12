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

import securesocial.core.{AuthenticationMethod, OAuth1Info, OAuth2Info, Identity, PasswordInfo, IdentityId, UserServicePlugin}


class SocketActor extends Actor {

  private def extract_topics(s: String): Set[String] = s.split(" ").filter(_.startsWith("#")).map(_.tail).toSet


  case class UserChannel(user_id: IdentityId, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)

  type User = IdentityId


  // this map relate every user with his or her UserChannel
  var webSockets: Map[User, UserChannel] = Map.empty

  

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


  def onRecentPosts(posts: Seq[Msg], user_id: User) = {

    posts.foreach{ msg =>
      webSockets(user_id).channel push Update(
        msg = Some(msg)
      ).asJson
    }

  }







  override def receive = {
    case StartSocket(user_id) => {
        val userChannel = establishConnection(user_id)
        sender ! userChannel.enumerator
      }
      
      
      
    case AckSocket(user_id) => {
      log.debug(s"ack socket $user_id")

      val result = for {
        posts <- RedisService.recent_posts
        //users <- Future.sequence(posts.map(p => RedisService.get_public_user(p.user_id).map(_.get)))
      } yield posts //users.zip(posts)

      result.onComplete{
        case Success(messages) =>   onRecentPosts(messages, user_id)
        case Failure(t) => log.error(s"recent posts fail: ${t}");
      }

      /*
      for {
        public_user <- RedisService.get_public_user(user_id)
      } {
        webSockets(user_id).channel push Update(
          user_info = Some(public_user)
        ).asJson
      */
      }

    case RequestAlias(user_id, alias) => {
        log.info(s"user $user_id requesting alias $alias")

        val alias_f = RedisService.establish_alias(user_id, alias)

        alias_f.foreach{ alias_pass =>

              }

        alias_f.map(result => AckRequestAlias(alias, result)).onComplete{
          case Success(ar) => webSockets(user_id).channel push Update(alias_result = Some(ar)).asJson
          case Failure(t) => log.error(s"error requesting alias: $t")
        }

    }





    case message@Msg(user_id, msg) => {
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

case class Msg(user_id: IdentityId, msg: String) extends SocketMessage with JsonMessage{
  def asJson = {
    implicit val format0 = Json.format[IdentityId]
    implicit val format = Json.format[Msg]
    Json.toJson(this)
  }
}


case class RequestAlias(user_id: IdentityId, alias: String) extends SocketMessage

case class AckRequestAlias(alias: String, pass: Boolean) extends JsonMessage{
  def asJson = {
    implicit val format = Json.format[AckRequestAlias]
    Json.toJson(this)
  }
}


case class PublicIdentity(user_id: IdentityId, alias: String, avatar_url: Option[String])


case class Update(msg: Option[Msg]=None, 
				  alias_result: Option[AckRequestAlias]=None,
				  user_info: Option[PublicIdentity]=None) extends JsonMessage {
  def asJson = {
    implicit val formata = Json.format[IdentityId]
    implicit val format0 = Json.format[PublicIdentity]
    implicit val format2 = Json.format[Msg]
    implicit val format3 = Json.format[AckRequestAlias]
    implicit val format4 = Json.format[Update]

    Json.toJson(this)
  }
}
