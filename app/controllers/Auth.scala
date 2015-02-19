package controllers

import akka.event.slf4j.Logger
import entities.{UserId, AuthToken}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._
import play.api.mvc.Results.{Unauthorized, Ok}
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc.SimpleResult
import scala.concurrent.{ExecutionContext, Future}
import scala.Some
import scala.Some
import service.RedisService
import utils.Logging
import utils.Utils._


/**
 * handles mapping RequestHeader => Future[UserId]
 */
object Auth {

  /**
   * @param req request header
   * @return Some auth token extracted from `req` or None
   */
  def get_auth_token(req: RequestHeader): Option[AuthToken] =
    req.session.get("login").map{t => AuthToken(t)}

  /**
   * Extract an auth token from a request header and fetch the corresponding UserId from Redis
   * @param req request header
   * @return Future of UserId extracted from request header
   */
  def authenticateRequest(req: RequestHeader): Future[UserId] = {
    for {
      token <- match_or_else(get_auth_token(req), "auth string not found"){ case Some(t) => t}
      uid <- RedisService.user_from_auth_token(token)
    } yield uid
  }

}

// Authenticate or return 401 unauthorized. For use with API calls where redirecting is not desired
object AuthenticatedAPI extends FutureAuthenticatedBuilder (
  onUnauthorized = (requestHeader, err) => Unauthorized
)

// Authenticate or redirect to the landing page
object Authenticated extends FutureAuthenticatedBuilder(
  onUnauthorized = (requestHeader, err) => Ok(views.html.app.landing(err.map(_.toString)))
)

// Utility class for building authenticated requests. Based on AuthenticatedBuilder.
class FutureAuthenticatedBuilder(onUnauthorized: (RequestHeader, Option[Throwable]) => SimpleResult)
  extends ActionBuilder[({ type R[A] = AuthenticatedRequest[A, UserId] })#R] with Logging {

  def invokeBlock[A](request: Request[A], block: (AuthenticatedRequest[A, UserId]) => Future[SimpleResult]) = {
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
