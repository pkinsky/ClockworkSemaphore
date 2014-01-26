package service

import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import scredis.Redis
import java.net.URI

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import play.api.Logger


/**
 * Created by paul on 1/26/14.
 */
trait RedisConfig {
  lazy val log = Logger("application." + this.getClass.getName)

  private val redisUri = sys.env.get("REDISCLOUD_URL").map(new URI(_))

  protected val redis = redisUri match{
    case Some(u) => Redis(ConfigFactory.empty
      .withValue("client",
        ConfigValueFactory.fromMap(
          Map(
            "host" -> u.getHost(),
            "port" -> u.getPort(),
            "password" -> "raRzMQoBfJTFtwIu"
          ).asJava
        )
      ))
    case None => Redis()
  }
}
