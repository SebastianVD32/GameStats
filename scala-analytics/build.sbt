ThisBuild / scalaVersion := "3.3.6"
ThisBuild / version := "0.1.0"

name := "gamestats-analytics"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % "0.14.10",
  "io.circe" %% "circe-generic" % "0.14.10",
  "io.circe" %% "circe-parser" % "0.14.10",
  "org.scalameta" %% "munit" % "1.0.4" % Test
)

Compile / mainClass := Some("gamestats.Main")
// Copy runtime dependencies for a JVM-only Docker image (no sbt at runtime).
lazy val stageRuntime = taskKey[Unit]("Stage the application and runtime dependencies")
stageRuntime := {
  val jar = (Compile / packageBin).value
  val dependencies = (Runtime / dependencyClasspath).value.map(_.data).filter(_.isFile)
  val destination = target.value / "runtime"
  IO.createDirectory(destination)
  (jar +: dependencies).foreach(file => IO.copyFile(file, destination / file.getName))
}
