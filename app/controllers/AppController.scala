
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

  val redis_service = RedisServiceImpl.asInstanceOf[RedisService]

  def get_auth_token(req: RequestHeader) = {
		req.session.get("login").map{t => AuthToken(t)}
	}

  def authenticate(req: RequestHeader): Future[UserId] = {
    for {
      token <- get_auth_token(req).map(Future(_)).getOrElse{Future.failed(Stop("auth string not found"))}
      uid <- redis_service.user_from_auth_token(token)
    } yield {
      //log.info(s"yield uid $uid from authenticate for auth token $token")
      uid
    }
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


  //this really breaks everything, somehow is causing failed to load post errors! somehow it makes redis close the socket(!)
  /*
  val holmesIterator = io.Source.fromFile("etc/sherlock.txt").getLines().filterNot(_.isEmpty)
  Akka.system.scheduler.schedule(10 seconds, 10 seconds){
    if(holmesIterator.hasNext) {
      val uid = UserId("robo-sherlock")
      socketActor ! MakePost(uid, Msg(System.currentTimeMillis, uid, holmesIterator.next()))
    }
  }*/




  def login2 = Action {
    implicit request =>

      //this is all getting rewritten later
      val forminfo = request.body.asFormUrlEncoded.get

      val username = forminfo("username").head
      val password = forminfo("password").head
      //log.info(s"login: $username / $password")

      val r = for {
        uid <- redis_service.login_user(username, password)
        auth <- redis_service.gen_auth_token(uid)
      } yield {
        //log.info(s"login $uid with $auth")
        Redirect(routes.AppController.index).withSession( "login" -> auth.token)
      }

      Async(r.recover{
          case t =>
            log.error(s"during login: $t");
            Redirect(routes.AppController.index)
        }
      )
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

	    log.info(s"user $username registering with password ${password.map(_ => '*')}")

      val r = for {
        uid <- redis_service.register_user(username, password)
        auth <- redis_service.gen_auth_token(uid)
      } yield {
        log.info(s"register: $username / $password results in user $uid with auth string $auth")
        Redirect(routes.AppController.index).withSession("login" -> auth.token)
      }

      Async(r.recover{ case t => log.error(s"during registration: $logout"); Redirect(routes.AppController.index)}) //todo: this still occurs when registering a reserved username
  }

  def follow(to_follow: String) = AuthenticatedAPI  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- redis_service.follow_user(user_id, UserId(to_follow))
      } yield Accepted

      Async(r)
    }
  }

  def unfollow(to_unfollow: String) = AuthenticatedAPI  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- redis_service.unfollow_user(user_id, UserId(to_unfollow))
      } yield Accepted

      Async(r)
    }
  }


  def index = Authenticated  {
    implicit request => {
      val user_id = request.user

      val r = for {
        username <- redis_service.get_user_name(user_id)
      } yield Ok(views.html.app.index(user_id.uid, username))

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
            socketActor ! MakePost(uid, Msg(System.currentTimeMillis, uid, msg))

          case JsObject(Seq( ("feed", JsString("my_feed")), ("page", JsNumber(page)))) =>
            log.info(s"load feed for user $uid page $page")
            for {
              feed <- redis_service.get_user_feed(uid, page.toInt)
            } socketActor ! SendMessages("my_feed", uid, feed)


          case JsObject(Seq( ("feed", JsString("global_feed")), ("page", JsNumber(page)))) =>
            log.info(s"load global feed page $page")
            for {
              feed <- redis_service.get_global_feed(page.toInt)
            } socketActor ! SendMessages("global_feed", uid, feed)


          case js => log.error(s"  ???: received jsvalue $js")

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
		log.error(s"unauthorized user, ex: $ex") 
		onUnauthorized(request)
    }

  }
}
















