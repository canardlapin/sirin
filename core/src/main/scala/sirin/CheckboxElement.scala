package sirin

import scala.annotation.unused

/** `Checkbox` as a public element: a box and its label, toggled by pointer release, Space or Enter, or an assistive
  * Activate. The owner decides what a toggle writes.
  */
private[sirin] object CheckboxElement:
  private final case class State(label: String, checked: Boolean)
  private val part = PartId(0)
  private val box = 16.0
  private val gap = 8.0

  private def textWidth(width: Lp): Lp = (width - Lp(box + gap)).max(Lp.zero)

  private val definition =
    new Element[String, State, Unit, State, Unit]:
      override def measureTextRequests(label: String, c: Constraints)(using
          @unused
          committed: Committed
      ): Seq[TextRequest] = Vector(TextRequest(label, TextStyle.Body, textWidth(c.maxWidth)))

      override def measure(label: String, c: Constraints, services: TextServices)(using Committed): Size =
        val text = services.layout(label, TextStyle.Body, textWidth(c.maxWidth))
        Size(
          (text.size.width + Lp(box + gap)).max(c.minWidth).min(c.maxWidth),
          text.size.height.max(Lp(box)).max(c.minHeight).min(c.maxHeight)
        )

      override def textRequests(
          state: State,
          @unused
          presentation: Unit,
          size: Size
      )(using
          @unused
          c: Committed
      ): Seq[TextRequest] = Vector(TextRequest(state.label, TextStyle.Body, textWidth(size.width)))

      private def square(size: Size): Rect = Rect(Point(Lp.zero, (size.height - Lp(box)) / 2), Size(Lp(box), Lp(box)))

      def paint(state: State, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit = paint(
        state,
        presentation,
        size,
        canvas,
        ElementFeedback()
      )

      override def paint(state: State, presentation: Unit, size: Size, canvas: Canvas, feedback: ElementFeedback)(using
          Committed
      ): Unit =
        val theme = canvas.theme
        val mark = square(size)
        if state.checked then
          canvas.fill(mark, theme.color(ColorRole.Accent), 3.lp)
          val x = mark.origin.x.toDouble
          val y = mark.origin.y.toDouble
          val ink = theme.color(ColorRole.Surface)
          canvas.line(Point(Lp(x + 4), Lp(y + 8)), Point(Lp(x + 7), Lp(y + 11)), ink, 2.lp)
          canvas.line(Point(Lp(x + 7), Lp(y + 11)), Point(Lp(x + 12), Lp(y + 5)), ink, 2.lp)
        else
          canvas.stroke(mark, theme.color(ColorRole.Muted), 1.5.lp, 3.lp)
        val text = canvas.textServices.layout(state.label, TextStyle.Body, textWidth(size.width))
        canvas.text(
          text,
          Point(Lp(box + gap), (size.height - text.size.height) / 2),
          theme.color(ColorRole.OnSurface)
        )
        paintOverlay(state, presentation, size, canvas, feedback)

      override def paintOverlay(
          @unused
          state: State,
          @unused
          presentation: Unit,
          size: Size,
          canvas: Canvas,
          feedback: ElementFeedback
      )(using
          @unused
          c: Committed
      ): Unit =
        val mark = square(size)
        if feedback.pressed then
          canvas.fill(mark, Color.rgba(50, 130, 220, 46), 3.lp)
        if feedback.focused && feedback.keyboard then
          canvas.stroke(mark, canvas.theme.color(ColorRole.FocusRing), 2.lp, 3.lp)

      override def activation(state: State)(using
          @unused
          c: Committed
      ): Option[ElementActivation] = Some(ElementActivation(part, true))

      override def semantics(state: State, size: Size)(using
          @unused
          c: Committed
      ): Seq[SemanticNode] = Vector(
        SemanticNode(
          part,
          Semantics(Role.Checkbox, name = Some(state.label), checked = Some(state.checked)),
          Rect(Point.origin, size),
          Set(SemanticAction.Activate)
        )
      )

  def view(checked: Signal[Boolean], label: Signal[String], onChange: Boolean => Action): View =
    val state = (label, checked).mapN(State(_, _))
    val handlers =
      new ElementHandlers:
        override def action(target: PartId, action: SemanticAction)(using txn: UiTxn): Boolean =
          if target != part || action != SemanticAction.Activate then
            false
          else
            onChange(!checked.get)(using txn)
            true
    Element(
      "checkbox",
      ElementInputs(label, state, Signal.const(()), state, Presentation.const(())),
      definition,
      focusable = true,
      handlers = Some(handlers)
    )
