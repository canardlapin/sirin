package sirin.probe.input

import scala.scalanative.unsafe.*

/** The existing pointer-only ABI in desktop/native/text_input.h and NativeWindow FFM. */
@extern
object NativeTextInput:
  def sirin_desktop_text_input(window: Ptr[Byte], mode: CInt, area: Ptr[CDouble]): CInt = extern
  def sirin_desktop_ime_control(window: Ptr[Byte], text: CString,
      start: CInt, length: CInt, commit: CInt): CInt = extern
  def sirin_desktop_activate(window: Ptr[Byte]): CInt = extern
  def sirin_desktop_keyboard_focus(window: Ptr[Byte]): CInt = extern

/** No application/window creation. Link reachability and invalid-owner rejection only. */
object NativeTextInputProbe:
  def main(args: Array[String]): Unit = Zone.acquire { implicit zone =>
    require(args.isEmpty)
    import NativeTextInput.*
    val area = alloc[CDouble](4)
    for index <- 0 until 4 do area(index) = 1.0
    for mode <- 0 to 2 do assert(sirin_desktop_text_input(null, mode, area) == 1)
    assert(sirin_desktop_ime_control(null, toCString("ABI probe"), 0, 0, 0) == 1)
    assert(sirin_desktop_activate(null) == 1)
    assert(sirin_desktop_keyboard_focus(null) == 0)
    println("Scala Native text/focus ABI: four shared entry points reached; invalid owners refused")
  }
