import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {
  val appName = "ClockworkSemaphore"
  val appVersion = "1.0"

  val scalaz = "org.scalaz" %% "scalaz-core" % "7.0.5" //functional programming voodoo
  val scalaz_concurrent = "org.scalaz" %% "scalaz-concurrent" % "7.0.5" //functional programming voodoo
  val scredis = "com.livestream" %% "scredis" % "1.1.0" //redis wrapper
  val scalacheck =  "org.scalacheck" %% "scalacheck" % "1.11.3" % "test"
  val bCrypt = "org.mindrot" % "jbcrypt" % "0.3m"


  val appDependencies = Seq(
    jdbc,
    cache,
    "commons-codec" % "commons-codec" % "1.7",
    scredis,
    scalaz,
    scalacheck,
    bCrypt
  )


  val main = play.Project(appName, appVersion, appDependencies).settings(
    scalaVersion := "2.10.2",
    javaOptions += "-d64 -Xms250M -Xmx4G -server -XX:MaxPermSize=2048M -XX:+CMSClassUnloadingEnabled -XX:+CMSPermGenSweepingEnabled",
      resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases/",
    resolvers += Resolver.url("sbt-plugin-snapshots", new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns)
  )



}
