/**
 * Copyright 2012 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package service

import play.api.{Logger, Application}
import securesocial.core.{AuthenticationMethod, OAuth1Info, OAuth2Info, Identity, PasswordInfo, IdentityId, UserServicePlugin}
import scredis.Redis
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scredis.parsing.{Parser, StringParser}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Await
import scala.concurrent.duration._
import securesocial.core.SocialUser
import com.typesafe.config.{ConfigValueFactory, ConfigValue, Config, ConfigFactory}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import java.net.URI
import securesocial.core.providers.Token

/**
 * It will be necessary to block on redis calls here. cache heavily to minimize blocking api calls.
 */

trait RedisBase {
  val global_timeline = "global:timeline"  //list

  def post_body(post_id: String) = s"post:$post_id:body"
  def post_author(post_id: String) = s"post:$post_id:author"

  def user_followers(user_id: String) =  s"user:$user_id:followers" //uids of followers

  val redisUri = new URI(sys.env.get("REDISCLOUD_URL").getOrElse("redis://rediscloud:raRzMQoBfJTFtwIu@pub-redis-18175.us-east-1-2.2.ec2.garantiadata.com:18175"))

  val config = ConfigFactory.empty
    .withValue("client",
      ConfigValueFactory.fromMap(
        Map(
          "host" -> redisUri.getHost(),
          "port" -> redisUri.getPort(),
          "password" -> "raRzMQoBfJTFtwIu"
        ).asJava
      )
    )

}



object RedisUserService extends RedisBase{
  def uidFromIdentityId(id: IdentityId): String = s"${id.providerId}:${id.userId}"

  val redis = Redis(config)

}


class RedisUserService(application: Application) extends UserServicePlugin(application) with RedisBase {
  lazy val log = Logger("application." + this.getClass.getName)




  import RedisUserService._


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


  object ParseJs extends Parser[JsValue]{
    protected def parseImpl(bytes: Array[Byte]): JsValue =
      Json.parse(new String(bytes, "UTF-8"))
  }


  def find(id: IdentityId): Option[Identity] = {

    log.debug(s"find $id")

    val fJson = redis.get[JsValue](s"user:${uidFromIdentityId(id)}:identity")(parser=ParseJs)
    val json = Await.result(fJson, 1 second)

    val r: Option[Identity] =  for {
      js <- json
      res <- Json.fromJson[Identity](js).asOpt
    } yield res

    log.debug(s"found $json => $r")

    r
  }


  //oh god this is awful
  def save(user: Identity): Identity = {
    val json = Json.toJson[Identity](user).toString
    log.debug(s"save $user as $json")
    val r = redis.set(s"user:${uidFromIdentityId(user.identityId)}:identity", json)

    Await.result(r, 1 second)

    user
  }

  //not implemented
  def findByEmailAndProvider(email: String, providerId: String): Option[Identity] = None
  def save(token: Token) = None
  def findToken(token: String): Option[Token] = None
  def deleteToken(uuid: String) = None
  def deleteTokens() = None
  def deleteExpiredTokens() = None
}