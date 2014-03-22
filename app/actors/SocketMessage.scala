package actors

import play.api.libs.json._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsNumber
import entities.{PostId, UserId}

sealed trait JsonMessage{
  def asJson: JsValue
}

sealed trait SocketMessage

case class SendMessages(src: String, user_id: UserId, posts: Seq[PostId])

case class MakePost(author_uid: UserId, body: String)

case class StartSocket(uid: UserId) extends SocketMessage

case class SocketClosed(uid: UserId) extends SocketMessage