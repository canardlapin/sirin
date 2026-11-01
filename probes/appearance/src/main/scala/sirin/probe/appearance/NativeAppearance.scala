package sirin.probe.appearance

import scala.scalanative.unsafe.*

/** Shared pointer-only declaration in desktop/native/appearance.h and NativeApplication FFM. */
@extern
object NativeAppearance:
  def sirin_desktop_system_theme(application: Ptr[Byte]): CInt = extern

object NativeAppearanceProbe:
  def main(args: Array[String]): Unit =
    require(args.isEmpty)
    assert(NativeAppearance.sirin_desktop_system_theme(null) == -1)
    println("Scala Native appearance ABI: shared entry point reached; missing owner refused")
