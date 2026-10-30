package sirin

import scala.annotation.unused

/** A standard control composed entirely through public element and signal APIs. */
private[sirin] object ButtonElement:
  private case class State(label: String, enabled: Boolean, busy: Boolean, primary: Boolean)
  private val part = PartId(0)

  private val definition =
    new Element[String, State, Unit, State, Unit]:
      override def measureTextRequests(text: String, c: Constraints)(using
          @unused
          committed: Committed
      ): Seq[TextRequest] = Vector(TextRequest(text, TextStyle.Body, (c.maxWidth - 24.lp).max(Lp.zero)))

      override def measure(text: String, c: Constraints, services: TextServices)(using Committed): Size =
        val result = services.layout(text, TextStyle.Body, (c.maxWidth - 24.lp).max(Lp.zero))
        Size(
          (result.size.width + 24.lp).max(c.minWidth).min(c.maxWidth),
          (result.size.height + 16.lp).max(c.minHeight).min(c.maxHeight)
        )

      override def textRequests(
          state: State,
          @unused
          presentation: Unit,
          size: Size
      )(using
          @unused
          c: Committed
      ): Seq[TextRequest] = Vector(TextRequest(state.label, TextStyle.Body, (size.width - 24.lp).max(Lp.zero)))

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
        val bounds = Rect(Point.origin, size)
        val base =
          if state.primary then
            Color.rgba(45, 90, 140)
          else
            Color.rgba(44, 53, 69)
        canvas.fill(bounds, base)
        val text = canvas.textServices.layout(state.label, TextStyle.Body, (size.width - 24.lp).max(Lp.zero))
        canvas.text(
          text,
          Point(12.lp, 8.lp),
          canvas
            .theme
            .color(
              if state.enabled then
                ColorRole.OnSurface
              else
                ColorRole.Muted
            )
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
        val bounds = Rect(Point.origin, size)
        if feedback.pressed then
          canvas.fill(bounds, Color.rgba(50, 130, 220, 46))
        if feedback.focused && feedback.keyboard && size.width.toDouble > 0 && size.height.toDouble > 0 then
          canvas.stroke(bounds, canvas.theme.color(ColorRole.FocusRing), 2.lp)

      override def activation(state: State)(using
          @unused
          c: Committed
      ): Option[ElementActivation] = Some(ElementActivation(part, state.enabled))

      override def semantics(state: State, size: Size)(using
          @unused
          c: Committed
      ): Seq[SemanticNode] = Vector(
        SemanticNode(
          part,
          Semantics(Role.Button, name = Some(state.label), busy = state.busy),
          Rect(Point.origin, size),
          if state.enabled then
            Set(SemanticAction.Activate)
          else
            Set.empty
        )
      )

  def view(
      label: Signal[String],
      primary: Boolean,
      enabled: Signal[Boolean],
      running: Signal[Int],
      invoke: UiTxn => Unit
  ): View =
    val state = (label, enabled, running).mapN((text, active, count) => State(text, active, count > 0, primary))
    val handlers =
      new ElementHandlers:
        override def action(target: PartId, action: SemanticAction)(using txn: UiTxn): Boolean =
          if target != part || action != SemanticAction.Activate then
            false
          else
            invoke(txn)
            true
    Element(
      "button",
      ElementInputs(label, state, Signal.const(()), state, Presentation.const(())),
      definition,
      focusable = true,
      handlers = Some(handlers)
    )
