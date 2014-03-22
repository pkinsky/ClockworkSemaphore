package entities

import play.api.libs.json.Json

object Update {
  implicit val format = Json.format[Update]
}

// users is map(user id => user name)
case class Update(feed: String, users: Seq[User], messages: Seq[Msg]) extends JsonMessage {
  def asJson = Json.toJson(this)
}