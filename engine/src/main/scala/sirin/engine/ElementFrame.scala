package sirin.engine

import sirin.*

/** Backend phase capability. Every invocation uses the kernel's poisoned read-only guard. */
private[engine] trait PhaseGuard:
  def apply[A](body: => A): A

/** Immutable settled phase data and pure callbacks; no graph reader or UI handler. */
final class ElementFrame private[engine] (
    val instance: Long,
    val name: String,
    val revisions: Vector[Long],
    val focusable: Boolean,
    val layoutIdentity: AnyRef,
    val layoutGlobals: Set[PhaseDependency],
    val paintGlobals: Set[PhaseDependency],
    definition: Element[Any, Any, Any, Any, Any],
    private val states: Vector[Any],
    guard: PhaseGuard,
    source: Option[Presentation[Any]] = None
):
  private given Committed = new Committed {}
  private var shown = this

  /** This frame with its presentation input sampled for the frame being presented. Capture calls it on the owner
    * thread; an unchanged sample returns the same frame, so caches keyed on it survive. Copies carry no source.
    */
  private[sirin] def presented: ElementFrame =
    source.fold(this) { presentation =>
      val sample = guard(presentation.current)
      if sample == shown.states(4) then
        shown
      else
        shown = new ElementFrame(
          instance,
          name,
          revisions,
          focusable,
          layoutIdentity,
          layoutGlobals,
          paintGlobals,
          definition,
          states.updated(4, sample),
          guard
        )
        shown
    }
  def measure(constraints: Constraints): Size = guard(definition.measure(states(0), constraints))
  def measure(constraints: Constraints, text: TextServices): Size = guard(
    definition.measure(states(0), constraints, text)
  )
  def measureTextRequests(constraints: Constraints): Vector[TextRequest] = guard {
    val requests = definition.measureTextRequests(states(0), constraints).iterator.take(1025).toVector
    require(requests.size <= 1024, "element measurement text request budget exceeded")
    requests
  }
  def activation: Option[ElementActivation] = guard(definition.activation(states(3)))
  def textInput(size: Size, text: TextServices): Option[TextInputRequest] = guard {
    val request = definition.textInput(states(3), size, text)
    request.foreach { r =>
      val c = r.caret
      require(
        Vector(c.origin.x.toDouble, c.origin.y.toDouble, c.size.width.toDouble, c.size.height.toDouble).forall(
          _.isFinite
        ) && c.size.width.toDouble >= 0 && c.size.height.toDouble >= 0,
        "invalid text input caret"
      )
    }
    request
  }
  def textRequests(size: Size): Vector[TextRequest] = guard {
    val result = definition.textRequests(states(1), states(4), size).iterator.take(1025).toVector
    require(result.size <= 1024, "element text request budget exceeded")
    result
  }
  def paint(size: Size, canvas: Canvas, feedback: ElementFeedback = ElementFeedback()): Unit = guard(
    definition.paint(states(1), states(4), size, canvas, feedback)
  )
  def overlay(size: Size, canvas: Canvas, feedback: ElementFeedback): Unit = guard(
    definition.paintOverlay(states(1), states(4), size, canvas, feedback)
  )
  def hit(size: Size, at: Point): Hit = guard(definition.hitTest(states(2), size, at))
  def semantics(size: Size, text: TextServices): Vector[SemanticNode] = guard {
    val result = definition.semantics(states(3), size, text).iterator.take(10001).toVector
    require(
      result.size <= 10000 && result.map(_.part).distinct.size == result.size,
      "duplicate or excessive semantic parts"
    )
    result.foreach { node =>
      val r = node.bounds
      require(
        Vector(r.origin.x.toDouble, r.origin.y.toDouble, r.size.width.toDouble, r.size.height.toDouble).forall(
          _.isFinite
        ) && r.size.width.toDouble >= 0 && r.size.height.toDouble >= 0,
        "invalid semantic bounds"
      )
      node
        .text
        .foreach { text =>
          require(
            text.origin.x.toDouble.isFinite && text.origin.y.toDouble.isFinite,
            "invalid text semantics origin"
          )
        }
    }
    result
  }

/** UI-owner binding, kept out of the worker tree. It refuses after removal. */
final class ElementBinding private[engine] (
    val instance: Long,
    val delivery: Delivery,
    val capacity: Capacity,
    pointerRun: (PointerEvent, TextEditingServices, UiTxn) => PointerResponse,
    keyRun: (KeyEvent, TextEditingServices, UiTxn) => Boolean,
    actionRun: (PartId, SemanticAction, UiTxn) => Boolean,
    wheelRun: (WheelEvent, UiTxn) => Boolean,
    textRun: (TextInputEvent, TextEditingServices, UiTxn) => Boolean,
    focusRun: (FocusEvent, UiTxn) => Unit
):
  def pointer(event: PointerEvent, services: TextEditingServices = TextEditingServices.unavailable)(using
      txn: UiTxn
  ): PointerResponse = pointerRun(event, services, txn)
  def wheel(event: WheelEvent)(using txn: UiTxn): Boolean = wheelRun(event, txn)
  def key(event: KeyEvent, services: TextEditingServices = TextEditingServices.unavailable)(using txn: UiTxn): Boolean =
    keyRun(event, services, txn)
  def action(part: PartId, action: SemanticAction)(using txn: UiTxn): Boolean = actionRun(part, action, txn)
  def text(event: TextInputEvent, services: TextEditingServices)(using txn: UiTxn): Boolean =
    textRun(event, services, txn)
  def focus(event: FocusEvent)(using txn: UiTxn): Unit = focusRun(event, txn)

final class LayoutFrame private[engine] (
    val instance: Long,
    val name: String,
    val revision: Long,
    private val state: Any,
    policy: LayoutPolicy[Any],
    guard: PhaseGuard,
    source: Option[LayoutPresentation[Any]] = None,
    /** The host reports what it presents of this layout (`Layout.reporting`). */
    val reportsViewport: Boolean = false
):
  private given Committed = new Committed {}
  private var shown = this
  private[engine] def sameState(other: Any): Boolean = state == other

  /** Layout presentation sampled for the frame being presented, as for `ElementFrame.presented`. Layout is charged
    * for every changed sample.
    */
  private[sirin] def presented: LayoutFrame =
    source.fold(this) { presentation =>
      val sample = guard(presentation.current)
      if sample == shown.state then
        shown
      else
        shown = new LayoutFrame(instance, name, revision, sample, policy, guard, None, reportsViewport)
        shown
    }
  def layout(constraints: Constraints, children: LayoutChildren): Size = guard(
    policy.layout(state, constraints, children)
  )
