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

// Standalone drawing values and recordings: no UI runtime or native library dependency.
lazy val graphics = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("graphics"))
  .settings(name := "sirin-graphics", testDeps)

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(name := "sirin-core", testDeps)

// Pure text adapter; both foundational APIs retain their independent graphs.
lazy val textUI = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("text-ui"))
  .dependsOn(core, graphics)
  .settings(name := "sirin-text-ui", testDeps)

lazy val graphicsUI = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("graphics-ui"))
  .dependsOn(core, graphics, textUI)
  .settings(name := "sirin-graphics-ui", testDeps)

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

// Provisional real font service, explicitly tested after fetching --text.
// Kept out of root aggregation, like the existing artifact-dependent probes.
lazy val verifyFontFixtures = taskKey[Unit]("Verify pinned font fixture hashes")
lazy val verifyUnicodeFixtures = taskKey[Unit]("Verify pinned Unicode conformance fixtures")
lazy val probeUnicode = project
  .in(file("probes/unicode"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(graphics.native)
  .settings(
    name := "sirin-probe-unicode",
    publish / skip := true,
    testDeps,
    verifyUnicodeFixtures := {
      val dir = baseDirectory.value / "src" / "test" / "resources" / "unicode"
      IO.readLines(dir / "SHA256SUMS").filter(_.nonEmpty).foreach { line =>
        val parts = line.split("\\s+", 2)
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(IO.readBytes(dir / parts(1)))
          .map(b => f"${b & 0xff}%02x").mkString
        require(actual == parts(0), s"Unicode fixture checksum mismatch: ${parts(1)}")
      }
    },
    Test / test := ((Test / test) dependsOn verifyUnicodeFixtures).value,
    nativeConfig := {
      val c = nativeConfig.value
      val unicode = (ThisBuild / vendor).value / "unicode"
      val headers = unicode / "icu4x-031d9e89f6a3fd26a14491e090729d46094328fd" / "ffi" / "capi" / "bindings" / "c"
      // ICU4X's Time.h must not shadow system <time.h> on macOS.
      c.withCompileOptions(c.compileOptions ++ Seq("-iquote", headers.toString))
        .withLinkingOptions(c.linkingOptions ++ Seq(s"$unicode/libicu4x_probe.a"))
    }
  )
lazy val textNative = project
  .in(file("text-native"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(graphics.native)
  .settings(
    name := "sirin-text-native",
    testDeps,
    verifyFontFixtures := {
      val dir = (ThisBuild / baseDirectory).value / "graphics" / "src" / "test" / "resources" / "fonts"
      IO.readLines(dir / "SHA256SUMS").filter(_.nonEmpty).foreach { line =>
        val parts = line.split("\\s+", 2)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(IO.readBytes(dir / parts(1)))
        val actual = digest.map(b => f"${b & 0xff}%02x").mkString
        require(actual == parts(0), s"font fixture checksum mismatch: ${parts(1)}")
      }
    },
    Test / test := ((Test / test) dependsOn verifyFontFixtures).value,
    nativeConfig := {
      val c = nativeConfig.value
      val text = (ThisBuild / vendor).value / "text"
      val cpp = if (sys.props("os.name").toLowerCase.contains("mac")) "-lc++" else "-lstdc++"
      c.withCompileOptions(c.compileOptions ++ Seq(s"-I$text/include/harfbuzz", s"-I$text/include/freetype2"))
        .withLinkingOptions(c.linkingOptions ++ Seq(s"$text/lib/libharfbuzz.a", s"$text/lib/libfreetype.a", cpp))
    }
  )

// Isolated integration of the Unicode and C/C++ font components. One Rust
// static library only; never co-linked with the native UI host.
lazy val probeParagraph = project
  .in(file("probes/paragraph"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(textNative, probeUnicode, textUI.native, graphicsUI.native)
  .settings(
    name := "sirin-probe-paragraph",
    publish / skip := true,
    testDeps,
    Test / test := ((Test / test) dependsOn (textNative / verifyFontFixtures, probeUnicode / verifyUnicodeFixtures)).value,
    nativeConfig := {
      val c = nativeConfig.value
      val artifacts = (ThisBuild / vendor).value
      val text = artifacts / "text"
      val unicode = artifacts / "unicode"
      val headers = unicode / "icu4x-031d9e89f6a3fd26a14491e090729d46094328fd" / "ffi" / "capi" / "bindings" / "c"
      c.withCompileOptions(c.compileOptions ++ Seq(s"-I$text/include/harfbuzz", s"-I$text/include/freetype2", "-iquote", headers.toString))
        .withLinkingOptions(c.linkingOptions ++ Seq(s"$text/lib/libharfbuzz.a", s"$text/lib/libfreetype.a", "-lc++", s"$unicode/libicu4x_probe.a"))
    }
  )

lazy val probeRender = project
  .in(file("probes/render"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(graphics.native, textNative)
  .settings(
    name := "sirin-probe-render", publish / skip := true, testDeps,
    Test / parallelExecution := false,
    Test / test := ((Test / test) dependsOn (textNative / verifyFontFixtures)).value,
    nativeConfig := {
      val c = nativeConfig.value
      val v = (ThisBuild / vendor).value
      val w = v / "wgpu-macos-aarch64"
      val t = v / "text"
      val thor = v / "thorvg" / "install"
      c.withCompileOptions(c.compileOptions ++ Seq(s"-I$w/include", s"-I$thor/include/thorvg-1", s"-I$t/include/harfbuzz", s"-I$t/include/freetype2"))
        .withLinkingOptions(c.linkingOptions ++ Seq(s"-L$w/lib", "-lwgpu_native", s"-Wl,-rpath,$w/lib", s"$thor/lib/libthorvg-1.a", s"$t/lib/libharfbuzz.a", s"$t/lib/libfreetype.a", "-lc++"))
    }
  )

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

// A-0b authoring corpus (VISION §4.1): twelve programs that only need to
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
  .aggregate(graphics.jvm, graphics.native, core.jvm, core.native, textUI.jvm, textUI.native, graphicsUI.jvm, graphicsUI.native, engine.jvm, engine.native, testkit.jvm, testkit.native)
  .settings(publish / skip := true)

// Named per-platform test commands; CI calls these rather than relying on `+test`.
addCommandAlias("testJVM", "; graphicsJVM/test; coreJVM/test; textUIJVM/test; graphicsUIJVM/test; engineJVM/test; testkitJVM/test; workbenchJVM/test; hostJVM/test")
addCommandAlias("testNative", "; graphicsNative/test; coreNative/test; textUINative/test; graphicsUINative/test; engineNative/test; testkitNative/test; workbenchNative/test; hostNative/test")
addCommandAlias("testAll", "; testJVM; testNative")

// Executable public-API behaviour specimen; no desktop renderer required.
lazy val workbench = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("specimens/workbench"))
  .dependsOn(engine)
  .settings(name := "sirin-workbench-specimen", publish / skip := true, testDeps)

// Competent JavaFX-backed authoring baseline; compile explicitly with JDK 25+.
lazy val scalafxWorkbench = project
  .in(file("specimens/scalafx-workbench"))
  .settings(
    name := "sirin-scalafx-comparison",
    publish / skip := true,
    libraryDependencies += "org.scalafx" %% "scalafx" % "26.0.0-R38"
  )

// Bounded JDK25 FFM development host. Deliberately not root-aggregated: invoke
// with sbt --java-home <jdk25> desktopJVM/test or desktopJVM/run.
lazy val desktopNativeLibrary = taskKey[File]("Build the pointer-only desktop transport")
lazy val desktopJVM = project.in(file("desktop"))
  .dependsOn(host.jvm, workbench.jvm)
  .settings(
    name := "sirin-desktop-development", publish / skip := true, testDeps,
    Compile / unmanagedSources ++= {
      val repo = (ThisBuild / baseDirectory).value
      Seq(repo / "probes/paragraph/src/main/scala/sirin/graphics/text/NativeParagraph.scala",
        repo / "probes/paragraph/src/main/scala/sirin/graphics/text/NativeTextServices.scala",
        repo / "probes/render/src/main/scala/sirin/render/PixelProgram.scala",
        repo / "probes/render/src/main/scala/sirin/render/BatchProgram.scala")
    },
    Compile / resourceGenerators += Def.task {
      val output = (Compile / resourceManaged).value / "raster.wgsl"
      IO.copyFile((ThisBuild / baseDirectory).value / "probes/render/src/main/resources/raster.wgsl", output)
      val batch = (Compile / resourceManaged).value / "batch.wgsl"
      IO.copyFile((ThisBuild / baseDirectory).value / "probes/render/src/main/resources/batch.wgsl", batch)
      Seq(output,batch)
    }.taskValue,
    desktopNativeLibrary := {
      import scala.sys.process._
      val rootDir = (ThisBuild / baseDirectory).value
      val output = target.value / "native" / "libsirin_desktop.dylib"
      IO.createDirectory(output.getParentFile)
      require(sys.props("os.name").toLowerCase.contains("mac"), "bounded desktop transport currently requires macOS")
      val (compile, link) = vendorFlags((ThisBuild / vendor).value)
      val v = (ThisBuild / vendor).value
      val text = v / "text"
      val unicode = v / "unicode"
      val headers = unicode / "icu4x-031d9e89f6a3fd26a14491e090729d46094328fd/ffi/capi/bindings/c"
      val thor = v / "thorvg/install"
      val command = Seq("clang", "-std=c11", "-Wall", "-Wextra", "-Werror", "-dynamiclib", "-O2") ++ compile ++
        Seq(s"-I$text/include/harfbuzz", s"-I$text/include/freetype2", "-iquote", headers.toString,
          s"-I$thor/include/thorvg-1", (baseDirectory.value / "native" / "sirin_desktop.c").toString, "-o", output.toString) ++ link ++
        Seq(s"$text/lib/libharfbuzz.a", s"$text/lib/libfreetype.a", s"$unicode/libicu4x_probe.a", s"$thor/lib/libthorvg-1.a", "-lc++")
      require(Process(command, rootDir).! == 0, "desktop transport compilation failed")
      output
    },
    Compile / compile := ((Compile / compile) dependsOn desktopNativeLibrary).value,
    Compile / run / fork := true,
    Compile / run / javaOptions ++= Seq("-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED",
      "-Dsirin.repo=" + (ThisBuild / baseDirectory).value.getAbsolutePath,
      "-Dsirin.desktop.library=" + (target.value / "native" / "libsirin_desktop.dylib").getAbsolutePath),
    Test / fork := true,
    Test / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED",
      "-Dsirin.desktop.library=" + (target.value / "native" / "libsirin_desktop.dylib").getAbsolutePath)
  )

// Backend-independent committed tree capture and bounded layout/recording nucleus.
lazy val host = crossProject(JVMPlatform, NativePlatform).crossType(CrossType.Pure)
  .in(file("host")).dependsOn(engine, graphicsUI)
  .settings(name := "sirin-host", testDeps)

// Identifiable local macOS development app. Its native vendor paths are local;
// this is an app-image acceptance target, not a redistributable installer.
lazy val desktopPackage = taskKey[File]("Package the bounded JVM workbench app-image")
desktopJVM / desktopPackage := {
  import scala.sys.process._
  val repo = (ThisBuild / baseDirectory).value
  val output = (desktopJVM / target).value / "package"
  val input = (desktopJVM / target).value / "package-input"
  val classes = (desktopJVM / target).value / "package-classes"
  val compiled = (desktopJVM / Compile / packageBin).value
  val classpath = (desktopJVM / Runtime / fullClasspath).value.map(_.data)
  IO.delete(input); IO.delete(classes); IO.delete(output)
  IO.createDirectory(input); IO.createDirectory(classes)
  classpath.foreach { file =>
    if (file.isDirectory) IO.copyDirectory(file,classes)
    else if (file.getName.endsWith(".jar")) IO.copyFile(file,input / file.getName)
  }
  val home = file(sys.props("java.home"))
  require(Process(Seq((home / "bin/jar").toString,"--create","--file",(input / "sirin.jar").toString,"-C",classes.toString,"."),repo).! == 0)
  IO.copyFile((desktopJVM / desktopNativeLibrary).value,input / "libsirin_desktop.dylib")
  val command = Seq((home / "bin/jpackage").toString,"--type","app-image","--name","Sirin",
    "--dest",output.toString,"--input",input.toString,"--main-jar","sirin.jar",
    "--main-class","sirin.desktop.DesktopWorkbench","--runtime-image",home.toString,
    "--mac-package-identifier","io.github.canardlapin.sirin.dev",
    "--java-options","-XstartOnFirstThread","--java-options","--enable-native-access=ALL-UNNAMED",
    "--java-options","-Dsirin.desktop.library=$APPDIR/libsirin_desktop.dylib",
    "--java-options","-Dsirin.repo=" + repo.toString,"--arguments","interactive")
  require(compiled.exists && Process(command,repo).! == 0,"desktop package failed")
  output / "Sirin.app"
}
