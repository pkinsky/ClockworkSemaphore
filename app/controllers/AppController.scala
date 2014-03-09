
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

import play.api.mvc.Security.{AuthenticatedRequest, AuthenticatedBuilder}


object AppController extends Controller {
  lazy val log = Logger(s"application.${this.getClass.getName}")

  val redis_service = RedisServiceImpl

  def get_auth_string(req: RequestHeader) = req.session.get("login")

  //todo: custom Authenticator that can handle futures better :(
  object Authenticated extends FutureAuthenticatedBuilder(
    userinfo= req => get_auth_string(req).map(redis_service.user_from_auth_string(_)).getOrElse(Future.failed(Stop("no auth string"))),
    onUnauthorized = requestHeader => Ok(views.html.app.login())
  )


  implicit val timeout = Timeout(2 second)
  val socketActor = Akka.system.actorOf(Props[SocketActor])


  def login2 = Action {
    implicit request =>

      //this is all getting rewritten later
      val forminfo = request.body.asFormUrlEncoded.get

      val username = forminfo("username").head
      val password = forminfo("password").head
      log.info(s"login: $username / $password")

      val r = for {
        uid <- redis_service.login_user(username, password)
        auth <- redis_service.gen_auth_string_for_user(uid)
      } yield {
        log.info(s"login $uid with $auth")
        Redirect(routes.AppController.index).withSession( "login" -> auth)
      }

      Async(r)
  }


  def login = Action {

    Ok( views.html.app.login() )

  }

  def register = Action {
    implicit request =>

      //this is all getting rewritten later
      val forminfo = request.body.asFormUrlEncoded.get

      val username = forminfo("username").head
      val password = forminfo("password").head

      val r = for {
        uid <- redis_service.register_user(username, password)
        auth <- redis_service.gen_auth_string_for_user(uid)
      } yield {
        log.info(s"register: $username / $password results in user $uid with auth string $auth")
        Redirect(routes.AppController.index).withSession("login" -> auth)
      }


      Async(r)

      //todo: copy logic from twitter redis example

  }


  def index = Authenticated  {
    implicit request => {
      val user_id = request.user


      val r = for {
        public_user <- RedisServiceImpl.get_public_user(user_id, user_id)
      } yield Ok(views.html.app.index(user_id, public_user.asJson.toString))



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
      val u = get_auth_string(requestHeader) match {
        case Some(auth) => redis_service.user_from_auth_string(auth)
        case None => Future.failed(Stop("no user for auth at websocket"))
      }

      u.flatMap{ userId =>
         (socketActor ? StartSocket(userId)) map {
            enumerator =>
              val it = Iteratee.foreach[JsValue]{
                case JsObject(Seq((("msg", JsString(msg))))) =>
                  socketActor ! MakePost(userId, Msg(System.currentTimeMillis, userId, msg))

                case JsString("recent_posts") =>
                  socketActor ! RecentPosts(userId)

                case JsObject(Seq(("user_id", JsString(id)))) =>
                  socketActor ! RequestInfo(userId, id)

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

                case JsObject(Seq(("follow_user", JsString(to_follow)))) =>
                  socketActor ! FollowUser(userId, to_follow)

                case JsObject(Seq(("unfollow_user", JsString(to_unfollow)))) =>
                  socketActor ! UnFollowUser(userId, to_unfollow)

                case js =>
                  log.error(s"  ???: received jsvalue $js")

          }.mapDone {
                _ => socketActor ! SocketClosed(userId)
          }
              (it, enumerator.asInstanceOf[Enumerator[JsValue]])
          }
	  }
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







class FutureAuthenticatedBuilder[U](userinfo: RequestHeader => Future[U],
                              onUnauthorized: RequestHeader => SimpleResult)
  extends ActionBuilder[({ type R[A] = AuthenticatedRequest[A, U] })#R] {

  def invokeBlock[A](request: Request[A], block: (AuthenticatedRequest[A, U]) => Future[SimpleResult]) =
    authenticate(request, block)

  /**
   * Authenticate the given block.
   */
  def authenticate[A](request: Request[A], block: (AuthenticatedRequest[A, U]) => Future[SimpleResult]) = {
    (for {
      user <- userinfo(request)
      r <- block(new AuthenticatedRequest(user, request))
    } yield r).recover{
      case _ => onUnauthorized(request)
    }

  }
}
















