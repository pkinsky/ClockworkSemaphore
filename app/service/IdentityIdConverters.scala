package service

import securesocial.core.IdentityId

//https://stackoverflow.com/questions/16895635/convert-scala-2-10-future-to-scalaz-concurrent-future-task
object IdentityIdConverters {

  def idToString(id: IdentityId): String = s"${id.providerId}:${id.userId}"

  def stringToId(id: String): IdentityId = id.split(":") match {
    case Array(user_id, provider_id) => IdentityId(provider_id, user_id)
    case _ => throw new IllegalArgumentException(s"could not parse user id $id")
  }


  implicit class StringEnhancer(s: String) {
    def asId: IdentityId = stringToId(s)
  }


  implicit class IdentityIdEnhancer(id: IdentityId) {
    def asString: String = idToString(id)
  }

}