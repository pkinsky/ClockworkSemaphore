package entities

import scalaz.Equal

case class PostId(pid: String) extends AnyVal

object PostId{
  implicit val equals: Equal[PostId] = Equal.equal(_ == _)
}