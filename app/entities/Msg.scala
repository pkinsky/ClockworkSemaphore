package entities

import play.api.libs.json._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsNumber

object Msg {

  implicit val format = new Format[Msg]{
    def writes(msg: Msg): JsValue = {
      JsObject(Seq(
        ("timestamp", JsNumber(msg.timestamp)),
        ("user_id", JsString(msg.uid.uid)),
        ("post_id", JsString(msg.post_id.pid)),
        ("body", JsString(msg.body))
      ))
    }

    def reads(json: JsValue): JsResult[Msg] =
      for{
        timeStamp <- Json.fromJson[Long](json \ "timestamp")
        uid <- Json.fromJson[String](json \ "user_id")
        pid <- Json.fromJson[String](json \ "post_id")
        msg <- Json.fromJson[String](json \ "body")
      } yield Msg(PostId(pid), timeStamp, UserId(uid), msg)
  }

}

case class Msg(post_id: PostId, timestamp: Long, uid: UserId, body: String) extends JsonMessage {
  def asJson = Json.toJson(this)
}