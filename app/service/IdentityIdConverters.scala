package service


//https://stackoverflow.com/questions/16895635/convert-scala-2-10-future-to-scalaz-concurrent-future-task
object IdentityIdConverters {

  def idToString(id: IdentityId): String = s"${id.user_id}"

  def stringToId(id: String): IdentityId = id match {
    case s => IdentityId(s) //keeping error handling infrastructure around for later
    case _ => throw new IllegalArgumentException(s"could not parse user id $id")
  }


  implicit class StringEnhancer(s: String) {
    def asId: IdentityId = stringToId(s)
  }


  implicit class IdentityIdEnhancer(id: IdentityId) {
    def asString: String = idToString(id)
  }

}