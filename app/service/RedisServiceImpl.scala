package service

import java.net.URI
import scredis.Redis
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import actors._
import ApplicativeStuff._
import scala.concurrent.Future
import scalaz.syntax.applicative.ToApplyOps
import play.api.libs.json._
import scredis.parsing.Parser
import securesocial.core._
import securesocial.core.providers.Token
import play.api.libs.json.JsObject
import securesocial.core.OAuth1Info
import securesocial.core.IdentityId
import securesocial.core.providers.Token
import securesocial.core.OAuth2Info
import securesocial.core.PasswordInfo
import play.api.Logger

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import play.api.libs.concurrent.Execution.Implicits._
import scredis.exceptions.RedisParsingException
import securesocial.core.OAuth1Info
import securesocial.core.IdentityId
import play.api.libs.json.JsString
import securesocial.core.providers.Token
import securesocial.core.OAuth2Info
import securesocial.core.PasswordInfo
import play.api.libs.json.JsObject
import actors.Msg
import scala.util.{Success, Failure}

import scalaz.concurrent.Task

import  scalaz._
import  Scalaz.ToIdOps

import service.RedisService


import ScalaFutureConverters._






object RedisServiceImpl extends RedisService{
  lazy val log = Logger("application." + this.getClass.getName)



  private implicit val format1 = Json.format[IdentityId]
  private implicit val format2 = Json.format[AuthenticationMethod]
  private implicit val format3 = Json.format[OAuth1Info]
  private implicit val format4 = Json.format[OAuth2Info]
  private implicit val format5 = Json.format[PasswordInfo]
  private implicit val format6 = Json.format[Token]

  private implicit val format8 = new Format[Identity]{
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



  def idToString(id: IdentityId): String = s"${id.providerId}:${id.userId}"

  def stringToId(id: String): IdentityId = id.split(":") match {
    case Array(user_id, provider_id) => IdentityId(provider_id, user_id)
    case _ => throw new IllegalArgumentException(s"could not parse user id $id")
  }




  private val global_timeline = "global:timeline"  //list

  private def post_body(post_id: String) = s"post:$post_id:body"
  private def post_author(post_id: String) = s"post:$post_id:author"

  private def user_alias(user_id: IdentityId) = s"user:${idToString(user_id)}:alias"

  private def user_followers(user_id: IdentityId) =  s"user:${idToString(user_id)}:followers" //uids of followers



  private val redisUri = sys.env.get("REDISCLOUD_URL").map(new URI(_))

  private val redis = redisUri match{
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

  private object ParseJs extends Parser[JsValue]{
    protected def parseImpl(bytes: Array[Byte]): JsValue =
      Json.parse(new String(bytes, "UTF-8"))
  }


  def get_user_as_task(user_id: IdentityId): Task[Identity] =
    get_user(user_id).asTask.flatMap{
      case Some(id) => Monad[Task].point(id)
      case None => Task.fail(new Exception(s"user id $user_id not found"))
    }



  def get_user(user_id: IdentityId): Future[Option[Identity]] =
    redis.get[JsValue](s"user:${idToString(user_id)}:identity")(parser=ParseJs).map{ json =>
      log.info(s"get_user($user_id) result: $json")
      for {
        js <- json
        res <- Json.fromJson[Identity](js).asOpt
      } yield res
    }

  def get_public_user_as_task(user_id: IdentityId): Task[PublicIdentity] = {

    //what happens if I filter a task? what does it contain if filter fails?
    get_public_user(user_id).asTask.flatMap{
      case Some(id) => Monad[Task].point(id)
      case None => Task.fail(new Exception(s"user id $user_id not found"))
    }

  }


  def get_public_user(user_id: IdentityId): Future[Option[PublicIdentity]] = {
    for {
      id_opt <- get_user(user_id)
      alias_opt <- get_alias(user_id)
    } yield {
        log.info(s"get_public_user for $user_id results: $id_opt, $alias_opt")
        for {
          id <- id_opt
          alias <- alias_opt
        } yield PublicIdentity(idToString(id.identityId), alias, id.avatarUrl)
    }
  }



  def save_user_as_task(user: Identity): Task[Unit] =
    save_user(user).asTask


  def save_user(user: Identity): Future[Unit] = {
    val user_json = Json.toJson[Identity](user).toString
    log.info(s"save $user to user:${idToString(user.identityId)}:identity")

    redis.set(s"user:${idToString(user.identityId)}:identity", user_json)
  }

  private def get_alias(user_id: IdentityId) = redis.get[String](user_alias(user_id))

  def establish_alias_as_task(user_id: IdentityId, alias: String): Task[Boolean] = establish_alias(user_id, alias).asTask

  def establish_alias(user_id: IdentityId, alias: String) = {
    val alias_f = redis.sAdd("global:aliases", alias).map(_==1L)

    alias_f.flatMap{
      case true => redis.set(user_alias(user_id), alias).map(_ => true)
      case false => Future(false)
    }
  }


  private def next_post_id: Future[String] = redis.incr("global:nextPostId").map(_.toString)


  def recent_posts_as_task: Task[Seq[Msg]] = recent_posts.asTask

  //fuck this method, srsly
  def recent_posts: Future[Seq[Msg]] =
    for {
      timeline <- redis.lRange[String](global_timeline, 0, 50)
      posts <-  Future.sequence(timeline.map{post_id =>

        for {
          author <- redis.get[String](post_author(post_id))
          b <- redis.get[String](post_body(post_id))
        } yield (author.map(stringToId(_)), b)

      })
    } yield posts.map{case (Some(author), Some(post)) => Msg(author, post)} //fail horribly on failed lookup...



  def get_followers_as_task(user_id: IdentityId): Task[Set[String]] = get_followers(user_id).asTask

  def get_followers(user_id: IdentityId): Future[Set[String]] = {
    redis.sMembers[String](user_followers(user_id))
  }

  def post_as_task(user_id: IdentityId, msg: String): Task[String] =
    post(user_id, msg).asTask

  def post(user_id: IdentityId, msg: String): Future[String] = {

    def trim_global = redis.lTrim(global_timeline,0,1000)

    def handle_post(post_id: String, audience: Set[String]) =
      (redis.lPush(global_timeline,post_id)
        |@| redis.set(post_body(post_id), msg)
        |@| redis.set(post_author(post_id), idToString(user_id))
        |@| Future.sequence(
        audience.map{u =>
          redis.lPush(s"user:$u:posts", post_id)
        }
      )
        ){ (a,b,c,d) => () }


    for {
      (post_id, followers) <- (next_post_id |@| get_followers(user_id))((a,b) => (a,b))
      _ <- handle_post(post_id, followers)
      _ <- trim_global
    } yield post_id
  }


}
