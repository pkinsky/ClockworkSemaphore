package utils

import scala.concurrent.{Future, ExecutionContext}
import scalaz.Applicative


object ApplicativeStuff {

  implicit def FutureApplicative(implicit executor: ExecutionContext) = new Applicative[Future] {
    def point[A](a: => A) = Future(a)
    def ap[A,B](fa: => Future[A])(f: => Future[A => B]) =
      (f zip fa) map { case (f1, a1) => f1(a1) }
  }

}