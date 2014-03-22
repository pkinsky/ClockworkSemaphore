
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
import Utils._


object AppController extends Controller {
  lazy val log = Logger(s"application.${this.getClass.getName}")

  val redis_service = RedisServiceImpl.asInstanceOf[RedisService]

  def get_auth_token(req: RequestHeader) =
		req.session.get("login").map{t => AuthToken(t)}

  def authenticate(req: RequestHeader): Future[UserId] = {
    for {
      token <- get_auth_token(req).map(Future(_)).getOrElse{Future.failed(Stop("auth string not found"))}
      uid <- redis_service.user_from_auth_token(token)
    } yield uid
  }

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



  //todo: make this pass: it's a good test case, and if the system should handle it or return meaningful errors
  // also need to add user info for robo-sherlock, failing to load a user is a semi-legit reason to fail to load a post

  //this really breaks everything, somehow is causing failed to load post errors! somehow it makes redis close the socket(!)
  //actually it's worse: even when commented out, having sent messages from a nonexistent user breaks post loading,
  // because it's assumed that all posts resolve to Some(post)
  /*
  val holmesIterator = io.Source.fromFile("etc/sherlock.txt").getLines().filterNot(_.isEmpty)
  Akka.system.scheduler.schedule(10 seconds, 10 seconds){
    if(holmesIterator.hasNext) {
      val uid = UserId("robo-sherlock")
      socketActor ! MakePost(uid, Msg(System.currentTimeMillis, uid, holmesIterator.next()))
    }
  }*/


  val alphanumeric: Set[Char] = (('0' to '1') ++ ('a' to 'z') ++ ('A' to 'Z')).toSet

  //todo: move sizes to config file or something
  def valid_username(username: String): Boolean =
      username.length >= 5 &&
      username.length <= 15 &&
      username.forall( c => alphanumeric.contains(c) )

  def valid_password(password: String): Boolean =
    password.length >= 5 &&
    password.length <= 15

  def login2 = Action.async{
    implicit request =>

      //this is all getting rewritten later
      val forminfo = request.body.asFormUrlEncoded.get

      val username = forminfo("username").head
      val password = forminfo("password").head
      //log.info(s"login: $username / $password")

      val r: Future[SimpleResult] = for {
        _ <- predicate(valid_password(password), "invalid password, should have been caught by client-side validation")
        _ <- predicate(valid_username(username), "invalid username, should have been caught by client-side validation")
        uid <- redis_service.login_user(username, password)
        auth <- redis_service.gen_auth_token(uid)
      } yield {
        //log.info(s"login $uid with $auth")
        Redirect(routes.AppController.index).withSession( "login" -> auth.token)
      }

      r.recover{
          case t =>
            log.error(s"error during login: $t");
            Redirect(routes.AppController.index)
        }

  }


  def login = Action {
    Ok( views.html.app.login() )
  }

  //todo: handle registering a reserved username gracefully
  def register = Action.async{
    implicit request =>
      //this is all getting rewritten later
      val forminfo = request.body.asFormUrlEncoded.get

      val username = forminfo("username").head
      val password = forminfo("password").head

      val r: Future[SimpleResult] = for {
        _ <- predicate(valid_password(password), "invalid password, should have been caught by client-side validation")
        _ <- predicate(valid_username(username), "invalid username, should have been caught by client-side validation")
        uid <- redis_service.register_user(username, password)
        auth <- redis_service.gen_auth_token(uid)
      } yield {
        Redirect(routes.AppController.index).withSession("login" -> auth.token)
      }

      r.recover{ case t => log.error(s"error during registration: $t"); Redirect(routes.AppController.index)}
  }

  def follow(to_follow: String) = AuthenticatedAPI.async  {
    implicit request => {
      val user_id = request.user

      for {
        _ <- redis_service.follow_user(user_id, UserId(to_follow))
      } yield Accepted
    }
  }

  def unfollow(to_unfollow: String) = AuthenticatedAPI.async  {
    implicit request => {
      val user_id = request.user

      for {
        _ <- redis_service.unfollow_user(user_id, UserId(to_unfollow))
      } yield Accepted
    }
  }


  def index = Authenticated.async {
    implicit request => {
      val user_id = request.user

      for {
        username <- redis_service.get_user_name(user_id)
      } yield Ok(views.html.app.index(user_id.uid, username))
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

      for {
        uid <- authenticate(requestHeader)
        enumerator <- (socketActor ? StartSocket(uid))
      } yield {
        val it = Iteratee.foreach[JsValue]{
          case JsObject(Seq( ("msg", JsString(msg)) )) =>
            socketActor ! MakePost(uid, msg)

          case JsObject(Seq( ("feed", JsString("my_feed")), ("page", JsNumber(page)))) =>
            log.info(s"load feed for user $uid page $page for user $uid")
            for {
              feed <- redis_service.get_user_feed(uid, page.toInt)
            } socketActor ! SendMessages("my_feed", uid, feed)


          case JsObject(Seq( ("feed", JsString("global_feed")), ("page", JsNumber(page)))) =>
            log.info(s"load global feed page $page for user $uid")
            for {
              feed <- redis_service.get_global_feed(page.toInt)
            } socketActor ! SendMessages("global_feed", uid, feed)


          case js => log.error(s"  ???: received invalid jsvalue $js")

        }.mapDone {
          _ => socketActor ! SocketClosed(uid)
        }

        (it, enumerator.asInstanceOf[Enumerator[JsValue]])
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

  lazy val log = Logger(s"application.${this.getClass.getName}")
 
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
      case ex =>
        log.error(s"error during authorization: $ex")
        onUnauthorized(request)
    }

  }
}
















