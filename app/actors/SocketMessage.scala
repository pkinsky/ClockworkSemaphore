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

object AckRequestAlias {
  implicit val format = Json.format[AckRequestAlias]
}

object PublicIdentity {
  implicit val format = Json.format[PublicIdentity]}


object MsgInfo{
  implicit val format = Json.format[MsgInfo]
}

object Update {
  implicit val format = Json.format[Update]
}


case class MsgInfo(post_id: String, favorite: Boolean, msg: Msg)

//todo: case class representing message + isFavorite and post_id for sending to client
case class Msg(timestamp: Long, uid: String, body: String) extends JsonMessage with SocketMessage{
  def asJson = Json.toJson(this)
}

case class AckRequestAlias(alias: String, pass: Boolean) extends JsonMessage{
  def asJson = Json.toJson(this)
}

//user specific, following: is the current user following this dude?
case class PublicIdentity(user_id: String, alias: String, avatar_url: Option[String], recent_posts: List[String], about_me: String) extends JsonMessage {
  def asJson = Json.toJson(this)
}


case class Update(msg: List[MsgInfo]=Nil,
                  alias_result: Option[AckRequestAlias]=None,
                  user_info: Option[PublicIdentity]=None,
                  deleted: Set[String] = Set.empty,
                  recent_messages: List[String]=Nil,
                  followed_messages: List[String]=Nil) extends JsonMessage {

  def asJson = Json.toJson(this)
}






sealed trait SocketMessage


case class MakePost(author_uid: String, msg: Msg)

case class PushPost(requesting_uid: String, msg: MsgInfo)

case class RequestPost(requesting_uid: String, post_id: String)

case class RecentPosts(uid: String)

case object Register extends SocketMessage

case class StartSocket(uid: String) extends SocketMessage

case class SocketClosed(uid: String) extends SocketMessage

case class RequestAlias(uid: String, alias: String) extends SocketMessage

case class RequestInfo(requesting_uid: String, user_id: String) extends SocketMessage

case class DeleteMessage(uid: String, post_id: String)

case class FavoriteMessage(uid: String, post_id: String)

case class UnFavoriteMessage(uid: String, post_id: String)

case class SetAboutMe(uid: String, about_me: String)

case class UnFollowUser(uid: String, to_unfollow: String)

case class FollowUser(uid: String, to_unfollow: String)