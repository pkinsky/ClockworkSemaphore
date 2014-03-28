package controllers

import play.api.mvc._
import entities.{UserId, AuthToken}
import scala.concurrent.{ExecutionContext, Future}
import utils.Utils._
import scala.Some
import service.RedisService
import play.api.mvc.Security.AuthenticatedRequest
import akka.event.slf4j.Logger
import scala.Some
import play.api.mvc.SimpleResult
import play.api.mvc.Results.{Unauthorized, Ok}
import play.api.libs.concurrent.Execution.Implicits._
import utils.Logging

object Auth {

  def get_auth_token(req: RequestHeader) =
    req.session.get("login").map{t => AuthToken(t)}

  def authenticateRequest(req: RequestHeader): Future[UserId] = {
    for {
      token <- match_or_else(get_auth_token(req), "auth string not found"){ case Some(t) => t}
      uid <- RedisService.user_from_auth_token(token)
    } yield uid
  }

}

object AuthenticatedAPI extends FutureAuthenticatedBuilder (
  onUnauthorized = (requestHeader, err) => Unauthorized
)

object Authenticated extends FutureAuthenticatedBuilder(
  onUnauthorized = (requestHeader, err) => Ok(views.html.app.landing(err.map(_.toString)))
)

class FutureAuthenticatedBuilder(onUnauthorized: (RequestHeader, Option[Throwable]) => SimpleResult)
  extends ActionBuilder[({ type R[A] = AuthenticatedRequest[A, UserId] })#R] with Logging {

  def invokeBlock[A](request: Request[A], block: (AuthenticatedRequest[A, UserId]) => Future[SimpleResult]) =
    authenticate(request, block)

  /**
   * Authenticate the given block.
   */
  def authenticate[A](request: Request[A], block: (AuthenticatedRequest[A, UserId]) => Future[SimpleResult]) = {
    (for {
      user <- Auth.authenticateRequest(request)
      r <- block(new AuthenticatedRequest(user, request)).recover{ case ex =>
        log.error(s"error during authenticated request: $ex")
        onUnauthorized(request, Some(ex))
      }
    } yield r).recover{
      case ex =>
        //todo: distinguish between serious errors and missing or stale auth tokens
        log.info(s"error during authorization: $ex")
        onUnauthorized(request, None)
    }

  }

}
