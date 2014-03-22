package entities

import scalaz.Equal

object AuthToken{
  implicit val equals: Equal[AuthToken] = Equal.equal(_ == _)
}

case class AuthToken(token: String) extends AnyVal
