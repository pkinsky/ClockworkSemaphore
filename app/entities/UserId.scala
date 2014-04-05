package entities

import scalaz.Equal

object UserId{
  //this typeclass allows use of scalaz's type-safe === method
  implicit val equals: Equal[UserId] = Equal.equal(_ == _)
}

// User Id wrapper class
case class UserId(uid: String) extends AnyVal
