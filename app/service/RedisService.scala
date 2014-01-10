package service

import java.net.URI
import scredis.Redis
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import actors.{Msg, ApplicativeStuff}
import ApplicativeStuff._
import scala.concurrent.Future
import scalaz.syntax.applicative.ToApplyOps
import play.api.libs.json._
import scredis.parsing.Parser
import securesocial.core._
import scala.Some
import actors.Msg
import scala.Some
import actors.Msg
import securesocial.core.providers.Token
import securesocial.core.providers.Token
import scala.Some
import actors.Msg
import play.api.libs.json.JsString
import securesocial.core.providers.Token
import scala.Some
import play.api.libs.json.JsObject
import actors.Msg
import securesocial.core.OAuth1Info
import securesocial.core.IdentityId
import play.api.libs.json.JsString
import securesocial.core.providers.Token
import scala.Some
import securesocial.core.OAuth2Info
import securesocial.core.PasswordInfo
import play.api.libs.json.JsObject
import actors.Msg
import play.api.Logger

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import play.api.libs.concurrent.Execution.Implicits._

object RedisService {
  lazy val log = Logger("application." + this.getClass.getName)



  implicit val format1 = Json.format[IdentityId]
  implicit val format2 = Json.format[AuthenticationMethod]
  implicit val format3 = Json.format[OAuth1Info]
  implicit val format4 = Json.format[OAuth2Info]
  implicit val format5 = Json.format[PasswordInfo]
  implicit val format6 = Json.format[Token]

  implicit val format8 = new Format[Identity]{
    def writes(user: Identity): JsValue = {
      JsObject(Seq(
        ("identityId", Json.toJson(user.identityId)),
        ("firstName", JsString(user.firstName)),
        ("lastName", JsString(user.lastName)),
        ("fullName", JsString(user.fullName)),
        ("email", Json.toJson(user.email)),
        ("avatarUrl", Json.toJson(user.avatarUrl)),
        ("authMethod", Json.toJson(user.authMethod)),
        ("oAuth1Info", Json.toJson(user.oAuth1Info)),
        ("oAuth2Info", Json.toJson(user.oAuth2Info)),
        ("passwordInfo", Json.toJson(user.passwordInfo))
      ))
    }

    def reads(json: JsValue): JsResult[Identity] = {


      for{
        identityId <- Json.fromJson[IdentityId](json \ "identityId")
        email <- Json.fromJson[Option[String]](json \ "email")
        avatarUrl <- Json.fromJson[Option[String]](json \ "avatarUrl")
        authMethod <- Json.fromJson[AuthenticationMethod](json \ "authMethod")
        oAuth1Info <- Json.fromJson[Option[OAuth1Info]](json \ "oAuth1Info")
        oAuth2Info <- Json.fromJson[Option[OAuth2Info]](json \ "oAuth2Info")
        passwordInfo <- Json.fromJson[Option[PasswordInfo]](json \ "passwordInfo")
      } yield SocialUser(
        identityId = identityId,
        firstName = (json \ "firstName").as[String],
        lastName = (json \ "lastName").as[String],
        fullName = (json \ "fullName").as[String],
        email = email,
        avatarUrl = avatarUrl,
        authMethod = authMethod,
        oAuth1Info = oAuth1Info,
        oAuth2Info = oAuth2Info,
        passwordInfo = passwordInfo
      )


    }


  }










  val global_timeline = "global:timeline"  //list

  def post_body(post_id: String) = s"post:$post_id:body"
  def post_author(post_id: String) = s"post:$post_id:author"

  def user_followers(user_id: String) =  s"user:$user_id:followers" //uids of followers

  def uidFromIdentityId(id: IdentityId): String = s"${id.providerId}:${id.userId}"


  val redisUri = sys.env.get("REDISCLOUD_URL").map(new URI(_))

  val redis = redisUri match{
    case Some(u) => Redis(ConfigFactory.empty
      .withValue("client",
      ConfigValueFactory.fromMap(
        Map(
          "host" -> u.getHost(),
          "port" -> u.getPort(),
          "password" -> "raRzMQoBfJTFtwIu"
        ).asJava
      )
    ))
    case None => Redis()
  }

  object ParseJs extends Parser[JsValue]{
    protected def parseImpl(bytes: Array[Byte]): JsValue =
      Json.parse(new String(bytes, "UTF-8"))
  }


  def get_user(user_id: IdentityId): Future[Option[Identity]] =
    redis.get[JsValue](s"user:${uidFromIdentityId(user_id)}:identity")(parser=ParseJs).map{ json =>

      for {
        js <- json
        res <- Json.fromJson[Identity](js).asOpt
      } yield res
    }

  def save_user(user: Identity) = {
    val user_json = Json.toJson[Identity](user).toString

    log.debug(s"save $user as $user_json")

    redis.set(s"user:${uidFromIdentityId(user.identityId)}:identity", user_json)
  }



  def establish_alias(user_id: String, alias: String) = {
    val alias_f = redis.sAdd("global:aliases", alias).map(_==1L)

    alias_f.flatMap{
      case true => redis.set(s"user:$user_id:alias", alias).map(_ => true)
      case false => Future(false)
    }
  }



  private def next_post_id: Future[String] = redis.incr("global:nextPostId").map(_.toString)

  private def topics(s: String): Set[String] = s.split(" ").filter(_.startsWith("#")).map(_.tail).toSet

  def recent_posts: Future[Seq[Msg]] =
    for {
      timeline <- redis.lRange[String](global_timeline, 0, 50)
      posts <-  Future.sequence(timeline.map{post_id =>

        for {
          a <- redis.get[String](post_author(post_id))
          b <- redis.get[String](post_body(post_id))
        } yield (a, b)

      })
    } yield posts.map{case (Some(author), Some(post)) => Msg(author,  topics(post), post)} //fail horribly on failed lookup...

  def followers(user_id: String): Future[Set[String]] = {
    redis.sMembers[String](user_followers(user_id))
  }


  def post(user_id: String, msg: String) = {

    def trim_global = redis.lTrim(global_timeline,0,1000)

    def handle_post(post_id: String, audience: Set[String]) =
      (redis.lPush(global_timeline,post_id)
        |@| redis.set(post_body(post_id), msg)
        |@| redis.set(post_author(post_id), user_id)
        |@| Future.sequence(
        audience.map{u =>
          redis.lPush(s"user:$u:posts", post_id)
        }
      )
        ){ (a,b,c,d) => () }


    for {
      (post_id, followers) <- (next_post_id |@| followers(user_id))((a,b) => (a,b))
      _ <- handle_post(post_id, followers)
      _ <- trim_global
    } yield post_id
  }


}
