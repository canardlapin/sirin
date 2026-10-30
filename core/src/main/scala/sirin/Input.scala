// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

/** A logical key. Physical keys and text input are separate channels (VISION §6). */
enum Key:
  case Character(c: Char)
  case Enter,
    Escape,
    Tab,
    Space,
    Backspace,
    Delete
  case ArrowUp,
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    Home,
    End,
    PageUp,
    PageDown

opaque type Mods = Int

object Mods:
  val none: Mods = 0

  /** Command on macOS, Control elsewhere. */
  val Primary: Mods = 1
  val Shift: Mods = 2
  val Alt: Mods = 4

  /** Physical Control; distinct from Command/Primary on macOS. */
  val Control: Mods = 8
  extension (m: Mods)
    def |(o: Mods): Mods = m | o
    def has(o: Mods): Boolean = (m & o) == o
    def isEmpty: Boolean = m == 0

final case class Chord(key: Key, mods: Mods = Mods.none)

object Chord:
  def primary(c: Char): Chord = Chord(Key.Character(c), Mods.Primary)
  def primaryShift(c: Char): Chord = Chord(Key.Character(c), Mods.Primary | Mods.Shift)

final case class KeyEvent(key: Key, mods: Mods, repeat: Boolean)

enum PointerPhase:
  case Down,
    Move,
    Up,
    Cancel

enum PointerButton:
  case Primary,
    Secondary,
    Middle

/** A routed pointer event. `hit` comes from the interaction snapshot the event was routed against; `captured` is true
  * while this element holds capture, in which case `hit` may be `Miss`.
  */
final case class PointerEvent(
    phase: PointerPhase,
    position: Point,
    button: PointerButton,
    mods: Mods,
    clicks: Int,
    hit: Hit,
    captured: Boolean
)
