
package controllers

import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json._
import play.api.libs.concurrent._
import play.api.libs.iteratee._
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.{Enumerator, Iteratee}

import scala.concurrent.Future
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

  implicit val timeout = Timeout(2 second)


  val socketActor = Akka.system.actorOf(Props[SocketActor])



  lazy val log = Logger("application." + this.getClass.getName)


  def index = SecuredAction  {
    implicit request => {

      val user = SecureSocial.currentUser.get //fuckit

      val user_id = RedisService.idToString(user.identityId)

      Ok(views.html.app.index(user_id))
    }
  }



  

  /**
   * This function creates a WebSocket using the
   * enumerator linked to the current user,
   * retrieved from the TaskActor.
   */
  def indexWS = WebSocket.async[JsValue]{

    (request: RequestHeader) =>

      implicit val rHeader = request

      SecureSocial.currentUser.map{u =>

        val userId = u.identityId


         (socketActor ? StartSocket(userId)) map {
            enumerator =>

              val it = Iteratee.foreach[JsValue]{
                case JsObject(Seq((("msg", JsString(msg))))) =>
                    socketActor ! Msg(userId, msg)

                case JsString("ACK") => socketActor ! AckSocket(userId)

                //why send ws_user_id?
                case JsObject(Seq(("user_id", JsString(ws_user_id)), ("alias", JsString(alias)))) =>
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

/*
  def testApp = SecuredAction {
    implicit request =>
      Ok(views.js.app.testApp())
  }

  
  */
  


  def errorFuture = {
    val in = Iteratee.ignore[JsValue]
    val out = Enumerator(Json.toJson("not authorized")).andThen(Enumerator.eof)

    Future {
      (in, out)
    }
  }
  
  
}

