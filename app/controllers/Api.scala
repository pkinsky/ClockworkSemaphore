package controllers

import akka.event.slf4j.Logger
import entities.{AuthToken, UserId}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{Controller, RequestHeader}
import scala.concurrent.Future
import scala.Some
import scala.util.Failure
import scala.util.Success
import scala.util.{Success, Failure}
import service.RedisService
import utils.Logging
import utils.Utils._


/**
 * Controller handling REST API interactions
 */
object API extends Controller with Logging {

  /**
   * as an authenticated user, follow the user with UID to_follow. Idempotent.
   */
  def follow(to_follow: String) = AuthenticatedAPI.async  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- RedisService.follow_user(user_id, UserId(to_follow))
      } yield Accepted

      r.onFailure{
        case t => log.error(s"unfollow failed:\n$t")
      }

      r
    }
  }


  /**
   * as an authenticated user, unfollow the user with UID to_unfollow. Idempotent.
   */
  def unfollow(to_unfollow: String) = AuthenticatedAPI.async  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- RedisService.unfollow_user(user_id, UserId(to_unfollow))
      } yield Accepted

      r.onFailure{
        case t => log.error(s"unfollow failed:\n$t")
      }

      r

    }
  }


}

