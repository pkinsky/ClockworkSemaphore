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
import scala.util.Random
import play.api.Routes
import scredis._
import scala.util.{ Success, Failure }
import play.api.libs.concurrent.Execution.Implicits._


object AppController extends Controller with Secured{

  val redis = Redis()

  def index = withAuth {
    implicit request => userId => {
        Ok(views.html.app.index())
    }
  }

  val timerActor = Akka.system.actorOf(Props[SocketActor])

  /**
   * This function creates a WebSocket using the
   * enumerator linked to the current user,
   * retrieved from the TaskActor.
   */
  def indexWS = withAuthWS {
    userId =>

      implicit val timeout = Timeout(3 seconds)

      // using the ask pattern of akka, 
      // get the enumerator for that user
      (timerActor ? StartSocket(userId)) map {
        enumerator =>

          // create a Iteratee which ignore the input and
          // and send a SocketClosed message to the actor when
          // connection is closed from the client

          val c = Iteratee.foreach[JsValue]{
            case JsObject(Seq(("topic", JsArray(topics)), ("msg", JsString(msg)))) =>
                timerActor ! Msg(userId, topics.collect{case JsString(str) => str}.toSet, msg)

            case js =>
                println(s"  ???: received jsvalue $js")

          }

          val it = c mapDone {
            _ =>
              timerActor ! SocketClosed(userId)
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

}

trait Secured {

  val redis: Redis


  def username(request: RequestHeader) = {
    //verify or create session, this should be a real login
    request.session.get(Security.username)
  }

  /**
   * When user not have a session, this function create a
   * random userId and reload index page
   */
  def unauthF(request: RequestHeader) = {

    //most of the schema is here as strings... not ok
    val result: Future[SimpleResult] = for{
      uid <- redis.incr("global:nextUserId")
      _ <- redis.pipelined { p =>
        val name = s"nameof-$uid"
        p.set(s"uid:$uid:username", s"name")
        p.set(s"uid:$uid:password", "foobar")

        p.set(s"username:$name:uid", uid)

        val auth = s"auth-$uid"
        p.set(s"uid:$uid:auth", auth)
        p.set(s"auth:$auth", uid)

        println(s"uid => $uid => $auth")
      }
    } yield Redirect(routes.AppController.index).withSession(Security.username -> uid.toString)

      Await.result(result, 1 second)
  }

  /**
   * Basi authentication system
   * try to retieve the username, call f() if it is present,
   * or unauthF() otherwise
   */
  def withAuth(f: => Int => Request[_ >: AnyContent] => Result): EssentialAction = {
    Security.Authenticated(username, unauthF) {
      username =>
        Action(request => f(username.toInt)(request))
    }
  }

  /**
   * This function provide a basic authentication for
   * WebSocket, likely withAuth function try to retrieve the
   * the username form the session, and call f() function if find it,
   * or create an error Future[(Iteratee[JsValue, Unit], Enumerator[JsValue])])
   * if username is none
   */
  def withAuthWS(f: => Int => Future[(Iteratee[JsValue, Unit], Enumerator[JsValue])]): WebSocket[JsValue] = {

    // this function create an error Future[(Iteratee[JsValue, Unit], Enumerator[JsValue])])
    // the iteratee ignore the input and do nothing,
    // and the enumerator just send a 'not authorized message'
    // and close the socket, sending Enumerator.eof
    def errorFuture = {
      // Just consume and ignore the input
      val in = Iteratee.ignore[JsValue]

      // Send a single 'Hello!' message and close
      val out = Enumerator(Json.toJson("not authorized")).andThen(Enumerator.eof)

      Future {
        (in, out)
      }
    }

    WebSocket.async[JsValue] {
      request =>
        username(request) match {
          case None =>
            errorFuture

          case Some(id) =>
            f(id.toInt)
            
        }
    }
  }
}

