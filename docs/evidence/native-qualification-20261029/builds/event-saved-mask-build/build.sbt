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
      val c                  = nativeConfig.value
      val (compile, linking) = vendorFlags((ThisBuild / vendor).value)
      c.withCompileOptions(c.compileOptions ++ compile)
        .withLinkingOptions(c.linkingOptions ++ linking)
    }
  )

/** Compile and link flags for wgpu-native and SDL3, per host platform (see native/fetch.sh). */
def vendorFlags(v: File): (Seq[String], Seq[String]) = {
  val os = sys.props("os.name").toLowerCase
  if (os.contains("mac")) {
    val wgpu = v / "wgpu-macos-aarch64"
    val sdl  = v / "SDL3.xcframework" / "macos-arm64_x86_64"
    (
      Seq(s"-I$wgpu/include", s"-F$sdl"),
      Seq(
        s"-L$wgpu/lib", "-lwgpu_native", s"-Wl,-rpath,$wgpu/lib",
        s"-F$sdl", "-framework", "SDL3", s"-Wl,-rpath,$sdl"
      )
    )
  } else if (os.contains("windows")) {
    val wgpu = v / "wgpu-windows-x86_64"
    val sdl  = v / "SDL3-3.4.18"
    // Import libraries only; the DLLs must be on PATH at run time.
    (
      Seq(s"-I$wgpu/include", s"-I$sdl/include"),
      Seq(s"$wgpu/lib/wgpu_native.dll.lib", s"$sdl/lib/x64/SDL3.lib")
    )
  } else {
    val wgpu = v / "wgpu-linux-x86_64"
    val sdl  = v / "sdl3-linux"
    (
      Seq(s"-I$wgpu/include", s"-I$sdl/include"),
      Seq(
        s"-L$wgpu/lib", "-lwgpu_native", s"-Wl,-rpath,$wgpu/lib",
        s"-L$sdl/lib", "-lSDL3", s"-Wl,-rpath,$sdl/lib"
      )
    )
  }
}

// A-0 authoring corpus (VISION §4.1): six programs that only need to
// type-check against the skeletal core API. JVM-only; not aggregated by root.
lazy val examples = project
  .in(file("examples"))
  .dependsOn(core.jvm)
  .settings(
    name                  := "sirin-examples",
    publish / skip        := true,
    Compile / scalaSource := baseDirectory.value / "authoring"
  )

// P0.6/P0.7: threads, GC, re-entry and foreign-thread entry. Multithreading is
// set explicitly. `-Dsirin.sanitize=thread` links the whole binary with
// ThreadSanitizer, and `-Dsirin.mode=release-fast` selects an optimized build
// (see docs/evidence/stage0/P0.6-P0.7-macos.md for both outcomes).
// `-Dsirin.gc=commix|boehm|none` selects the collector for the P0.6 GC comparison.
lazy val probeThreads = project
  .in(file("probes/threads"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "sirin-probe-threads",
    publish / skip := true,
    testDeps,
    nativeConfig := {
      val c   = nativeConfig.value
      val sdl = (ThisBuild / vendor).value / "SDL3.xcframework" / "macos-arm64_x86_64"
      val mode = sys.props.get("sirin.mode") match {
        case Some("release-fast") => scala.scalanative.build.Mode.releaseFast
        case Some("release-full") => scala.scalanative.build.Mode.releaseFull
        case _                    => c.mode
      }
      val gc = sys.props.get("sirin.gc") match {
        case Some("commix") => scala.scalanative.build.GC.commix
        case Some("boehm")  => scala.scalanative.build.GC.boehm
        case Some("none")   => scala.scalanative.build.GC.none
        case _              => c.gc
      }
      val base = c
        .withGC(gc)
        .withMode(mode)
        .withMultithreading(true)
        .withCompileOptions(c.compileOptions ++ Seq(s"-F$sdl"))
        .withLinkingOptions(
          c.linkingOptions ++ Seq(s"-F$sdl", "-framework", "SDL3", s"-Wl,-rpath,$sdl")
        )
      sys.props.get("sirin.sanitize") match {
        case Some("thread")  => base.withSanitizer(scala.scalanative.build.Sanitizer.ThreadSanitizer)
        case Some("address") => base.withSanitizer(scala.scalanative.build.Sanitizer.AddressSanitizer)
        case _               => base
      }
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

// Private evidence experiment only; original source and dependency cache preserved.
probeThreads / Compile / dependencyClasspath ~= { entries =>
  val replacement = file("/private/tmp/sirin-qualification-20261029/target/native-qualification-20261029/runtime/saved-mask-control.jar").getCanonicalFile
  require(replacement.isFile)
  require(entries.count(_.data.getName.startsWith("nativelib_native")) == 1)
  entries.map { entry =>
    if (entry.data.getName.startsWith("nativelib_native")) Attributed.blank(replacement)
    else entry
  }
}
