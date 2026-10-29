// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

/** An immutable, composable description of structure (VISION §4). A plain
  * function returning a `View` needs no scope. Static children are
  * positional; changing structure is marked with `Switch` and `ForEach`.
  */
trait View:
  def padding(all: Lp): View
  def padding(horizontal: Lp, vertical: Lp): View
  def width(w: Lp): View
  def height(h: Lp): View

  /** Takes the remaining main-axis space in a `Row` or `Column`. */
  def grow: View
  def ref(r: NodeRef): View
  def semantics(s: Semantics): View
  def semantics(s: Signal[Semantics]): View

  /** Handles a chord while focus is inside this subtree. */
  def onKey(chord: Chord)(run: Action): View

  /** Dispatches `command` on a chord while focus is inside this subtree. */
  def shortcut(chord: Chord, command: Command[Unit, ?]): View

  /** Forces identity: a different key remounts this subtree. */
  def keyed[K](key: K): View

  /** Mounted but not visible: keeps state, schedules no animation frames. */
  def visible(shown: Signal[Boolean]): View

object View:
  def empty: View = ???

trait TextView extends View:
  def style(s: TextStyle): TextView
  def color(role: ColorRole): TextView

object Text:
  def apply(content: String): TextView         = ???
  def apply(content: Signal[String]): TextView = ???

trait TextFieldView extends View:
  def label(text: String): TextFieldView
  def placeholder(text: String): TextFieldView

  /** Shown when `Some`; also sets `invalid` and `describedBy` semantics. */
  def error(message: Signal[Option[String]]): TextFieldView
  def multiline: TextFieldView
  def onCommit(run: Action): TextFieldView
  def onBlur(run: Action): TextFieldView

object TextField:
  /** Edits `value`. IME composition stays inside the field until committed. */
  def bind(value: Cell[String]): TextFieldView = ???

trait ButtonView extends View:
  def primary: ButtonView

  /** Enablement, activation, and busy state all come from the command. */
  def command(c: Command[Unit, ?]): ButtonView
  def command[A](c: Command[A, ?], arg: Signal[A]): ButtonView

  /** A plain action, always enabled. */
  def onPress(run: Action): ButtonView

object Button:
  def apply(label: String): ButtonView         = ???
  def apply(label: Signal[String]): ButtonView = ???

  /** A button whose label also comes from the command. */
  def apply(command: Command[Unit, ?]): ButtonView = ???

object Checkbox:
  def bind(value: Cell[Boolean], label: String): View = ???

  /** A controlled checkbox: the owner decides what a toggle writes. */
  def apply(checked: Signal[Boolean], label: Signal[String])(onChange: Boolean => Action): View = ???

enum Alignment:
  case Start, Center, End, Baseline, Stretch

trait StackView extends View:
  def spacing(gap: Lp): StackView
  def align(cross: Alignment): StackView

object Column:
  def apply(children: View*): StackView = ???

object Row:
  def apply(children: View*): StackView = ???

object Spacer:
  def apply(): View         = ???
  def apply(size: Lp): View = ???

object Scroll:
  def apply(content: View): View = ???

/** Layers drawn over `base`, each sized to it. */
object Overlay:
  def apply(base: View, layers: View*): View = ???

enum Placement:
  case Below, Above, Start, End, Over

enum FocusRestore:
  /** The node focused when the popup opened, if it still exists and is focusable. */
  case Previous
  case To(target: NodeRef)
  case Nowhere

final case class Dismissal(onEscape: Boolean = true, onOutsidePress: Boolean = true)

/** A popup owned by its originator but placed in a window overlay layer
  * (VISION §5.2, §8). Placement, dismissal, focus restoration, and modality
  * are shared machinery. Dismissal writes `false` to `open`.
  */
object Popup:
  def apply(
      open: Cell[Boolean],
      anchor: NodeRef,
      placement: Placement = Placement.Below,
      initialFocus: Option[NodeRef] = None,
      restoreFocus: FocusRestore = FocusRestore.Previous,
      dismissal: Dismissal = Dismissal(),
      modal: Boolean = false
  )(content: View): View = ???

/** Keyed dynamic children. Identity is the key, never the index. The row
  * function re-runs when the value under a key changes; components it
  * places keep their state and receive the new value through `Inputs`.
  */
object ForEach:
  def apply[K, A](source: CollectionSource[K, A])(row: A => View): View      = ???
  def apply[K, A](items: Signal[Seq[A]], key: A => K)(row: A => View): View = ???

/** A dynamic region. `branch` re-runs when `value` changes; the result is
  * reconciled positionally against the previous one, so a component of the
  * same type at the same position keeps its state. Use `keyed` to force a
  * remount.
  */
object Switch:
  def apply[A](value: Signal[A])(branch: A => View): View = ???
