package entities

import scalaz.Equal


object PostId{
  //this typeclass allows use of scalaz's type-safe === method
  implicit val equals: Equal[PostId] = Equal.equal(_ == _)
}


//Post Id wrapper class.
case class PostId(pid: String) extends AnyVal

