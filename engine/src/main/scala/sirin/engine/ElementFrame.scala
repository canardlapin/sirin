package sirin.engine

import sirin.*

/** Backend phase capability. Every invocation uses the kernel's poisoned read-only guard. */
private[engine] trait PhaseGuard:
  def apply[A](body: => A): A

/** Immutable settled phase data and pure callbacks; no graph reader or UI handler. */
final class ElementFrame private[engine](
    val instance: Long, val name: String, val revisions: Vector[Long], val focusable: Boolean, val layoutIdentity: AnyRef,
    definition: Element[Any,Any,Any,Any,Any], states: Vector[Any], guard: PhaseGuard
):
  private given Committed = new Committed {}
  def measure(constraints: Constraints): Size = guard(definition.measure(states(0),constraints))
  def textRequests(size: Size): Vector[TextRequest] = guard {
    val result=definition.textRequests(states(1),states(4),size).iterator.take(1025).toVector
    require(result.size<=1024,"element text request budget exceeded")
    result
  }
  def paint(size: Size,canvas: Canvas): Unit = guard(definition.paint(states(1),states(4),size,canvas))
  def hit(size: Size,at: Point): Hit = guard(definition.hitTest(states(2),size,at))
  def semantics(size: Size): Vector[SemanticNode] = guard {
    val result=definition.semantics(states(3),size).iterator.take(10001).toVector
    require(result.size<=10000 && result.map(_.part).distinct.size==result.size,"duplicate or excessive semantic parts")
    result.foreach { node =>
      val r=node.bounds
      require(Vector(r.origin.x.toDouble,r.origin.y.toDouble,r.size.width.toDouble,r.size.height.toDouble).forall(_.isFinite) &&
        r.size.width.toDouble>=0 && r.size.height.toDouble>=0,"invalid semantic bounds")
    }
    result
  }

/** UI-owner binding, kept out of the worker tree. It refuses after removal. */
final class ElementBinding private[engine](val instance: Long,val delivery: Delivery,val capacity: Capacity,
    pointerRun: (PointerEvent,UiTxn) => PointerResponse,keyRun: (KeyEvent,UiTxn) => Boolean,
    actionRun: (PartId,SemanticAction,UiTxn) => Boolean,wheelRun: (WheelEvent,UiTxn) => Boolean):
  def pointer(event: PointerEvent)(using txn: UiTxn): PointerResponse = pointerRun(event,txn)
  def wheel(event: WheelEvent)(using txn: UiTxn): Boolean = wheelRun(event,txn)
  def key(event: KeyEvent)(using txn: UiTxn): Boolean = keyRun(event,txn)
  def action(part: PartId,action: SemanticAction)(using txn: UiTxn): Boolean = actionRun(part,action,txn)

final class LayoutFrame private[engine](val instance: Long,val name: String,val revision: Long,
    state: Any,policy: LayoutPolicy[Any],guard: PhaseGuard):
  private given Committed = new Committed {}
  def layout(constraints: Constraints,children: LayoutChildren): Size = guard(policy.layout(state,constraints,children))
