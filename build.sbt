import scala.collection.Seq

ThisBuild / scalaVersion := "2.12.10"

ThisBuild / githubRepository := "quasar-destination-h2"

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
  .aggregate(h2, tableau)

lazy val h2 = project
  .in(file("h2"))
  .settings(publishTestsSettings)
  .settings(
    name := "quasar-destination-h2",
    libraryDependencies ++= Seq(
      "org.slf4s"    %% "slf4s-api" % "1.7.25",
      "org.tpolecat" %% "doobie-core" % DoobieVersion,
      "org.tpolecat" %% "doobie-hikari" % DoobieVersion,
      "org.tpolecat" %% "doobie-h2" % DoobieVersion,
      "com.h2database" % "h2" % "1.4.200",
      "com.precog" %% "quasar-connector" % managedVersions.value("precog-quasar"),
      "com.precog" %% "quasar-connector" % managedVersions.value("precog-quasar") % Test classifier "tests",
      "com.precog" %% "quasar-foundation" % managedVersions.value("precog-quasar") % Test classifier "tests",
      "com.github.tototoshi" %% "scala-csv" % "1.3.6" % Test,
      "org.slf4j" % "slf4j-log4j12" % "1.7.25" % Test,
      "org.specs2" %% "specs2-core" % SpecsVersion % Test,
      "org.specs2" %% "specs2-scalacheck" % SpecsVersion % Test,
      "org.specs2" %% "specs2-scalaz" % SpecsVersion % Test))

lazy val tableau = project
  .in(file("tableau"))
  .dependsOn(h2 % "compile->compile;test->test")
  .settings(
    name := "quasar-destination-tableau",

    quasarPluginName := "tableau",

    quasarPluginQuasarVersion := managedVersions.value("precog-quasar"),

    quasarPluginDestinationFqcn := Some("quasar.destination.tableau.TableauDestinationModule$"),

    /** Specify managed dependencies here instead of with `libraryDependencies`.
      * Do not include quasar libs, they will be included based on the value of
      * `quasarPluginQuasarVersion`.
      */
    quasarPluginDependencies ++= Seq())

  .enablePlugins(QuasarPlugin)
