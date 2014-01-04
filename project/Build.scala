import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  val appName = "ClockworkSemaphore"
  val appVersion = "1.0"

  val appDependencies = Seq(
    // Add your project dependencies here,
    jdbc,
    cache,
    "org.specs2" %% "specs2" % "1.14" % "test",
    "commons-codec" % "commons-codec" % "1.7",
    "com.typesafe.akka" %% "akka-testkit" % "2.1.0",
    "com.livestream" %% "scredis" % "1.0.1",
    "org.scalaz" %% "scalaz-core" % "7.0.5",
	"securesocial" %% "securesocial" % "master-SNAPSHOT"	
  )




  val main = play.Project(appName, appVersion, appDependencies).settings(
    scalaVersion := "2.10.2",
    javaOptions in (Test,run) += "-d64 -Xms250M -Xmx4G -server",
      resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases/",
    resolvers += Resolver.url("sbt-plugin-snapshots", new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns)
  )



}
