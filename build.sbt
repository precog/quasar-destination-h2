import scala.collection.Seq

ThisBuild / scalaVersion := "2.12.11"

ThisBuild / githubRepository := "quasar-destination-h2"

ThisBuild / githubWorkflowOSes += "windows-latest"

performMavenCentralSync in ThisBuild := false   // basically just ignores all the sonatype sync parts of things

publishAsOSSProject in ThisBuild := true

homepage in ThisBuild := Some(url("https://github.com/precog/quasar-destination-h2"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/precog/quasar-destination-h2"),
  "scm:git@github.com:precog/quasar-destination-h2.git"))

val DoobieVersion = "0.8.8"
val SpecsVersion = "4.9.2"

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  publishArtifact in (Test, packageBin) := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "quasar-destination-h2",

    quasarPluginName := "h2",

    quasarPluginQuasarVersion := managedVersions.value("precog-quasar"),

    quasarPluginDestinationFqcn := Some("quasar.destination.h2.H2DestinationModule$"),

    /** Specify managed dependencies here instead of with `libraryDependencies`.
      * Do not include quasar libs, they will be included based on the value of
      * `quasarPluginQuasarVersion`.
      */
    quasarPluginDependencies ++= Seq(
      "org.slf4s"    %% "slf4s-api" % "1.7.25",
      "org.tpolecat" %% "doobie-core" % DoobieVersion,
      "org.tpolecat" %% "doobie-hikari" % DoobieVersion,
      "org.tpolecat" %% "doobie-h2" % DoobieVersion,
      "com.h2database" % "h2" % "1.4.200"),

    libraryDependencies ++= Seq(
      "com.precog" %% "quasar-connector" % managedVersions.value("precog-quasar") % Test classifier "tests",
      "com.precog" %% "quasar-foundation" % managedVersions.value("precog-quasar") % Test classifier "tests",
      "com.github.tototoshi" %% "scala-csv" % "1.3.6" % Test,
      "org.slf4j" % "slf4j-log4j12" % "1.7.25" % Test,
      "org.specs2" %% "specs2-core" % SpecsVersion % Test,
      "org.specs2" %% "specs2-scalacheck" % SpecsVersion % Test,
      "org.specs2" %% "specs2-scalaz" % SpecsVersion % Test))

  .enablePlugins(QuasarPlugin)
