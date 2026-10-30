// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
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

  /** Isolated group opacity (VISION §7). */
  def withOpacity(alpha: Double)(body: => Unit): Unit
  def withClip(rect: Rect)(body: => Unit): Unit
  def withOffset(by: Point)(body: => Unit): Unit

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

trait Element[L, P, H, S, A]:
  def paint(state: P, presentation: A, size: Size, canvas: Canvas)(using Committed): Unit
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

  /** Reads in handlers are explicitly staged model reads; geometry is in event.hit. */
  def pointer(
      @unused
      event: PointerEvent
  )(using
      @unused
      txn: UiTxn
  ): PointerResponse = PointerResponse.Ignore
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

object Element:
  def apply[L, P, H, S, A](
      name: String,
      inputs: ElementInputs[L, P, H, S, A],
      element: Element[L, P, H, S, A],
      focusable: Boolean = false,
      pointerDelivery: Delivery = Delivery.CoalesceLatest,
      pointerCapacity: Capacity = Capacity(256)
  ): View = ???

opaque type Slot = String
object Slot:
  def apply(name: String): Slot = name

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
  def apply[S](name: String, state: Signal[S], policy: LayoutPolicy[S])(children: View*): View = ???
  def named[S](name: String, state: Signal[S], policy: LayoutPolicy[S])(children: (Slot, View)*): View = ???
  def presented[S](name: String, state: LayoutPresentation[S], policy: LayoutPolicy[S])(children: View*): View = ???
