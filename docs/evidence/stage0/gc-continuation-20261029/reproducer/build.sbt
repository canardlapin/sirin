import scala.scalanative.build.{GC, Mode}

enablePlugins(ScalaNativePlugin)
name := "gc-repro"
scalaVersion := "3.7.4"
scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")
publish / skip := true
nativeConfig := {
  val mode = sys.props.getOrElse("repro.mode", "release-fast") match {
    case "debug" => Mode.debug
    case "release-fast" => Mode.releaseFast
    case other => sys.error(s"Unknown repro.mode: $other")
  }
  val gc = sys.props.getOrElse("repro.gc", "immix") match {
    case "immix" => GC.immix
    case "commix" => GC.commix
    case other => sys.error(s"Unknown repro.gc: $other")
  }
  nativeConfig.value.withMode(mode).withGC(gc).withMultithreading(true)
}

// Opt-in, isolated runtime experiments; the normal dependency remains pinned.
Compile / dependencyClasspath ~= { entries =>
  sys.props.get("repro.runtimeJar") match {
    case None => entries
    case Some(path) =>
      val replacement = file(path).getCanonicalFile
      require(replacement.isFile, s"Missing runtime JAR: $replacement")
      val matches = entries.count(_.data.getName.startsWith("nativelib_native"))
      require(matches == 1, s"Expected one nativelib dependency, found $matches")
      entries.map { entry =>
        if (entry.data.getName.startsWith("nativelib_native")) Attributed.blank(replacement)
        else entry
      }
  }
}
