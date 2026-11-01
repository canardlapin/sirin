import scala.sys.process._

ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val sdk = settingKey[File]("Exported local Sirin development SDK")
lazy val packageApp = taskKey[File]("Build the standalone macOS app image")

lazy val root = project.in(file(".")).settings(
  name := "reviewpad",
  sdk := file(sys.env.getOrElse("SIRIN_SDK", sys.error("Set SIRIN_SDK to the exported SDK directory"))),
  Compile / unmanagedJars += Attributed.blank(sdk.value / "sirin.jar"),
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.1" % Test,
  scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror"),
  Compile / mainClass := Some("reviewpad.ReviewPad"),
  packageApp := {
    val jar = (Compile / packageBin).value
    val command = Seq("python3", (baseDirectory.value / "package.py").toString,
      "--sdk", sdk.value.getAbsolutePath, "--jar", jar.getAbsolutePath,
      "--java-home", sys.props("java.home"))
    require(Process(command, baseDirectory.value).! == 0, "ReviewPad packaging failed")
    target.value / "package" / "SirinReviewPad.app"
  }
)
