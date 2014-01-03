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
import securesocial.core._
import securesocial.core.providers.Token
import securesocial.core.IdentityId
import scredis.Redis
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scredis.parsing.{Parser, StringParser}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Await
import scala.concurrent.duration._
import securesocial.core.SocialUser

/**
 * It will be necessary to block on redis calls here. cache heavily to minimize blocking api calls.
 */
class RedisUserService(application: Application) extends UserServicePlugin(application) {
  private var users = Map[String, Identity]()
  private var tokens = Map[String, Token]()





  val redis = Redis()


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
        ("fullName", JsString(user.lastName)),
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

  /*
  def identToJson(user: Identity) = {


    val js = JsObject(Seq(
      ("identityId", Json.toJson(user.identityId)),
      ("firstName", JsString(user.firstName)),
      ("lastName", JsString(user.lastName)),
      ("fullName", JsString(user.lastName)),
      ("email", Json.toJson(user.email)),
      ("avatarUrl", Json.toJson(user.avatarUrl)),
      ("authMethod", Json.toJson(user.authMethod)),
      ("oAuth1Info", Json.toJson(user.oAuth1Info)),
      ("oAuth2Info", Json.toJson(user.oAuth2Info)),
      ("passwordInfo", Json.toJson(user.passwordInfo))
    ))

    js

  }*/


  object ParseJs extends Parser[JsValue]{
    protected def parseImpl(bytes: Array[Byte]): JsValue =
      Json.parse(new String(bytes, "UTF-8"))
  }


  def find(id: IdentityId): Option[Identity] = {
    val fJson = redis.get[JsValue](s"user:${id.providerId}:${id.userId}:identity")(parser=ParseJs)
    val json = Await.result(fJson, 1 second)

    val r: Option[Identity] =  for {
      js <- json
      res <- Json.fromJson[Identity](js).asOpt
    } yield res

    r
  }

  def findByEmailAndProvider(email: String, providerId: String): Option[Identity] = {
    Logger.error("findByEmailAndProvider while username/password disabled")
    None
  }

  //oh god this is awful
  def save(user: Identity): Identity = {
    val r = redis.set(s"user:${user.identityId.providerId}:${user.identityId.userId}:identity", Json.toJson[Identity](user))

    Await.result(r, 1 second)

    user
  }

  def save(token: Token) {

    val r = redis.set(s"token:${token.uuid}", token)

    val json = Await.result(r, 1 second)


    tokens += (token.uuid -> token)
  }

  def findToken(token: String): Option[Token] = {
    tokens.get(token)
  }

  def deleteToken(uuid: String) {
    tokens -= uuid
  }

  def deleteTokens() {
    tokens = Map()
  }

  def deleteExpiredTokens() {
    tokens = tokens.filter(!_._2.isExpired)
  }
}