package service


import java.lang.Long.parseLong

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


import  scalaz._, std.option._, std.tuple._, syntax.bitraverse._

import  Scalaz.ToIdOps


import IdentityIdConverters._



object RedisServiceImpl extends RedisService{
  lazy val log = Logger("application." + this.getClass.getName)



  
  //ALL THIS SHIT. ALL OF IT. GONE. BECAUSE HASHMAPS!
  
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


  private val global_timeline = "global:timeline"  //list


  
  private def post_info(post_id: String) = s"post:$post_id:info"


  private def user_favorites(user_id: IdentityId) = s"user:${idToString(user_id)}:favorites"

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



  def get_user(user_id: IdentityId): Future[Identity] =
    redis.get[JsValue](s"user:${idToString(user_id)}:identity")(parser=ParseJs).map{ json =>
      log.info(s"get_user($user_id) result: $json")
      for {
        js <- json
        res <- Json.fromJson[Identity](js).asOpt
      } yield res
    }.flatMap{
      case Some(u) => Applicative[Future].point(u)
      case None => Future.failed(new Exception(s"user id $user_id not found"))
    }


  def get_public_user(user_id: IdentityId): Future[PublicIdentity] = {
    for {
      id <- get_user(user_id)
      following <- get_followers(user_id)
      alias <- get_alias(user_id)
    } yield {
        log.info(s"get_public_user for $user_id results: $id, $alias")
        PublicIdentity(idToString(id.identityId), alias.getOrElse(""), following, id.avatarUrl)
    }
  }

  def load_favorite_posts(user_id: IdentityId): Future[Set[String]] = {
    redis.sMembers[String](user_favorites(user_id))
  }

  def remove_favorite_post(user_id: IdentityId, post_id: String): Future[Unit] = {
    redis.sRem(user_favorites(user_id), post_id).map( _ => () ) //return Unit?
  }


  def add_favorite_post(user_id: IdentityId, post_id: String): Future[Unit] = {
    redis.sAdd(user_favorites(user_id), post_id).map( _ => () ) //return Unit?
  }

  def save_user(user: Identity): Future[Unit] = {
    val user_json = Json.toJson[Identity](user).toString
    log.info(s"save $user to user:${idToString(user.identityId)}:identity")

    redis.set(s"user:${idToString(user.identityId)}:identity", user_json)
  }

  private def get_alias(user_id: IdentityId): Future[Option[String]] =
    redis.get[String](user_alias(user_id))


  def establish_alias(user_id: IdentityId, alias: String) = {

    val alias_f = redis.sAdd("global:aliases", alias).map(_==1L)

    alias_f.flatMap{
      case true => redis.set(user_alias(user_id), alias).map(_ => true)
      case false => Future(false)
    }
  }


  private def next_post_id: Future[String] = redis.incr("global:nextPostId").map(_.toString)

  def followed_posts(user_id: IdentityId): Future[List[MsgInfo]] =
    for {
      following <- redis.lRange[String](user_posts(user_id), 50)

      favorites <- redis.sMembers(user_favorites(user_id))

      posts_f: Seq[Future[Option[MsgInfo]]] = following.map{post_id =>
        load_post(post_id).map(r => r.map{ MsgInfo(post_id, favorites.contains(post_id), _) })
      }

      posts <-  Future.sequence(posts_f)
    } yield posts.collect{ case Some(msg) => msg }.toList //skip deleted messages



  def recent_posts(user_id: IdentityId): Future[List[MsgInfo]] =
    for {
      timeline <- redis.lRange[String](global_timeline, 0, 50)

      favorites <- redis.sMembers(user_favorites(user_id))

      posts_f: Seq[Future[Option[MsgInfo]]] = timeline.map{post_id =>
        load_post(post_id).map(r => r.map{ MsgInfo(post_id, favorites.contains(post_id), _) })
      }

      posts <-  Future.sequence(posts_f)
    } yield posts.collect{ case Some(msg) => msg }.toList //skip deleted messages

  def delete_follower(user_id: IdentityId, following: IdentityId): Future[Unit] = {
    redis.sRem(user_followers(user_id), following.asString).map( _ => () )
  }

  def add_follower(user_id: IdentityId, following: IdentityId): Future[Unit] = {
    redis.sAdd(user_followers(user_id), following.asString).map( _ => () )
  }

  def get_followers(user_id: IdentityId): Future[Set[String]] = {
    redis.sMembers[String](user_followers(user_id))
  }

  def load_post(post_id: String): Future[Option[Msg]] =
    for {
      map <- redis.hmGetAsMap[String](post_info(post_id))("timestamp", "author", "body")
    } yield {
      for{
        timestamp <- map.get("timestamp")
        author <- map.get("author")
        body <- map.get("body")
      } yield Msg(parseLong(timestamp), author.asId, body)
    }


  def delete_post(post_id: String): Future[Unit] = {
     redis.del(post_info(post_id)).map{ r => log.info("delete result: " + r); () }
  }


  def save_post(post_id: String, msg: Msg): Future[Unit] =
    redis.hmSetFromMap(post_info(post_id), Map(
				"timestamp" -> msg.timestamp,
				"author" -> msg.user_id.asString,
				"body" -> msg.body
    ))


  private def user_posts(user_id: IdentityId): String = s"user:${user_id.asString}:posts"


  //need to use Msg object here, load_post will return Msg object as well. ditto for every object, 
  //use a hashmap to speed lookup! multiple keys == multiple lookups!
  def post(user_id: IdentityId, msg: Msg): Future[String] = {

    def trim_global = redis.lTrim(global_timeline,0,1000)

    def handle_post(post_id: String, audience: Set[String]) =
      (redis.lPush(global_timeline,post_id)
        |@| save_post(post_id, msg)
        |@| Future.sequence(
          audience.map{ u => redis.lPush(user_posts(u.asId), post_id) }
        )
        ){ (a,b,c) => () }


    for {
      (post_id, followers) <- (next_post_id |@| get_followers(msg.user_id))((a,b) => (a,b))
      _ <- handle_post(post_id, followers)
      _ <- trim_global
    } yield post_id
  }


}
