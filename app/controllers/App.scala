package controllers

import actors._
import akka.event.slf4j.Logger
import entities._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.Routes
import scala.concurrent.Future
import scala.util.{Failure, Success}
import service._
import utils.Utils._

/**
 * Controller for the main page
 */
object App extends Controller  {

  /**
   * Serves up the main page to authorized users, provisioned with the current user's UserId and username
   */
  def index = Authenticated.async {
    implicit request => {
      val user_id = request.user

      for {
        username <- RedisService.get_user_name(user_id)
      } yield Ok(views.html.app.index(user_id.uid, username))
    }
  }

}

