package service

import scala.concurrent.Future
import entities.{Msg, PostId, AuthToken, UserId}
import scredis.Client
import scredis.pubsub.PubSubMessage

//currently just a wrapper for the client object
//todo: move all pubsub logic here, should not need to know channel names in SocketActor
trait PubSubServiceLayer {

  trait PubSubService {
    def unsubscribe(channels: String): Unit

    def subscribe(channel: String)(pf: PartialFunction[PubSubMessage, Any]): Unit
  }

  type PubSubServiceLike <: PubSubService

  val pubSubService: PubSubServiceLike
}