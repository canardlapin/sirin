import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version      := "0.0.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")

val munitV      = "1.2.1"
val munitCheckV = "1.3.0"

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit"            % munitV      % Test,
    "org.scalameta" %%% "munit-scalacheck" % munitCheckV % Test
  )
)

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(name := "sirin-core", testDeps)

lazy val engine = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("engine"))
  .dependsOn(core)
  .settings(name := "sirin-engine", testDeps)

lazy val testkit = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("testkit"))
  .dependsOn(engine)
  .settings(name := "sirin-testkit", testDeps)

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.native, engine.jvm, engine.native, testkit.jvm, testkit.native)
  .settings(publish / skip := true)

// Named per-platform test commands; CI calls these rather than relying on `+test`.
addCommandAlias("testJVM", "; coreJVM/test; engineJVM/test; testkitJVM/test")
addCommandAlias("testNative", "; coreNative/test; engineNative/test; testkitNative/test")
addCommandAlias("testAll", "; testJVM; testNative")
