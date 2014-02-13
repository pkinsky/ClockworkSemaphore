package controllers


import scala.concurrent.duration._
import lib._
import play.api.mvc._
import play.api.libs.json.{JsValue, Json, JsResult}
import play.api.libs.functional.syntax._
import service.{IdentityId, Identity, RedisServiceImpl}
import scala.concurrent.Await

object Auth extends Controller {
 
  val GITHUB = new OAuth2[GithubUser](OAuth2Settings(
    "cc1041161512ab0f5d5b",
    "2e428edb1ed5abd3b0e7c08d4e8ac4e639cd6b5f",
    "https://github.com/login/oauth/authorize",
    "https://github.com/login/oauth/access_token",
    "https://api.github.com/user"
  )){
    def user(body: String) = {
		  val json: JsValue = Json.parse(body)
		  val r = GithubUser(
        (json \ "login").as[String],
        (json \ "email").as[String],
        (json \ "avatar_url").as[String],
        (json \ "name").as[String]
		  )
      println(s"woot got github user $r from string $body")

      r
	}
	}
 
	case class GithubUser(
		login: String,
		email: String,
		avatar_url: String,
		name: String
	)

  def login() = Action { Redirect(GITHUB.logIn) }
 
  def logout() = Action { Redirect(routes.AppController.index).withSession() }
 
  def callback() = Action { implicit request =>
    params("code").flatMap { code =>

      GITHUB.authenticate(code) map { user =>
        Await.result(RedisServiceImpl.save_user( Identity(IdentityId(user.login), user.name, user.avatar_url)), 5 seconds) //ugh
        Redirect(routes.AppController.index).withSession("login" -> user.login)
      }
    } getOrElse Redirect(GITHUB.logIn)
  }
 
  protected def params[T](key: String)(implicit request: Request[T]) = request.queryString.get(key).flatMap(_.headOption)
}
