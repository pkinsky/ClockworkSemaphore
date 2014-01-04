
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

  import RedisUserService.{redis, uidFromIdentityId}
  implicit val timeout = Timeout(1 second)




  val socketActor = Akka.system.actorOf(Props[SocketActor])



  lazy val log = Logger("application." + this.getClass.getName)


  def index = SecuredAction  {
    implicit request => {

        val user = SecureSocial.currentUser.get // fuck it

        val alias_key = s"user:${uidFromIdentityId(user.identityId)}:alias"

        val alias = for {
           alias_option <- redis.get[String](alias_key)
        } yield alias_option.getOrElse("fuck!")

      AsyncResult(alias.map(s => Ok(views.html.app.index(s))))


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

        val userId = RedisUserService.uidFromIdentityId(u.identityId)

        log.debug(s" >>>>> during websocket startup $u with uid => $userId")



         (socketActor ? StartSocket(userId)) map {
            enumerator =>

              val it = Iteratee.foreach[JsValue]{
                case JsObject(Seq(("topic", JsArray(topics)), ("msg", JsString(msg)))) =>
                    socketActor ! Msg(userId, topics.collect{case JsString(str) => str}.toSet, msg)

                case JsString("ACK") => socketActor ! AckSocket(userId)

                case js => println(s"  ???: received jsvalue $js")
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

  def testApp = SecuredAction { implicit request =>

    val user = SecureSocial.currentUser.get // fuck it

    val alias_key = s"user:${uidFromIdentityId(user.identityId)}:alias"

    val fAlias = for {
      alias_option <- redis.get[String](alias_key)
    } yield alias_option


    AsyncResult( fAlias.map(alias => Ok(views.js.app.testApp(user, alias)) ) )

  }








  def errorFuture = {
    val in = Iteratee.ignore[JsValue]
    val out = Enumerator(Json.toJson("not authorized")).andThen(Enumerator.eof)

    Future {
      (in, out)
    }
  }
  
  
}

