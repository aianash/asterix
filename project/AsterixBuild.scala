import sbt._
import sbt.Classpaths.publishTask
import Keys._

import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{ MultiJvm, extraOptions, jvmOptions, scalatestOptions, multiNodeExecuteTests, multiNodeJavaName, multiNodeHostsFileName, multiNodeTargetDirName, multiTestOptions }
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging

import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import sbtassembly.AssemblyPlugin.autoImport._

import org.apache.maven.artifact.handler.DefaultArtifactHandler

import com.typesafe.sbt.SbtNativePackager._, autoImport._
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd, CmdLike}

import com.goshoplane.sbt.standard.libraries.StandardLibraries


object AsterixBuild extends Build with StandardLibraries {

  lazy val makeScript = TaskKey[Unit]("make-script", "make script in local directory to run main classes")

  def sharedSettings = Seq(
    organization := "com.goshoplane",
    version := "0.1.0",
    scalaVersion := Version.scala,
    crossScalaVersions := Seq(Version.scala, "2.10.4"),
    scalacOptions := Seq("-unchecked", "-optimize", "-deprecation", "-feature", "-language:higherKinds", "-language:implicitConversions", "-language:postfixOps", "-language:reflectiveCalls", "-Yinline-warnings", "-encoding", "utf8"),
    retrieveManaged := true,

    fork := true,
    javaOptions += "-Xmx2500M",

    resolvers ++= StandardResolvers,

    publishMavenStyle := true
  ) ++ net.virtualvoid.sbt.graph.Plugin.graphSettings

  lazy val asterix = Project(
    id = "asterix",
    base = file("."),
    settings = Project.defaultSettings ++
      sharedSettings
  ) aggregate (core, crawler, processing)

  lazy val core = Project(
    id = "asterix-core",
    base = file("core"),
    settings = Project.defaultSettings ++ sharedSettings
  ).settings(
    name := "asterix-core"
  )

  lazy val crawler = Project(
    id = "asterix-crawler",
    base = file("crawler"),
    settings = Project.defaultSettings ++ sharedSettings
  ).enablePlugins(JavaAppPackaging)
  .settings(
    name := "asterix-crawler",

    libraryDependencies ++= Seq(
      "org.jsoup" % "jsoup" % "1.8.3"
    ) ++ Libs.fastutil
      ++ Libs.scallop
      ++ Libs.scalaz
      ++ Libs.playJson
      ++ Libs.akka
      ++ Libs.commonsCore,

    makeScript <<= (stage in Universal, stagingDirectory in Universal, baseDirectory in ThisBuild, streams) map { (_, dir, cwd, streams) =>
      var path = dir / "bin" / "asterix-crawler"
      sbt.Process(Seq("ln", "-sf", path.toString, "asterix-crawler"), cwd) ! streams.log
    }
  ) dependsOn(core)

  lazy val processing = Project(
    id = "asterix-processing",
    base = file("processing"),
    settings = Project.defaultSettings ++ sharedSettings
  ).enablePlugins(JavaAppPackaging)
  .settings(
    name := "asterix-processing",

    libraryDependencies ++= Seq(
      "org.jsoup" % "jsoup" % "1.8.3"
    ) ++ Libs.fastutil
      ++ Libs.scallop
      ++ Libs.scalaz
      ++ Libs.playJson
      ++ Libs.akka
      ++ Libs.commonsCatalogue
      ++ Libs.hemingway
      ++ Seq("com.goshoplane" %% "cassie-core" % "0.0.1")
      ++ Libs.microservice,

    makeScript <<= (stage in Universal, stagingDirectory in Universal, baseDirectory in ThisBuild, streams) map { (_, dir, cwd, streams) =>
      var path = dir / "bin" / "asterix-processing"
      sbt.Process(Seq("ln", "-sf", path.toString, "asterix-processing"), cwd) ! streams.log
    }
  ) dependsOn(core)

}
