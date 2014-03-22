package entities

import scalaz.Equal

case class UserId(uid: String) extends AnyVal

object UserId{
  implicit val equals: Equal[UserId] = Equal.equal(_ == _)
}