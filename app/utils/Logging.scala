package utils

import akka.event.slf4j.Logger

/**
 * Created by paul on 3/26/14.
 */
trait Logging {
  lazy val log = Logger(s"application.${this.getClass.getName}")
}
