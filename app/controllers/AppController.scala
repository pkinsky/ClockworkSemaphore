
package controllers

import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json._
import play.api.libs.concurrent._
import play.api.libs.iteratee._
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.{Enumerator, Iteratee}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import actors._
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import actors.StartSocket
import actors.SocketClosed
import play.api.Routes
import scala.util.{ Success, Failure }
import play.api.libs.concurrent.Execution.Implicits._
import securesocial.core.SecureSocial
import akka.event.slf4j.Logger
import service._


object AppController extends Controller with SecureSocial {
  lazy val log = Logger("application." + this.getClass.getName)

  implicit val timeout = Timeout(2 second)
  val socketActor = Akka.system.actorOf(Props[SocketActor])


  def index = SecuredAction  {
    implicit request => {

      val user_id = SecureSocial.currentUser.get.identityId

      val user =  Await.result(RedisServiceImpl.get_public_user(user_id), 1 second) //ugh

      Ok(views.html.app.index(RedisServiceImpl.idToString(user_id), user.alias, user.avatar_url.getOrElse("")))
    }
  }


  /**
   * This function creates a WebSocket using the
   * enumerator linked to the current user,
   * retrieved from the TaskActor.
   */
  def indexWS = WebSocket.async[JsValue] {
    implicit requestHeader =>

      SecureSocial.currentUser.map{u =>

        val userId = u.identityId

         (socketActor ? StartSocket(userId)) map {
            enumerator =>

              val it = Iteratee.foreach[JsValue]{
                case JsObject(Seq((("msg", JsString(msg))))) =>
                    val current_time = System.currentTimeMillis
                    socketActor ! Msg(current_time, userId, msg)

                case JsString("ACK") => socketActor ! AckSocket(userId)


                case JsObject(Seq(("user_id", JsString(id)))) =>
                  socketActor ! RequestInfo(userId, RedisServiceImpl.stringToId(id))


                case JsObject(Seq(("alias", JsString(alias)))) =>
                    socketActor ! RequestAlias(userId, alias)

                case js => log.error(s"  ???: received jsvalue $js")
              }.mapDone {
                _ => socketActor ! SocketClosed(userId)
              }

              (it, enumerator.asInstanceOf[Enumerator[JsValue]])
          }
      }.getOrElse(errorFuture)
  }

  def javascriptRoutes = SecuredAction {
    implicit request =>
      Ok(
        Routes.javascriptRouter("jsRoutes")(
          routes.javascript.AppController.indexWS
        )
      ).as("text/javascript")
  }

  def errorFuture = {
    val in = Iteratee.ignore[JsValue]
    val out = Enumerator(Json.toJson("not authorized")).andThen(Enumerator.eof)

    Future {
      (in, out)
    }
  }
  
  
}
