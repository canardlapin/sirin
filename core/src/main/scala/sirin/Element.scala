package sirin

import scala.annotation.unused

/** The public extension API for custom elements and layouts. The standard widgets are meant to be expressible with
  * exactly these interfaces; there are no private hooks (VISION §1).
  */
enum Hit:
  case Miss
  case Self
  case Part(id: PartId)

enum PointerResponse:
  case Ignore,
    Handled

  /** Route subsequent pointer events to this element until `Release` or `Up`. */
  case Capture
  case Release

/** A recording paint target. Paint is pure: it may not write application state. */
trait Canvas:
  def theme: Theme

  /** Recording implementations expose prepared-result lookup, not shaping. */
  def textServices: TextServices
  def text(layout: TextLayout, at: Point, color: Color): Unit
  def fill(rect: Rect, color: Color, radius: Lp = Lp.zero): Unit
  def stroke(rect: Rect, color: Color, width: Lp, radius: Lp = Lp.zero): Unit
  def line(from: Point, to: Point, color: Color, width: Lp): Unit

  /** Uses an already prepared unbounded-width request for this content/style. */
  def text(content: String, at: Point, style: TextStyle, color: Color): Unit

  /** Canonical drawing scope names. The child canvas expires when its scope exits. */
  def layer(alpha: Double)(body: Canvas => Unit): Unit
  def clipped(rect: Rect)(body: Canvas => Unit): Unit
  def transformed(transform: Transform2D)(body: Canvas => Unit): Unit

  /** Compatibility conveniences over the same drawing scopes. */
  /** Isolated group opacity (VISION §7). */
  def withOpacity(alpha: Double)(body: => Unit): Unit
  def withClip(rect: Rect)(body: => Unit): Unit
  def withOffset(by: Point)(body: => Unit): Unit

  /** Local paint transform; an element applies the inverse in its hit geometry. */
  def withTransform(transform: Transform2D)(body: => Unit): Unit

/** Separate phase inputs make invalidation explicit. Presentation here is paint-only (e.g. a shadow); use View.offset
  * for coherent geometry motion.
  */
final case class ElementInputs[L, P, H, S, A](
    layout: Signal[L],
    paint: Signal[P],
    hit: Signal[H],
    semantics: Signal[S],
    presentation: Presentation[A],
    layoutGlobals: Set[PhaseDependency] = Set(PhaseDependency.FontMetrics, PhaseDependency.DisplayScale),
    paintGlobals: Set[PhaseDependency] = Set(PhaseDependency.ThemeColors)
)

/** Presentation facts from the accepted input geometry; never model state. */
final case class ElementFeedback(focused: Boolean = false, keyboard: Boolean = false, pressed: Boolean = false)

/** Opt in to conventional pointer-release, Enter and Space-release activation. The action is dispatched to
  * `Element.action` and uses that element's semantics.
  */
final case class ElementActivation(part: PartId, enabled: Boolean)

/** Focus arriving at or leaving a focusable element. */
enum FocusEvent:
  case Gained

  /** Focus is moving away, or the window deactivated. Refusing a move away keeps focus on this element. */
  case Lost

trait ElementHandlers:
  /** Reads in handlers are explicitly staged model reads; geometry is in event.hit. */
  def pointer(
      @unused
      event: PointerEvent
  )(using
      @unused
      txn: UiTxn
  ): PointerResponse = PointerResponse.Ignore
  def wheel(
      @unused
      event: WheelEvent
  )(using
      @unused
      txn: UiTxn
  ): Boolean = false
  def key(
      @unused
      event: KeyEvent
  )(using
      @unused
      txn: UiTxn
  ): Boolean = false
  def action(
      @unused
      part: PartId,
      @unused
      action: SemanticAction
  )(using
      @unused
      txn: UiTxn
  ): Boolean = false

  /** Text-channel input for a focused element that declares `Element.textInput`. */
  def text(
      @unused
      event: TextInputEvent,
      @unused
      services: TextEditingServices
  )(using
      @unused
      txn: UiTxn
  ): Boolean = false

  /** Runs in its own transaction before focus moves away and after it arrives. */
  def focus(
      @unused
      event: FocusEvent
  )(using
      @unused
      txn: UiTxn
  ): Unit = ()

  /** Text controls override these forms to reach the clipboard, segmentation and presented text. */
  def key(
      event: KeyEvent,
      @unused
      services: TextEditingServices
  )(using UiTxn): Boolean = key(event)
  def pointer(
      event: PointerEvent,
      @unused
      services: TextEditingServices
  )(using UiTxn): PointerResponse = pointer(event)

trait Element[L, P, H, S, A] extends ElementHandlers:
  def paint(state: P, presentation: A, size: Size, canvas: Canvas)(using Committed): Unit
  def paint(
      state: P,
      presentation: A,
      size: Size,
      canvas: Canvas,
      @unused
      feedback: ElementFeedback
  )(using Committed): Unit = paint(state, presentation, size, canvas)

  /** Cheap presentation feedback over a retained base drawing. */
  def paintOverlay(
      @unused
      state: P,
      @unused
      presentation: A,
      @unused
      size: Size,
      @unused
      canvas: Canvas,
      @unused
      feedback: ElementFeedback
  )(using
      @unused
      c: Committed
  ): Unit = ()

  /** Requests resolved before measurement; the supplied service only looks them up. */
  def measureTextRequests(
      @unused
      state: L,
      @unused
      constraints: Constraints
  )(using
      @unused
      c: Committed
  ): Seq[TextRequest] = Nil
  def measure(
      state: L,
      constraints: Constraints,
      @unused
      text: TextServices
  )(using Committed): Size = measure(state, constraints)
  def activation(
      @unused
      state: S
  )(using
      @unused
      c: Committed
  ): Option[ElementActivation] = None

  /** Declared while this element, when focused, accepts text input. It shares the semantics state; `text` looks up
    * the layouts prepared for paint, so the caret can come from presented geometry.
    */
  def textInput(
      @unused
      state: S,
      @unused
      size: Size,
      @unused
      text: TextServices
  )(using
      @unused
      c: Committed
  ): Option[TextInputRequest] = None

  /** Requests resolved before paint; Canvas.text performs lookup only. */
  def textRequests(
      @unused
      state: P,
      @unused
      presentation: A,
      @unused
      size: Size
  )(using
      @unused
      committed: Committed
  ): Seq[TextRequest] = Nil
  def measure(
      @unused
      state: L,
      constraints: Constraints
  )(using
      @unused
      committed: Committed
  ): Size = constraints.biggest
  def hitTest(
      @unused
      state: H,
      @unused
      size: Size,
      @unused
      at: Point
  )(using
      @unused
      committed: Committed
  ): Hit = Hit.Self
  def semantics(
      @unused
      state: S,
      @unused
      size: Size
  )(using
      @unused
      committed: Committed
  ): Seq[SemanticNode] = Nil

object Element:
  /** One state feeds every phase; any state change invalidates all four phases. */
  abstract class Simple[S] extends Element[S, S, S, S, Unit]:
    def paint(state: S, size: Size, canvas: Canvas)(using Committed): Unit
    final def paint(
        state: S,
        @unused
        presentation: Unit,
        size: Size,
        canvas: Canvas
    )(using Committed): Unit = paint(state, size, canvas)

  def simple[S](name: String, state: Signal[S], element: Simple[S], focusable: Boolean = false): View = apply(
    name,
    ElementInputs(state, state, state, state, Presentation.const(())),
    element,
    focusable
  )

  private[sirin] final case class Description[L, P, H, S, A](
      name: String,
      inputs: ElementInputs[L, P, H, S, A],
      element: Element[L, P, H, S, A],
      focusable: Boolean,
      pointerDelivery: Delivery,
      pointerCapacity: Capacity,
      handlers: Option[ElementHandlers]
  ) extends View
  def apply[L, P, H, S, A](
      name: String,
      inputs: ElementInputs[L, P, H, S, A],
      element: Element[L, P, H, S, A],
      focusable: Boolean = false,
      pointerDelivery: Delivery = Delivery.CoalesceLatest,
      pointerCapacity: Capacity = Capacity(256),
      handlers: Option[ElementHandlers] = None
  ): View =
    require(name.nonEmpty && name.length <= 128, "element name must contain 1 through 128 characters")
    Description(name, inputs, element, focusable, pointerDelivery, pointerCapacity, handlers)

opaque type Slot = String
object Slot:
  def apply(name: String): Slot =
    require(name.nonEmpty && name.length <= 128, "slot name must contain 1 through 128 characters")
    name
  extension (slot: Slot)
    def name: String = slot

/** Unplaced children stay mounted but cannot draw/hit/expose semantics. Cached by constraint; intrinsic=true labels
  * intentional multi-measurement.
  */
trait LayoutChildren:
  def count: Int
  def measure(index: Int, constraints: Constraints, intrinsic: Boolean = false): Size
  def place(index: Int, at: Point): Unit
  def measure(slot: Slot, constraints: Constraints): Size
  def place(slot: Slot, at: Point): Unit

trait LayoutPolicy[S]:
  def layout(state: S, constraints: Constraints, children: LayoutChildren)(using Committed): Size

object Layout:
  private[sirin] final case class Description[S](
      name: String,
      state: Option[Signal[S]],
      presentation: Option[LayoutPresentation[S]],
      policy: LayoutPolicy[S],
      children: Vector[(Slot, View)]
  ) extends View
  private def description[S](
      name: String,
      state: Option[Signal[S]],
      presentation: Option[LayoutPresentation[S]],
      policy: LayoutPolicy[S],
      children: Vector[(Slot, View)]
  ): View =
    require(name.nonEmpty && name.length <= 128, "layout name must contain 1 through 128 characters")
    require(
      children.size <= 10000 && children.map(_._1).distinct.size == children.size,
      "duplicate or excessive layout slots"
    )
    Description(name, state, presentation, policy, children)
  def apply[S](name: String, state: Signal[S], policy: LayoutPolicy[S])(children: View*): View = description(
    name,
    Some(state),
    None,
    policy,
    children.zipWithIndex.map((v, i) => Slot(i.toString) -> v).toVector
  )
  def named[S](name: String, state: Signal[S], policy: LayoutPolicy[S])(children: (Slot, View)*): View = description(
    name,
    Some(state),
    None,
    policy,
    children.toVector
  )
  def presented[S](name: String, state: LayoutPresentation[S], policy: LayoutPolicy[S])(children: View*): View =
    description(name, None, Some(state), policy, children.zipWithIndex.map((v, i) => Slot(i.toString) -> v).toVector)
