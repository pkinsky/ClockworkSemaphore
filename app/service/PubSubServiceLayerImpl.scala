package service

import scredis.pubsub.PubSubMessage

trait PubSubServiceLayerImpl extends PubSubServiceLayer {

  override val pubSubService = new PubSubServiceImpl()

  type PubSubServiceLike = PubSubServiceImpl

  class PubSubServiceImpl extends PubSubService with RedisConfig{

    def unsubscribe(channel: String): Unit = client.unsubscribe(channel)

    def subscribe(channel: String)(pf: PartialFunction[PubSubMessage, Any]): Unit = client.subscribe(channel)(pf)

  }


}