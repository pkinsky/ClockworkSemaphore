package entities

import scalaz.Equal

case class PostId(pid: String) extends AnyVal

object PostId{
  //this typeclass allows use of scalaz's type-safe === method
  implicit val equals: Equal[PostId] = Equal.equal(_ == _)
}