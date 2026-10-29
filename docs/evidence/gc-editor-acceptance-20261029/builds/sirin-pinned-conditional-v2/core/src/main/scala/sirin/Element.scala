// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

import scala.annotation.unused

/** The public extension API for custom elements and layouts. The standard
  * widgets are meant to be expressible with exactly these interfaces; there
  * are no private hooks (VISION §1).
  */
enum Hit:
  case Miss
  case Self
  case Part(id: PartId)

enum PointerResponse:
  case Ignore, Handled

  /** Route subsequent pointer events to this element until `Release` or `Up`. */
  case Capture
  case Release

/** A recording paint target. Paint is pure: it may not write application state. */
trait Canvas:
  def theme: Theme
  def fill(rect: Rect, color: Color, radius: Lp = Lp.zero): Unit
  def stroke(rect: Rect, color: Color, width: Lp, radius: Lp = Lp.zero): Unit
  def line(from: Point, to: Point, color: Color, width: Lp): Unit
  def text(content: String, at: Point, style: TextStyle, color: Color): Unit

  /** Isolated group opacity (VISION §7). */
  def withOpacity(alpha: Double)(body: => Unit): Unit
  def withClip(rect: Rect)(body: => Unit): Unit
  def withOffset(by: Point)(body: => Unit): Unit

/** A custom element over a declared state `S`. Measurement, paint, hit
  * testing, and semantics are functions of the committed state; input
  * handlers run in a transaction.
  */
trait Element[S]:
  def paint(state: S, size: Size, canvas: Canvas): Unit

  /** Defaults: fill the constraints, hit as one target, no semantic
    * children, ignore input.
    */
  def measure(@unused state: S, constraints: Constraints): Size = constraints.biggest
  def hitTest(@unused state: S, @unused size: Size, @unused at: Point): Hit = Hit.Self
  def semantics(@unused state: S, @unused size: Size): Seq[SemanticNode]  = Nil
  def pointer(@unused state: S, @unused event: PointerEvent)(using @unused txn: UiTxn): PointerResponse =
    PointerResponse.Ignore
  def key(@unused state: S, @unused event: KeyEvent)(using @unused txn: UiTxn): Boolean = false
  def action(@unused state: S, @unused part: PartId, @unused action: SemanticAction)(using
      @unused txn: UiTxn
  ): Boolean = false

object Element:
  def apply[S](name: String, state: Signal[S], element: Element[S], focusable: Boolean = false): View =
    ???

/** Child access for a custom layout. Children are the views passed to `Layout`. */
trait LayoutChildren:
  def count: Int
  def measure(index: Int, constraints: Constraints): Size
  def place(index: Int, at: Point): Unit

trait LayoutPolicy[S]:
  def layout(state: S, constraints: Constraints, children: LayoutChildren): Size

object Layout:
  def apply[S](name: String, state: Signal[S], policy: LayoutPolicy[S])(children: View*): View = ???
