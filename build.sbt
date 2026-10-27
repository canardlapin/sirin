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

// Stage 0 probes link pinned prebuilt native artifacts from native/vendor/
// (fetch with native/fetch-macos.sh). They are not aggregated by root.
lazy val vendor = settingKey[File]("Pinned native artifact directory")
ThisBuild / vendor := (ThisBuild / baseDirectory).value / "native" / "vendor"

lazy val probeWindow = project
  .in(file("probes/window"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(core.native)
  .settings(
    name := "sirin-probe-window",
    publish / skip := true,
    testDeps,
    nativeConfig := {
      val c    = nativeConfig.value
      val v    = (ThisBuild / vendor).value
      val wgpu = v / "wgpu-macos-aarch64"
      val sdl  = v / "SDL3.xcframework" / "macos-arm64_x86_64"
      c.withCompileOptions(c.compileOptions ++ Seq(s"-I$wgpu/include", s"-F$sdl"))
        .withLinkingOptions(
          c.linkingOptions ++ Seq(
            s"-L$wgpu/lib", "-lwgpu_native", s"-Wl,-rpath,$wgpu/lib",
            s"-F$sdl", "-framework", "SDL3", s"-Wl,-rpath,$sdl"
          )
        )
    }
  )

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.native, engine.jvm, engine.native, testkit.jvm, testkit.native)
  .settings(publish / skip := true)

// Named per-platform test commands; CI calls these rather than relying on `+test`.
addCommandAlias("testJVM", "; coreJVM/test; engineJVM/test; testkitJVM/test")
addCommandAlias("testNative", "; coreNative/test; engineNative/test; testkitNative/test")
addCommandAlias("testAll", "; testJVM; testNative")
