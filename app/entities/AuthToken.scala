package entities

import scalaz.Equal

object AuthToken{
  //this typeclass allows use of scalaz's type-safe === method
  implicit val equals: Equal[AuthToken] = Equal.equal(_ == _)
}

case class AuthToken(token: String) extends AnyVal
