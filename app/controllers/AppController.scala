
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


//todo:
/*
  def predicate(if: Boolean)(fail: Exception) returns future with exception if predicate not satisfied

  for flow control without shitty syntax

   use
   for {
    _ <- predicate 1 == 2
   }

 */




object AppController extends Controller {
  lazy val log = Logger(s"application.${this.getClass.getName}")

  val redis_service = RedisServiceImpl.asInstanceOf[RedisService]

  def get_auth_token(req: RequestHeader) = req.session.get("login").map(AuthToken(_))

  def authenticate(req: RequestHeader) = get_auth_token(req).map(redis_service.user_from_auth_token(_)).getOrElse(Future.failed(Stop("no auth string")))

  object Authenticated extends FutureAuthenticatedBuilder(
    userinfo= authenticate,
    onUnauthorized = requestHeader => Ok(views.html.app.login())
  )

  object AuthenticatedAPI extends FutureAuthenticatedBuilder(
    userinfo= authenticate,
    onUnauthorized = requestHeader => Unauthorized
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
        auth <- redis_service.gen_auth_token(uid)
      } yield {
        log.info(s"login $uid with $auth")
        Redirect(routes.AppController.index).withSession( "login" -> auth.token)
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
        auth <- redis_service.gen_auth_token(uid)
      } yield {
        log.info(s"register: $username / $password results in user $uid with auth string $auth")
        Redirect(routes.AppController.index).withSession("login" -> auth.token)
      }

      Async(r)
  }

  def follow(to_follow: String) = AuthenticatedAPI  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- redis_service.follow_user(user_id, UserId(to_follow))
      } yield Ok("pass ???")

      Async(r)
    }
  }

  def unfollow(to_unfollow: String) = AuthenticatedAPI  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- redis_service.unfollow_user(user_id, UserId(to_unfollow))
      } yield Ok("pass ???")

      Async(r)
    }
  }


  def index = Authenticated  {
    implicit request => {
      val user_id = request.user

      Ok(views.html.app.index(user_id.uid))
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
      val u = get_auth_token(requestHeader) match {
        case Some(auth) => redis_service.user_from_auth_token(auth)
        case None => Future.failed(Stop("no user for auth at websocket"))
      }

      u.flatMap{ userId =>
         (socketActor ? StartSocket(userId)) map {
            enumerator =>
              val it = Iteratee.foreach[JsValue]{
                case JsObject(Seq((("msg", JsString(msg))))) =>
                  socketActor ! MakePost(userId, Msg(System.currentTimeMillis, userId, msg))

                case js => log.error(s"  ???: received jsvalue $js")

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
















