package controllers

import scala.concurrent.Future
import utils.Utils._
import play.api.mvc._
import utils.Utils.UserVisibleError
import scala.Some
import utils.Utils.UserVisibleError
import scala.Some
import play.api.mvc.SimpleResult
import service.RedisService
import akka.event.slf4j.Logger
import play.api.libs.concurrent.Execution.Implicits._
import akka.event.slf4j.Logger


/**
 * Landing page. Unauthenticated users are redirected here.
 * Handles login, registration, logout flow
 */
object LandingPage extends Controller {

  val alphanumeric: Set[Char] = (('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z')).toSet

  val username_length = (5 to 15)
  val password_length = (5 to 15)

  //todo: move sizes to config file or something
  def valid_username(username: String): Boolean =
    username_length.contains(username.length) &&
    username.forall( c => alphanumeric.contains(c) )

  def valid_password(password: String): Boolean =
    password_length.contains(username.length)


  /**
   * check that credentials are of valid form before going to the database.
   * @param username username string
   * @param password password string
   * @return
   */
  private def validate_credentials(username: String, password: String): Future[Unit] =
    for{
      _ <- predicate(valid_password(password), UserVisibleError(s"invalid password, should have been caught by client-side validation"))
      _ <- predicate(valid_username(username), UserVisibleError(s"invalid username $username, should have been caught by client-side validation"))
    } yield ()



  def login = Action.async{
    implicit request =>

      val r: Future[SimpleResult] = for {
        (username, password) <- match_or_else(parse_form(request), "username and/or password not found"){case Some(t) => t }
        _ <- validate_credentials(username, password)
        uid <- RedisService.login_user(username, password)
        auth <- RedisService.gen_auth_token(uid)
      } yield Redirect(routes.App.index).withSession( "login" -> auth.token)

      r.recover{
        case UserVisibleError(reason) =>
          Ok(views.html.app.landing(Some(reason)))
        case t =>
          Ok(views.html.app.landing(Some("An error occured and has been logged")))
      }

  }

  def landing = Action {
    Ok( views.html.app.landing(None) )
  }


  /**
   * parses a request with a form url encoded body
   * @param request the request
   * @return Some (username, password) tuple retrieved from request or None
   */
  def parse_form(request: Request[AnyContent]): Option[(String, String)] =
    for {
      formInfo <- request.body.asFormUrlEncoded
      usernames <- formInfo.get("username")
      username <- usernames.headOption
      passwords <- formInfo.get("password")
      password <- passwords.headOption
    } yield (username, password)



  def register = Action.async{
    implicit request =>

      val r: Future[SimpleResult] = for {
        (username, password) <- match_or_else(parse_form(request), "username and/or password not found"){case Some(t) => t }
        _ <- validate_credentials(username, password)
        uid <- RedisService.register_user(username, password)
        auth <- RedisService.gen_auth_token(uid)
      } yield {
        Redirect(routes.App.index).withSession("login" -> auth.token)
      }

      r.recover{
        case UserVisibleError(reason) =>
          Ok(views.html.app.landing(Some(reason)))
        case t =>
          Ok(views.html.app.landing(Some("An error occured and has been logged")))
      }
  }

  //logout, removing session cookie
  def logout() = Action { Redirect(routes.App.index).withSession() }
}
