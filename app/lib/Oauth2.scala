package lib
 
import play.api.libs.ws.WS
import play.core.parsers._
import scala.concurrent.Await
import scala.concurrent.duration._

case class OAuth2Settings(
  clientId: String,
  clientSecret: String,
  logInUrl: String,
  accessTokenUrl: String,
  userInfoUrl: String
)
 
abstract class OAuth2[T](settings: OAuth2Settings){
  def user(body: String): T
 
  import settings._
  lazy val logIn = logInUrl + "?client_id=" + clientId
 
  def requestAccessToken(code: String): Option[String] = {
    val req = WS.url(accessTokenUrl +
      "?client_id=" + clientId +
      "&client_secret=" + clientSecret +
      "&code=" + code)


    val resp =  Await.result(req.get, 5 seconds) //ugh



    FormUrlEncodedParser.parse(resp.body).get("access_token").flatMap(_.headOption)
  }
 
  def authenticate(code: String) = requestAccessToken(code).map(requestUserInfo)

  def requestUserInfo(accessToken: String): T = {
    val req = WS.url(userInfoUrl + "?access_token=" + accessToken)

    val resp =  Await.result(req.get, 5 seconds) //ugh

    user(resp.body)
  }
}













