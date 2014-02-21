
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
import akka.event.slf4j.Logger
import service._

import IdentityIdConverters._
import play.api.mvc.Security.AuthenticatedBuilder


object AppController extends Controller {
  lazy val log = Logger(s"application.${this.getClass.getName}")

  def get_user_id(req: RequestHeader) = req.session.get("login")

  object Authenticated extends AuthenticatedBuilder(
    userinfo= req => get_user_id(req),
    onUnauthorized = requestHeader => Ok(views.html.app.login())
  )


  implicit val timeout = Timeout(2 second)
  val socketActor = Akka.system.actorOf(Props[SocketActor])


  //issue: no error handling for auth'd session cookie for session that has been
  def index = Authenticated  {
    implicit request => {
      val user_id = IdentityId(request.user)


      val r = RedisServiceImpl.get_public_user(user_id, user_id).map{ p: PublicIdentity =>
        Ok(views.html.app.index(user_id.asString, p.asJson.toString))
      }

      Async(r)


    }
  }

  def logout() = Action { Redirect(routes.AppController.index).withSession() }

  /**
   * This function creates a WebSocket using the
   * enumerator linked to the current user,
   * retrieved from the TaskActor.
   */
  def indexWS = WebSocket.async[JsValue] {
    implicit requestHeader =>
      get_user_id(requestHeader).map(IdentityId(_)).map{ userId =>
         (socketActor ? StartSocket(userId)) map {
            enumerator =>
              val it = Iteratee.foreach[JsValue]{
                case JsObject(Seq((("msg", JsString(msg))))) =>
                  socketActor ! MakePost(userId, Msg(System.currentTimeMillis, userId, msg))

                case JsString("recent_posts") =>
                  socketActor ! RecentPosts(userId)

                case JsObject(Seq(("user_id", JsString(id)))) =>
                  socketActor ! RequestInfo(userId, id.asId)

                case JsObject(Seq(("alias", JsString(alias)))) =>
                  socketActor ! RequestAlias(userId, alias)

                case JsObject(Seq(("about_me", JsString(about_me)))) =>
                  socketActor ! SetAboutMe(userId, about_me)

                case JsObject(Seq(("post_id", JsString(post_id)))) =>
                  socketActor ! RequestPost(userId, post_id)

                case JsObject(Seq(("delete_message", JsString(post_id)))) =>
                  socketActor ! DeleteMessage(userId, post_id)

                case JsObject(Seq(("favorite_message", JsString(post_id)))) =>
                  socketActor ! FavoriteMessage(userId, post_id)

                case JsObject(Seq(("unfavorite_message", JsString(post_id)))) =>
                  socketActor ! UnFavoriteMessage(userId, post_id)

                case js =>
                  log.error(s"  ???: received jsvalue $js")

          }.mapDone {
                _ => socketActor ! SocketClosed(userId)
          }
              (it, enumerator.asInstanceOf[Enumerator[JsValue]])
          }
	}.getOrElse(errorFuture)
  }

  def javascriptRoutes = Action {
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
