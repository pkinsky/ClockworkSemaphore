package actors

import play.api.libs.json._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsNumber


sealed trait JsonMessage{
  def asJson: JsValue
}


object Msg {

  implicit val format = new Format[Msg]{
    def writes(msg: Msg): JsValue = {
      JsObject(Seq(
        ("timestamp", JsNumber(msg.timestamp)),
        ("user_id", JsString(msg.uid)),
        ("body", JsString(msg.body))
      ))
    }

    def reads(json: JsValue): JsResult[Msg] =
      for{
        timeStamp <- Json.fromJson[Long](json \ "timestamp")
        identityId <- Json.fromJson[String](json \ "user_id")
        msg <- Json.fromJson[String](json \ "body")
      } yield Msg(timeStamp,identityId, msg)
  }

}


object PublicIdentity {
  implicit val format = Json.format[PublicIdentity]}


object MsgInfo{
  implicit val format = Json.format[MsgInfo]
}

object Update {
  implicit val format = Json.format[Update]
}


case class MsgInfo(post_id: String, favorite: Boolean, msg: Msg) extends JsonMessage {
  def asJson = Json.toJson(this)
}

//todo: case class representing message + isFavorite and post_id for sending to client
case class Msg(timestamp: Long, uid: String, body: String) extends JsonMessage with SocketMessage{
  def asJson = Json.toJson(this)
}

//user specific, following: is the current user following this dude?
case class PublicIdentity(user_id: String, alias: String, avatar_url: Option[String],
                          recent_posts: List[String], about_me: String, following: Boolean) extends JsonMessage {
  def asJson = Json.toJson(this)
}


case class Update(msg: List[MsgInfo]=Nil,
                  user_info: Option[PublicIdentity]=None,
                  deleted: Set[String] = Set.empty,
                  recent_messages: List[String]=Nil,
                  followed_messages: List[String]=Nil) extends JsonMessage {

  def asJson = Json.toJson(this)
}






sealed trait SocketMessage


case class MakePost(author_uid: String, msg: Msg)

case class RecentPosts(uid: String)

case class StartSocket(uid: String) extends SocketMessage

case class SocketClosed(uid: String) extends SocketMessage

case class RequestAlias(uid: String, alias: String) extends SocketMessage

case class SetAboutMe(uid: String, about_me: String)