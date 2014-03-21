package actors

import play.api.libs.json._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsNumber
import service.{UserId, PostId}

sealed trait JsonMessage{
  def asJson: JsValue
}


object Msg {

  implicit val format = new Format[Msg]{
    def writes(msg: Msg): JsValue = {
      JsObject(Seq(
        ("timestamp", JsNumber(msg.timestamp)),
        ("user_id", JsString(msg.uid.uid)),
        ("body", JsString(msg.body))
      ))
    }

    def reads(json: JsValue): JsResult[Msg] =
      for{
        timeStamp <- Json.fromJson[Long](json \ "timestamp")
        uid <- Json.fromJson[String](json \ "user_id")
        msg <- Json.fromJson[String](json \ "body")
      } yield Msg(timeStamp, UserId(uid), msg)
  }

}


//todo: case class representing message + isFavorite and post_id for sending to client
case class Msg(timestamp: Long, uid: UserId, body: String) extends JsonMessage with SocketMessage{
  def asJson = Json.toJson(this)
}


object MsgInfo {
  implicit val format = Json.format[MsgInfo]
}


//pid is post id, but using string to avoid need for custom serializer
case class MsgInfo(src: String, pid: String, msg: Msg) extends JsonMessage{
  def asJson = Json.toJson(this)
}


sealed trait SocketMessage

case class SendMessage(src: String, user_id: UserId, post_id: PostId)

case class MakePost(author_uid: UserId, msg: Msg)

case class RecentPosts(uid: UserId)

case class StartSocket(uid: UserId) extends SocketMessage

case class SocketClosed(uid: UserId) extends SocketMessage

case class RequestAlias(uid: UserId, alias: String) extends SocketMessage

case class SetAboutMe(uid: UserId, about_me: String)
