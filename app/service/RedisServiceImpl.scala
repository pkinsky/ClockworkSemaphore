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

  private def zipMsgInfo(post_ids: List[String], user_id: String): Future[List[MsgInfo]] =
    for {
      favorites <- redis.sMembers(user_favorites(user_id))
      posts <-  Future.sequence(post_ids.map{post_id =>
        load_post(post_id).map(r =>
          r.map{
            MsgInfo(post_id, favorites.contains(post_id), _)
          })
      })
    } yield posts.collect{ case Some(msg) => msg }.toList



  def recent_posts(user_id: String): Future[List[MsgInfo]] =
    for {
      timeline <- redis.lRange[String](global_timeline, 0, 50)
      msg_info <- zipMsgInfo(timeline, user_id)
    } yield msg_info


  def load_msg_info(user_id: String, post_id: String): Future[Option[MsgInfo]] = {
    for {
      favorites <- redis.sMembers(user_favorites(user_id))
      msgOpt <- load_post(post_id)
    } yield msgOpt.map{
        msg => MsgInfo(post_id, favorites.contains(post_id), msg)
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
      } yield Msg(parseLong(timestamp), author, body)
    }


  def delete_post(post_id: String): Future[Unit] = {
     redis.del(post_info(post_id)).map{ r => log.info("delete result: " + r); () }
  }


  def save_post(post_id: String, msg: Msg): Future[Unit] =
    redis.hmSetFromMap(post_info(post_id), Map(
				"timestamp" -> msg.timestamp,
				"author" -> msg.uid,
				"body" -> msg.body
    ))


  def distribute_post(from: String, post_id: String): Future[Unit] =
    for {
      recipients <- get_followers(from)
      distribution = for (recipient <- recipients + from) yield {
        println(s"push post with id $post_id to $recipient")
        redis.lPush(user_posts(recipient), post_id)
      }
      _ <- Future.sequence(distribution)
    } yield ()

  def post(user_id: String, msg: Msg): Future[String] = {

    def trim_global = redis.lTrim(global_timeline,0,1000)

    def handle_post(post_id: String) =
      (redis.lPush(global_timeline,post_id)
        |@| save_post(post_id, msg)
        |@| distribute_post(user_id, post_id)
      ){ (a,b,c) => log.info("done handling post!"); () }


    for {
      post_id <- redis.incr(next_post_id).map(_.toString)
      _ <- handle_post(post_id)
      _ <- trim_global
    } yield post_id
  }


}
