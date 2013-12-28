package actors

import akka.actor.Actor

import play.api.libs.iteratee.{Concurrent, Enumerator}

import play.api.libs.iteratee.Concurrent.Channel
import play.api.Logger
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scredis._
import scala.util.{ Success, Failure }
import play.api.libs.concurrent.Execution.Implicits._












class SocketActor extends Actor {
  val redis = Redis()

  case class UserChannel(userId: Int, var channelsCount: Int, enumerator: Enumerator[JsValue], channel: Channel[JsValue])

  lazy val log = Logger("application." + this.getClass.getName)

  type User = Int

  // this map relate every user with his UserChannel
  var webSockets: Map[User, UserChannel] = Map.empty

  //subscriptions
  var subscriptions: Map[User, Seq[String]] = Map.empty

  //track use of each topic
  //update to reflect Redis Integer capacity
  var trending: Map[String, Long] = Map.empty.withDefaultValue(0L)

  override def receive = {



    case StartSocket(userId) =>

      log.debug(s"start new socket for user $userId")

      // get or create the tuple (Enumerator[JsValue], Channel[JsValue]) for current user
      // Channel is very useful class, it allows to write data inside its related
      // enumerator, that allow to create WebSocket or Streams around that enumerator and
      // write data inside that using its related Channel
      val userChannel: UserChannel = webSockets.get(userId) getOrElse {
        val broadcast: (Enumerator[JsValue], Channel[JsValue]) = Concurrent.broadcast[JsValue]
        UserChannel(userId, 0, broadcast._1, broadcast._2)
      }

      // if user open more then one connection, increment just a counter instead of create
      // another tuple (Enumerator, Channel), and return current enumerator,
      // in that way when we write in the channel,
      // all opened WebSocket of that user receive the same data
      userChannel.channelsCount = userChannel.channelsCount + 1
      webSockets += (userId -> userChannel)

      log debug s"channel for user : $userId count : ${userChannel.channelsCount}"
      log debug s"channel count : ${webSockets.size}"

      // return the enumerator related to the user channel,
      // this will be used for create the WebSocket
      sender ! userChannel.enumerator


    case Msg(userId, topics, msg) => {

      println(s"msg: ${Msg(userId, topics, msg)}")

      /*
       1. send message to all user id's, with username (uid for now)
       2. push topic to redis, store map of #hashtag use count, ordered
          retrieve most popular is O(1)
       3. push top N topics to user along with message ie:
          case class Update(Option[Msg] ,.., Option[Trending])
      */

      //update trending
      trending = topics.foldLeft(trending)( (t, topic) => t.updated(topic, t(topic) + 1))

      redis.pipelined { p =>
        topics.foreach{ t =>
            p.hIncrBy("trending")(t, 1)
        }


        p.lPush(s"tweets.$userId", msg)

        p.lRange( s"tweets.$userId", 0, -1 ).onComplete(println(_))
        p.hGetAll( "trending" ).onComplete(println(_))

      }



      redis

      //this is not efficient. Should keep running tally in redis
      val popular = trending.toList.sortBy{case(k, v) => v}.take(5).toMap


      webSockets(userId).channel push Update(Some(Msg(userId, topics, msg)), Some(popular).filterNot(_.isEmpty).map(Trending)).asJson
    }


    case SocketClosed(userId) =>

      log debug s"closed socket for $userId"

      val userChannel = webSockets(userId)

      if (userChannel.channelsCount > 1) {
        userChannel.channelsCount = userChannel.channelsCount - 1
        webSockets += (userId -> userChannel)
        log debug s"channel for user : $userId count : ${userChannel.channelsCount}"
      } else {
        removeUserChannel(userId)
        log debug s"removed channel for $userId"
      }

  }

  def removeUserChannel(userId: Int) = webSockets -= userId

}


sealed trait SocketMessage
sealed trait JsonMessage{
  def asJson: JsValue
}



case class StartSocket(userId: Int) extends SocketMessage

case class SocketClosed(userId: Int) extends SocketMessage

case class Msg(userId: Int, topics: Set[String], msg: String) extends SocketMessage with JsonMessage{
  def asJson = {
    implicit val format = Json.format[Msg]
    Json.toJson(this)
  }
}

case class Trending(trending: Map[String, Long]) extends JsonMessage {
    def asJson = {
      implicit val format = Json.format[Trending]
      Json.toJson(this)
    }

}

case class Update(msg: Option[Msg], trending: Option[Trending]) extends JsonMessage {
  def asJson = {
    implicit val format1 = Json.format[Trending]
    implicit val format2 = Json.format[Msg]
    implicit val format3 = Json.format[Update]
    Json.toJson(this)
  }
}


/*
    implicit val format = new Writes[Update]{
      def writes(o: Update): JsValue = {
         JsObject(Seq(
             msg.

         ))
      }
    }
    */