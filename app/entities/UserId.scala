package entities

import scalaz.Equal

case class UserId(uid: String) extends AnyVal

object UserId{
  //this typeclass allows use of scalaz's type-safe === method
  implicit val equals: Equal[UserId] = Equal.equal(_ == _)
}