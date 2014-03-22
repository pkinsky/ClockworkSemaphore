package controllers

import play.api.mvc.{Request, ActionBuilder, SimpleResult, RequestHeader}
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.Security.AuthenticatedRequest
import akka.event.slf4j.Logger

import play.api.mvc.Results.InternalServerError
/**
 * Created by paul on 3/22/14.
 */
class FutureAuthenticatedBuilder[U](userinfo: RequestHeader => Future[U],
                                    onUnauthorized: RequestHeader => SimpleResult)(implicit executor: ExecutionContext)
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
      r <- block(new AuthenticatedRequest(user, request)).recover{ case ex =>
        log.error(s"error during authenticated request: $ex")
        InternalServerError
      }
    } yield r).recover{
      case ex =>
        log.error(s"error during authorization: $ex")
        onUnauthorized(request)
    }

  }
}