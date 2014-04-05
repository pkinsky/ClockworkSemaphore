package entities

import play.api.libs.json.Json

object Update {
  implicit val format = Json.format[Update]
}

/**
 * Used for communication from the server to the client via websocket
 * @param feed the feed this update is associated with. currently global or my_feed (check that these values are correct, maybe use enum)
 * @param users User info for the authors of every Msg in messages
 * @param messages messages contained in this update
 */
case class Update(feed: String, users: Seq[User], messages: Seq[Msg]) extends JsonMessage {
  def asJson = Json.toJson(this)
}
