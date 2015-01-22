package entities

import play.api.libs.json.Json

object User {
  implicit val format = Json.format[User]
}

case class User(uid: String, username: String, isFollowing: Boolean) extends JsonMessage

