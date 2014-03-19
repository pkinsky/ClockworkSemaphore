package service


import java.lang.Long.parseLong


import scredis.Redis
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}
import actors._
import scala.concurrent.Future
import scalaz.syntax.applicative.ToApplyOps
import scredis.parsing.Parser
import play.api.libs.json._

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import play.api.libs.concurrent.Execution.Implicits._
import scredis.exceptions.RedisParsingException
import scala.util.{Success, Failure}
import  scalaz._, std.option._, std.tuple._, syntax.bitraverse._
import  Scalaz.ToIdOps


import actors.ApplicativeStuff._






object RedisServiceImpl extends RedisService with UserOps with RedisSchema with RedisConfig {

  private def zipMsgInfo(post_ids: List[String], user_id: UserId): Future[List[MsgInfo]] =
    for {
      posts <-  Future.sequence(post_ids.map{post_id =>
        load_post(post_id).map(r =>
          r.map{
            MsgInfo(post_id, _)
          })
      })
    } yield posts.collect{ case Some(msg) => msg }.toList



  def recent_posts(user_id: UserId): Future[List[MsgInfo]] =
    for {
      timeline <- redis.lRange[String](global_timeline, 0, 50)
      msg_info <- zipMsgInfo(timeline, user_id)
    } yield msg_info


  def load_msg_info(user_id: UserId, post_id: String): Future[Option[MsgInfo]] = {
    for {
      favorites <- redis.sMembers(user_favorites(user_id.uid))
      msgOpt <- load_post(post_id)
    } yield msgOpt.map{
        msg => MsgInfo(post_id, msg)
      }
  }

  def load_post(post_id: String): Future[Option[Msg]] =
    for {
      map <- redis.hmGetAsMap[String](post_info(post_id))("timestamp", "author", "body")
    } yield {
      for{
        timestamp <- map.get("timestamp")
        author <- map.get("author")
        body <- map.get("body")
      } yield Msg(parseLong(timestamp), UserId(author), body)
    }


  def delete_post(post_id: String): Future[Unit] = {
     redis.del(post_info(post_id)).map{ r => log.info("delete result: " + r); () }
  }


  def save_post(post_id: PostId, msg: Msg): Future[Unit] =
    redis.hmSetFromMap(post_info(post_id.pid), Map(
				"timestamp" -> msg.timestamp,
				"author" -> msg.uid,
				"body" -> msg.body
    ))


  def distribute_post(from: UserId, post_id: PostId): Future[Unit] =
    for {
      recipients <- get_followers(from)
      distribution = for (recipient <- recipients + from) yield {
        println(s"push post with id $post_id to $recipient")
        redis.lPush(user_posts(recipient.uid), post_id)
      }
      _ <- Future.sequence(distribution)
    } yield ()

  def post_message(user_id: UserId, msg: Msg): Future[PostId] = {

    def trim_global = redis.lTrim(global_timeline,0,1000)

    def handle_post(post_id: PostId) =
      (redis.lPush(global_timeline,post_id)
        |@| save_post(post_id, msg)
        |@| distribute_post(user_id, post_id)
      ){ (a,b,c) => log.info("done handling post!"); () }


    for {
      post_id <- redis.incr(next_post_id).map(id => PostId(id.toString))
      _ <- handle_post(post_id)
      _ <- trim_global
    } yield post_id
  }


}
