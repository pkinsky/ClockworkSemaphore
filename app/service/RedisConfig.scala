package service

import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import scredis.{Redis, Client}
import java.net.URI

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._



trait RedisConfig {

  private val redisUri: Option[URI] = sys.env.get("REDISTOGO_URL").map(new URI(_))

    /** calling this results in dooooom! DOOOOOM!
     *  (flushes current redis config. Do not call from non-test code)
     */
  def flushall = redis.flushAll()

  val defaultConfig = ConfigFactory.empty

  val config = redisUri match{
    case Some(uri) => defaultConfig
      .withValue("client",
        ConfigValueFactory.fromMap(
          Map(
            "host" -> uri.getHost(),
            "port" -> uri.getPort(),
            "password" -> uri.getUserInfo().split(":",2)(1),
            "tries" ->  5
          ).asJava
        )
      )
    case None => defaultConfig
      .withValue("client", ConfigValueFactory.fromMap(Map("tries"->5)))
  }

  protected lazy val redis = Redis(config)

  def getClient = Client(config)

}




