package service

import securesocial.core._
import play.api.libs.json._
import securesocial.core.providers.Token
import scredis.parsing.Parser

/**
 * Created by paul on 1/26/14.
 */
object IdentityToJson {
  //ALL THIS SHIT. ALL OF IT. GONE. BECAUSE HASHMAPS!

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
}
