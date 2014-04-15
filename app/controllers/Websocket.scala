package controllers

import play.api.mvc.{WebSocket => PlayWS, RequestHeader, Controller}
import service.RedisService
import play.api.libs.json._
import actors.SocketActor
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsNumber
import scala.concurrent.Future
import akka.event.slf4j.Logger
import play.api.libs.concurrent.Execution.Implicits._
import akka.util.Timeout
import play.api.libs.concurrent.Akka
import akka.actor.Props
import akka.util.Timeout
import scala.concurrent.duration._
import entities.{AuthToken, UserId}
import utils.Utils._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsNumber
import play.api.Play.current
import akka.actor.Props
import akka.pattern.ask
import akka.event.slf4j.Logger
import utils.Logging


/**
 * Provisions authorized users with web socket connections
 */
object WebSocket extends Controller with Logging {

  implicit val timeout = Timeout(2 second)
  val socketActor = Akka.system.actorOf(Props[SocketActor])

  /**
   * Provisions authorized users with web socket connections
   */
  def indexWS = PlayWS.async[JsValue] {
    implicit requestHeader =>
      for {
        uid <- Auth.authenticateRequest(requestHeader)
        enumerator <- (socketActor ? SocketActor.StartSocket(uid))
      } yield {
        val it = Iteratee.foreach[JsValue]{

          // post a message as the current user
          case JsObject(Seq( ("msg", JsString(msg)) )) =>
            socketActor ! SocketActor.MakePost(uid, msg)

          // request a page of the current user's feed
          case JsObject(Seq( ("feed", JsString("my_feed")), ("page", JsNumber(page)))) =>
            log.info(s"load feed for user $uid page $page for user $uid")
            for {
              feed <- RedisService.get_user_feed(uid, page.toInt)
            } socketActor ! SocketActor.SendMessages("my_feed", uid, feed)


          // request a page of the global feed
          case JsObject(Seq( ("feed", JsString("global_feed")), ("page", JsNumber(page)))) =>
            log.info(s"load global feed page $page for user $uid")
            for {
              feed <- RedisService.get_global_feed(page.toInt)
            } socketActor ! SocketActor.SendMessages("global_feed", uid, feed)

          // ping the connection, used to keep websocket connection alive on heroku
          case JsString("ping") =>

          case js => log.error(s"  ???: received invalid jsvalue $js")

        }.mapDone {
          _ => socketActor ! SocketActor.SocketClosed(uid)
        }

        (it, enumerator.asInstanceOf[Enumerator[JsValue]])
      }

  }
}
