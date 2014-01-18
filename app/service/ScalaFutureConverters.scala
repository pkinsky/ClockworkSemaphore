package service

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scalaz.{\/-, -\/}
import scalaz.concurrent.Task

import  scalaz._
import  Scalaz.ToIdOps

import scala.concurrent.ExecutionContext

//https://stackoverflow.com/questions/16895635/convert-scala-2-10-future-to-scalaz-concurrent-future-task
object ScalaFutureConverters {


  implicit def scalaFuture2scalazTask[T](fut: Future[T])(implicit ec: ExecutionContext): Task[T] = {
    Task.async {
      register =>
        fut.onComplete {
          case Success(v) => register(v.right)
          case Failure(ex) => register(ex.left)
        }
    }
  }


  implicit def scalazTask2scalaFuture[T](task: Task[T]): Future[T] = {
    val p: Promise[T] = Promise()

    task.runAsync {
      case -\/(ex) => p.failure(ex)
      case \/-(r) => p.success(r)
    }

    p.future
  }


  implicit class ScalazFutureEnhancer[T](task: Task[T]) {
    def asFuture: Future[T] = scalazTask2scalaFuture(task)
  }


  implicit def scalaF2EnhancedScalaF[T](fut: Future[T])(implicit ec: ExecutionContext): ScalaFEnhancer[T] =
    ScalaFEnhancer(fut)(ec)

  case class ScalaFEnhancer[T](fut: Future[T])(implicit ec: ExecutionContext) {
    def asTask: Task[T] = scalaFuture2scalazTask(fut)(ec)
  }

}