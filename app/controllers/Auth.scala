package controllers


import scala.concurrent.duration._
import lib._
import play.api.mvc._
import play.api.libs.json.{JsValue, Json, JsResult}
import service.{IdentityIdConverters, IdentityId, Identity, RedisServiceImpl}
import scala.concurrent.Await

import IdentityIdConverters._
import play.api.libs.ws.WS
import play.core.parsers.FormUrlEncodedParser
import play.api.libs.ws.WS.WSRequestHolder
import java.net.URLEncoder


trait Authenticator extends Controller {

  val extra_params: Map[String, String] = Map.empty

  val extra_login_params: Map[String, String] = Map.empty

  val oauth_settings: OAuth2Settings

  def parse_user(body: String): Identity


  //so lazy
  def login_params = Map("client_id" -> oauth_settings.clientId) ++ extra_login_params

  def login_url = oauth_settings.logInUrl

  def login() = Action { Redirect(login_url, login_params.mapValues(Seq(_))) }


  def callback() = Action { implicit request =>
    println("callback")
    params("code").flatMap { code =>

      println(s"callback with code $code")

      authenticate(code) map { user =>
        println(s"got access token & user_info")
        Await.result(RedisServiceImpl.save_user(user), 5 seconds) //ugh
        Redirect(routes.AppController.index).withSession("login" -> user.user_id.asString)
      }
    } getOrElse Redirect(login_url, login_params.mapValues(Seq(_)))
  }

  protected def params[Identity](key: String)(implicit request: Request[Identity]) = request.queryString.get(key).flatMap(_.headOption)


  def requestAccessToken(code: String): Option[String] = {
    //def because I don't feel like dealing with lazy vals right now
    def params_map = Map("client_id" -> oauth_settings.clientId,
      "client_secret" -> oauth_settings.clientSecret,
      "code" -> code) ++ extra_params



    val req =  params_map.toSeq.foldLeft(WS.url(oauth_settings.accessTokenUrl))((a, b) => a.withQueryString(b))

    val resp =  Await.result(req.post(""), 5 seconds) //ugh


    val r = FormUrlEncodedParser.parse(resp.body).get("access_token").flatMap(_.headOption)
    println(s"got access token $r from body ${resp.body} using $req")
    r
  }

  def authenticate(code: String) = requestAccessToken(code).map(requestUserInfo)

  def requestUserInfo(accessToken: String): Identity = {
    println("getting user info")
    val req = WS.url(oauth_settings.userInfoUrl + "?access_token=" + accessToken)

    val resp =  Await.result(req.get, 5 seconds) //ugh

    val r = parse_user(resp.body)

    println(s"got user_info: ${resp.body} => $r")

    r
  }
}


object Google extends Authenticator {
  override val extra_params =
    Map("redirect_uri" -> "http://localhost:9000/google/callback", "grant_type" -> "authorization_code")


  override val extra_login_params =
    Map("redirect_uri" -> "http://localhost:9000/google/callback", "scope" -> "profile", "response_type" -> "code")

  val oauth_settings = OAuth2Settings(
    "459927666173-qn7fmhamv8kpn9jbdchmebg0sosemvvo.apps.googleusercontent.com",
    "hCNIEXk1-pJ3DCwmRPKLwUBG",
    "https://accounts.google.com/o/oauth2/auth",
    "https://accounts.google.com/o/oauth2/token",
    "https://api.github.com/user"
  )


  def parse_user(body: String) = {
    val json: JsValue = Json.parse(body)
    Identity(
      IdentityId((json \ "login").as[String]),
      (json \ "email").as[String],
      (json \ "avatar_url").as[String]
    )
  }

}










object Github extends Authenticator {

  val oauth_settings =
    OAuth2Settings(
      "cc1041161512ab0f5d5b",
      "2e428edb1ed5abd3b0e7c08d4e8ac4e639cd6b5f",
      "https://github.com/login/oauth/authorize",
      "https://github.com/login/oauth/access_token",
      "https://api.github.com/user"
    )


  def parse_user(body: String) = {
		  val json: JsValue = Json.parse(body)
		  val r = Identity(
        IdentityId((json \ "login").as[String]),
        (json \ "email").as[String],
        (json \ "avatar_url").as[String]
		  )
      r
	}

}
