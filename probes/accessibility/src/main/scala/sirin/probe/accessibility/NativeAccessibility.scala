package sirin.probe.accessibility

import scala.scalanative.unsafe.*

/** The same pointer-only contract as desktop/native/accessibility.h and NativeWindow FFM. */
@extern
object NativeAccessibility:
  def sirin_desktop_ax_abi(): CInt = extern
  def sirin_desktop_ax_begin(window: Ptr[Byte], revision: CLongLong): CInt = extern
  def sirin_desktop_ax_field(window: Ptr[Byte], id: CLongLong, kind: CInt, label: CString,
      value: CString, help: CString, start: CInt, length: CInt, focused: CInt, enabled: CInt,
      bounds: Ptr[CDouble], spans: Ptr[CDouble], count: CInt): CInt = extern
  def sirin_desktop_ax_node(window: Ptr[Byte], id: CLongLong, parent: CLongLong, kind: CInt,
      label: CString, value: CString, help: CString, flags: CInt, checked: CInt, bounds: Ptr[CDouble]): CInt = extern
  def sirin_desktop_ax_link(window: Ptr[Byte], id: CLongLong, parent: CLongLong, flags: CInt): CInt = extern
  def sirin_desktop_ax_actions(window: Ptr[Byte], id: CLongLong, kinds: Ptr[CInt], lengths: Ptr[CInt],
      names: CString, count: CInt, bytes: CInt): CInt = extern
  def sirin_desktop_ax_relationships(window: Ptr[Byte], id: CLongLong, facts: Ptr[CLongLong]): CInt = extern
  def sirin_desktop_ax_publish(window: Ptr[Byte]): CInt = extern
  def sirin_desktop_ax_poll(window: Ptr[Byte], facts: Ptr[CLongLong], text: CString, capacity: CInt): CInt = extern

/** ABI reachability and invalid-owner rejection, without opening a window or entering Scala from native code. */
object NativeAccessibilityProbe:
  def main(args: Array[String]): Unit = Zone.acquire { implicit zone =>
    require(args.isEmpty)
    import NativeAccessibility.*
    val text = toCString("ABI probe")
    val bounds = alloc[CDouble](4)
    val facts = alloc[CLongLong](5)
    val kinds = alloc[CInt](1)
    val lengths = alloc[CInt](1)
    kinds(0) = 8
    lengths(0) = 9
    val output = alloc[CChar](65537)
    assert(sirin_desktop_ax_abi() == 4)
    assert(sirin_desktop_ax_begin(null, 17L) == 1)
    assert(sirin_desktop_ax_field(null, 1L, 0, text, text, text, 0, 0, 0, 1, bounds, null, 0) == 1)
    assert(sirin_desktop_ax_node(null, 2L, 0L, 3, text, text, text, 3, 1, bounds) == 1)
    assert(sirin_desktop_ax_link(null, 1L, 2L, 1) == 1)
    assert(sirin_desktop_ax_actions(null, 2L, kinds, lengths, text, 1, 9) == 1)
    assert(sirin_desktop_ax_relationships(null, 2L, facts) == 1)
    assert(sirin_desktop_ax_publish(null) == 1)
    assert(sirin_desktop_ax_poll(null, facts, output, 65537) == 0)
    println("Scala Native accessibility ABI v4: all nine shared entry points reached; invalid owners refused")
  }
