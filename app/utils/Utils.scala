package utils

import scala.concurrent.Future

/**
 * Created by paul on 3/22/14.
 */
object Utils {

  def predicate(condition: Boolean, fail: => String): Future[Unit] =
    if (condition) Future.successful( () ) else Future.failed(Stop(fail))

  def match_or_else[A, B](to_match: A, fail: => String)(pf: PartialFunction[A, B]): Future[B] =
    if (pf.isDefinedAt(to_match)){
      try{
        Future.successful( pf(to_match) )
      } catch {
        case e: Throwable => Future.failed(e)
      }
    }else{
      Future.failed(Stop(fail))
    }

  case class Stop(reason: String) extends Exception(s"stop execution:: $reason")
}
