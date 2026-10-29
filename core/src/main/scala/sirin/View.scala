// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

/** An immutable, composable description of structure (VISION §4). A plain
  * function returning a `View` needs no scope. Static children are
  * positional; changing structure is marked with `Switch` and `ForEach`.
  */
trait View

/** A real reconstruction capability, not a cast to an arbitrary custom subtype. */
trait ViewRebuild[V <: View]:
  def apply(view: V, decoration: Decoration): V

object ViewRebuild:
  given ViewRebuild[View] = (view, decoration) => View.Decorated(view, decoration)
  given ViewRebuild[TextView] = (view, decoration) => View.TextDecoration(view, decoration)
  given ViewRebuild[TextFieldView] = (view, decoration) => View.FieldDecoration(view, decoration)
  given ViewRebuild[ButtonView] = (view, decoration) => View.ButtonDecoration(view, decoration)
  given ViewRebuild[StackView] = (view, decoration) => View.StackDecoration(view, decoration)

enum Decoration:
  case Padding(horizontal: Lp, vertical: Lp)
  case Width(value: Lp)
  case Height(value: Lp)
  case Grow
  case Ref(value: NodeRef)
  case Semantic(value: Signal[Semantics])
  case KeyHandler(chord: Chord, run: UiTxn => Unit)
  case Shortcut(chord: Chord, command: Command[Unit, ?])
  case Keyed(key: Any)
  case Visible(value: Signal[Boolean])
  case Activate[A](command: Command[A, ?], arg: Signal[A], role: Role)
  case Opacity(value: Presentation[Double])
  case Offset(value: Presentation[Point])

extension [V <: View](view: V)(using rebuild: ViewRebuild[V])
  def padding(all: Lp): V = rebuild(view, Decoration.Padding(all, all))
  def padding(horizontal: Lp, vertical: Lp): V = rebuild(view, Decoration.Padding(horizontal, vertical))
  def width(w: Lp): V = rebuild(view, Decoration.Width(w))
  def height(h: Lp): V = rebuild(view, Decoration.Height(h))
  def grow: V = rebuild(view, Decoration.Grow)
  def ref(r: NodeRef): V = rebuild(view, Decoration.Ref(r))
  def semantics(s: Semantics): V = semantics(Signal.const(s))
  def semantics(s: Signal[Semantics]): V = rebuild(view, Decoration.Semantic(s))
  def onKey(chord: Chord)(run: Action): V = rebuild(view, Decoration.KeyHandler(chord, txn => run(using txn)))
  def shortcut(chord: Chord, command: Command[Unit, ?]): V = rebuild(view, Decoration.Shortcut(chord, command))
  def keyed[K](key: K): V = rebuild(view, Decoration.Keyed(key))
  def visible(shown: Signal[Boolean]): V = rebuild(view, Decoration.Visible(shown))
  def activate[A](command: Command[A, ?], arg: Signal[A], role: Role): V = rebuild(view, Decoration.Activate(command, arg, role))
  def opacity(value: Presentation[Double]): V = rebuild(view, Decoration.Opacity(value))
  /** Coherent drawing, hit-test and accessibility transform. */
  def offset(value: Presentation[Point]): V = rebuild(view, Decoration.Offset(value))

object View:
  private[sirin] case object Empty extends View
  private[sirin] final case class Decorated(view: View, decoration: Decoration) extends View
  private[sirin] final case class Stack(children: Vector[View], horizontal: Boolean,
      gap: Option[Lp] = None, alignment: Option[Alignment] = None) extends StackView:
    def spacing(value: Lp): StackView = copy(gap = Some(value))
    def align(value: Alignment): StackView = copy(alignment = Some(value))
  private[sirin] final case class StackDecoration(view: StackView, decoration: Decoration) extends StackView:
    def spacing(value: Lp): StackView = copy(view = view.spacing(value))
    def align(value: Alignment): StackView = copy(view = view.align(value))
  private[sirin] final case class TextDecoration(view: TextView, decoration: Decoration) extends TextView:
    def style(value: TextStyle): TextView = copy(view = view.style(value))
    def color(value: ColorRole): TextView = copy(view = view.color(value))
  private[sirin] final case class ButtonDecoration(view: ButtonView, decoration: Decoration) extends ButtonView:
    def primary: ButtonView = copy(view = view.primary)
    def command(c: Command[Unit, ?]): ButtonView = copy(view = view.command(c))
    def command[A](c: Command[A, ?], arg: Signal[A]): ButtonView = copy(view = view.command(c,arg))
    def onPress(run: Action): ButtonView = copy(view = view.onPress(run))
  private[sirin] final case class FieldDecoration(view: TextFieldView, decoration: Decoration) extends TextFieldView:
    def label(text: String): TextFieldView = copy(view=view.label(text))
    def placeholder(text: String): TextFieldView = copy(view=view.placeholder(text))
    def error(message: Signal[Option[String]]): TextFieldView = copy(view=view.error(message))
    def multiline: TextFieldView = copy(view=view.multiline)
    def onCommit(run: Action): TextFieldView = copy(view=view.onCommit(run))
    def onBlur(run: Action): TextFieldView = copy(view=view.onBlur(run))
  private[sirin] final case class Space(size: Option[Lp]) extends View
  private[sirin] final case class ScrollContent(content: View) extends View
  def empty: View = Empty

trait TextView extends View:
  def style(s: TextStyle): TextView
  def color(role: ColorRole): TextView

object Text:
  private[sirin] final case class Description(content: Signal[String], textStyle: TextStyle = TextStyle.Body,
      colorRole: ColorRole = ColorRole.OnSurface) extends TextView:
    def style(value: TextStyle): TextView = copy(textStyle = value)
    def color(value: ColorRole): TextView = copy(colorRole = value)
  def apply(content: String): TextView = Description(Signal.const(content))
  def apply(content: Signal[String]): TextView = Description(content)

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
  private[sirin] final case class Description(value: Cell[String],fieldLabel: String="",hint: String="",
      problem: Signal[Option[String]]=Signal.const(None),multiple: Boolean=false,
      commit: UiTxn => Unit = _ => (),blur: UiTxn => Unit = _ => ()) extends TextFieldView:
    def label(text: String): TextFieldView = copy(fieldLabel=text)
    def placeholder(text: String): TextFieldView = copy(hint=text)
    def error(message: Signal[Option[String]]): TextFieldView = copy(problem=message)
    def multiline: TextFieldView = copy(multiple=true)
    def onCommit(run: Action): TextFieldView = copy(commit=txn => run(using txn))
    def onBlur(run: Action): TextFieldView = copy(blur=txn => run(using txn))
  def bind(value: Cell[String]): TextFieldView = Description(value)

trait ButtonView extends View:
  def primary: ButtonView

  /** Enablement, activation, and busy state all come from the command. */
  def command(c: Command[Unit, ?]): ButtonView
  def command[A](c: Command[A, ?], arg: Signal[A]): ButtonView

  /** A plain action, always enabled. */
  def onPress(run: Action): ButtonView

object Button:
  private[sirin] sealed trait Binding:
    def enabled: Signal[Boolean]
    def running: Signal[Int]
    def invoke()(using UiTxn): Unit
  private[sirin] case object Inert extends Binding:
    val enabled=Signal.const(false)
    val running=Signal.const(0)
    def invoke()(using UiTxn): Unit = ()
  private[sirin] final case class Plain(run: UiTxn => Unit) extends Binding:
    val enabled=Signal.always
    val running=Signal.const(0)
    def invoke()(using txn: UiTxn): Unit = run(txn)
  private[sirin] final case class Bound[A](command: Command[A,?], arg: Signal[A]) extends Binding:
    val enabled=command.enabledFor(arg)
    def running: Signal[Int] = command.running
    def invoke()(using UiTxn): Unit = { command.dispatch(arg.get); () }
  private[sirin] final case class Description(label: Signal[String],isPrimary: Boolean=false,binding: Binding=Inert) extends ButtonView:
    def primary: ButtonView = copy(isPrimary=true)
    def command(c: Command[Unit,?]): ButtonView = command(c,Signal.const(()))
    def command[A](c: Command[A,?],arg: Signal[A]): ButtonView = copy(binding=Bound(c,arg))
    def onPress(run: Action): ButtonView = copy(binding=Plain(txn => run(using txn)))
  def apply(label: String): ButtonView = Description(Signal.const(label))
  def apply(label: Signal[String]): ButtonView = Description(label)
  def apply(command: Command[Unit,?]): ButtonView = apply(command.label).command(command)

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
  def apply(children: View*): StackView = View.Stack(children.toVector, horizontal = false)

object Row:
  def apply(children: View*): StackView = View.Stack(children.toVector, horizontal = true)

object Spacer:
  def apply(): View = View.Space(None)
  def apply(size: Lp): View =
    require(size.toDouble.isFinite && size.toDouble >= 0, "spacer size must be finite and nonnegative")
    View.Space(Some(size))

object Scroll:
  def apply(content: View): View = View.ScrollContent(content)

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

enum DismissReason:
  case Escape, OutsidePress, WindowDeactivated

enum DismissDecision:
  case Allow, Veto

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
      modal: Boolean = false,
      onDismiss: DismissReason => UiTxn ?=> DismissDecision = (_: DismissReason) => DismissDecision.Allow
  )(content: View): View = ???

/** Row builder runs once per mounted key, receiving live Inputs. */
object ForEach:
  private[sirin] final case class Description[K, A](items: Signal[Seq[A]], key: A => K,
      row: Inputs[A] => View, equality: Equality[A], schema: String) extends View
  def apply[K, A](source: CollectionSource[K, A])(row: Inputs[A] => View)(using Equality[A], SchemaId[K], SchemaId[A]): View =
    Description(source.keyedSnapshot, (pair: (K, A)) => pair._1,
      (inputs: Inputs[(K, A)]) => row(Inputs.from(inputs.map(_._2))),
      (a, b) => a._1 == b._1 && summon[Equality[A]].same(a._2, b._2), s"source:${summon[SchemaId[K]].value}:${summon[SchemaId[A]].value}")
  /** Explicit O(n) reconciliation for small sequences. */
  def apply[K, A](items: Signal[Seq[A]], key: A => K)(row: Inputs[A] => View)(using Equality[A], SchemaId[K], SchemaId[A]): View =
    Description(items, key, row, summon[Equality[A]], s"sequence:${summon[SchemaId[K]].value}:${summon[SchemaId[A]].value}")

object Switch:
  /** Unequal values remount the branch. */
  private[sirin] final case class Plain[A](value: Signal[A], branch: A => View, equality: Equality[A], schema: String) extends View
  private[sirin] final case class Keyed[K, A](value: Signal[A], key: A => K, branch: Inputs[A] => View, equality: Equality[A], schema: String) extends View
  def apply[A](value: Signal[A])(branch: A => View)(using Equality[A], SchemaId[A]): View = Plain(value, branch, summon[Equality[A]], summon[SchemaId[A]].value)
  /** Same key preserves the branch and delivers live replacement Inputs. */
  def keyed[K, A](value: Signal[A])(key: A => K)(branch: Inputs[A] => View)(using Equality[A], SchemaId[K], SchemaId[A]): View =
    Keyed(value, key, branch, summon[Equality[A]], s"${summon[SchemaId[K]].value}:${summon[SchemaId[A]].value}")

object Show:
  /** Boolean keyed Switch sugar; no new lifetime model. */
  def apply(when: Signal[Boolean])(content: => View): View = Switch(when)(value => if value then content else View.empty)
