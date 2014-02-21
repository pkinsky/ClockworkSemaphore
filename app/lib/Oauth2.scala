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


}













