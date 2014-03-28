package controllers

import entities.{AuthToken, UserId}
import scala.util.{Success, Failure}
import play.api.mvc.{Controller, RequestHeader}
import scala.concurrent.Future
import utils.Utils._
import scala.util.Success
import scala.util.Failure
import scala.Some
import service.RedisServiceLayerImpl
import play.api.libs.concurrent.Execution.Implicits._
import akka.event.slf4j.Logger
import utils.Logging

object API extends Controller with RedisServiceLayerImpl with Logging {

  def follow(to_follow: String) = AuthenticatedAPI.async  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- redisService.follow_user(user_id, UserId(to_follow))
      } yield Accepted

      r.onComplete{
        case Failure(t) => log.error(s"unfollow failed:\n$t")
        case Success(_) =>
      }

      r
    }
  }



  def unfollow(to_unfollow: String) = AuthenticatedAPI.async  {
    implicit request => {
      val user_id = request.user

      val r = for {
        _ <- redisService.unfollow_user(user_id, UserId(to_unfollow))
      } yield Accepted

      r.onComplete{
        case Failure(t) => log.error(s"unfollow failed:\n$t")
        case Success(_) =>
      }

      r

    }
  }


}

