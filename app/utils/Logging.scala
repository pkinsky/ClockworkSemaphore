package utils

import akka.event.slf4j.Logger


trait Logging {
  lazy val log = Logger(s"application.${this.getClass.getName}")
}
