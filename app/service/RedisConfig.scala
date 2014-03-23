package service

import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import scredis.{Redis, Client}
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

  def flushall = redis.flushAll() //calling this results in dooooom! DOOOOOM!

  val defaultConfig = ConfigFactory.empty
    .withValue("async", ConfigValueFactory.fromMap(Map("auto-pipeline"->false)))


  /*
  todo: currently all commands are retried if an error is received by the client.
  Could lead to double LPUSH of posts, in very rare scenarios. Scredis allows per-command retry scope, use that instead
   */

  private val config = redisUri match{
    case Some(u) => defaultConfig
      .withValue("client",
        ConfigValueFactory.fromMap(
          Map(
            "host" -> u.getHost(),
            "port" -> u.getPort(),
            "password" -> "raRzMQoBfJTFtwIu",
            "tries" ->  1
          ).asJava
        )
      )
    case None => defaultConfig
      .withValue("client", ConfigValueFactory.fromMap(Map("tries"->5)))

  }

  protected lazy val redis = Redis(config)

  protected lazy val client = Client(config)

}




