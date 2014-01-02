
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


  def index = withAuth {
    implicit request => userId => {
	//val uid: Long = userId ???
        Ok(views.html.app.index(0L))
    }
  }


  
  val socketActor = Akka.system.actorOf(Props[SocketActor])

  /**
   * This function creates a WebSocket using the
   * enumerator linked to the current user,
   * retrieved from the TaskActor.
   */
  def indexWS = withAuthWS {
    userId =>
	
      implicit val timeout = Timeout(3 seconds)


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
  implicit val timeout = Timeout(1 second)
  import RedisActor._

  var _op_id = 0L
  def next_op_id = {
    val prev = _op_id
    _op_id = _op_id + 1
    prev  
  }
  
  val redisActor = Akka.system.actorOf(Props[RedisActor])  
  
  

  def username(request: RequestHeader) = {
    //verify or create session, this should be a real login
    request.session.get(Security.username)
  }

  /**
   * When user not have a session, this function create a
   * random userId and reload index page
   */
  def unauthF(request: RequestHeader) = {
    val result = for {
      AckRegisterUser(_, user_id) <- (redisActor ? RegisterUser(next_op_id)).mapTo[AckRegisterUser]
    } yield {
      println(s"ack register user $user_id")
      Redirect(routes.AppController.index).withSession(Security.username -> user_id.toString)
    }
    
    Await.result(result, 1 second)
  }

  /**
   * try to retieve the username, call f() if it is present,
   * or unauthF() otherwise
   */
  def withAuth(f: => Long => Request[_ >: AnyContent] => Result): EssentialAction = {
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
  def withAuthWS(f: => Long => Future[(Iteratee[JsValue, Unit], Enumerator[JsValue])]): WebSocket[JsValue] = {

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

