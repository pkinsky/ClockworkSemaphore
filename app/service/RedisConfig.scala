package service

import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import scredis.{Redis, Client}
import java.net.URI

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import play.api.Logger


trait RedisConfig {
  lazy val log = Logger("application." + this.getClass.getName)

  private val redisUri: Option[URI] = sys.env.get("REDISTOGO_URL").map(new URI(_))



  def flushall = redis.flushAll() //calling this results in dooooom! DOOOOOM!

  val defaultConfig = ConfigFactory.empty
    .withValue("async", ConfigValueFactory.fromMap(Map("auto-pipeline"->false)))


  /*
  todo: currently all commands are retried if an error is received by the client.
  Could lead to double LPUSH of posts, in very rare scenarios where command succeeds without ack.
  //Scredis allows per-command retry scope, use that instead
   */

  private val config = redisUri match{
    case Some(uri) => defaultConfig
      .withValue("client",
        ConfigValueFactory.fromMap(
          Map(
            "host" -> uri.getHost(),
            "port" -> uri.getPort(),
            "password" -> uri.getUserInfo().split(":",2)(1),
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




