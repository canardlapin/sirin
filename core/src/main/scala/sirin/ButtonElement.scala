package sirin

import scala.annotation.unused

/** A standard control composed entirely through public element and signal APIs. */
private[sirin] object ButtonElement:
  private case class Label(text: String, singleLine: Boolean):
    def display: String =
      if singleLine then text.replaceAll("[\\r\\n\\u000B\\u000C\\u0085\\u2028\\u2029]+", " ") else text
    def width(available: Lp): Lp =
      if singleLine then Lp(Double.PositiveInfinity) else available.max(Lp.zero)
    def request(available: Lp): TextRequest = TextRequest(display, TextStyle.Body, width(available))

  private case class State(label: Label, enabled: Boolean, busy: Boolean, primary: Boolean)
  private val part = PartId(0)

  private val definition =
    new Element[Label, State, Unit, State, Unit]:
      override def measureTextRequests(text: Label, c: Constraints)(using
          @unused
          committed: Committed
      ): Seq[TextRequest] = Vector(text.request(c.maxWidth - 24.lp))

      override def measure(text: Label, c: Constraints, services: TextServices)(using Committed): Size =
        val result = services.layout(text.display, TextStyle.Body, text.width(c.maxWidth - 24.lp))
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
      ): Seq[TextRequest] =
        val label = state.label.request(size.width - 24.lp)
        if state.label.singleLine then Vector(label, TextRequest("…", TextStyle.Body)) else Vector(label)

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
          if state.primary && state.enabled then
            canvas.theme.color(ColorRole.Accent)
          else
            canvas.theme.color(ColorRole.SurfaceRaised)
        canvas.fill(bounds, base)
        if !state.primary || !state.enabled then canvas.stroke(bounds, canvas.theme.color(ColorRole.Border), 1.lp)
        val label = state.label
        val width = (size.width - 24.lp).max(Lp.zero)
        val text = canvas.textServices.layout(label.display, TextStyle.Body, label.width(width))
        val color = canvas.theme.color(
          if !state.enabled then ColorRole.Muted
          else if state.primary then ColorRole.OnAccent
          else ColorRole.OnSurface)
        if label.singleLine then
          val content = Rect(Point(12.lp, 8.lp), Size(width, (size.height - 16.lp).max(Lp.zero)))
          canvas.clipped(content) { clipped =>
            if text.size.width.toDouble <= width.toDouble then
              clipped.text(text, content.origin, color)
            else
              val ellipsis = canvas.textServices.layout("…", TextStyle.Body, Lp(Double.PositiveInfinity))
              val room = (width - ellipsis.size.width).max(Lp.zero).toDouble
              val start = text.caret(0, TextAffinity.Downstream).map(_.bounds.origin.x.toDouble).getOrElse(0.0)
              val end = text.caret(text.text.length, TextAffinity.Upstream)
                .map(_.bounds.origin.x.toDouble).getOrElse(text.size.width.toDouble)
              val rtl = start > end
              val stops = text.graphemeBoundaries.flatMap(text.caretsAt)
                .filter(_.lineIndex == 0).map(_.bounds.origin.x.toDouble)
              val edge =
                if rtl then stops.filter(_ >= text.size.width.toDouble - room).minOption
                  .getOrElse(text.size.width.toDouble)
                else stops.filter(_ <= room).maxOption.getOrElse(0.0)
              val kept = if rtl then text.size.width.toDouble - edge else edge
              val offset = if rtl then width - text.size.width else Lp.zero
              val left = if rtl then width - kept.lp else Lp.zero
              clipped.clipped(Rect(Point(12.lp + left, 8.lp), Size(kept.lp, content.size.height))) { prefix =>
                prefix.text(text, Point(12.lp + offset, 8.lp), color)
              }
              val markerX = if rtl then Lp.zero else (width - ellipsis.size.width).max(Lp.zero)
              clipped.text(ellipsis, Point(12.lp + markerX, 8.lp), color)
          }
        else canvas.text(text, Point(12.lp, 8.lp), color)
        paintOverlay(state, presentation, size, canvas, feedback)

      override def paintOverlay(
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
        val contrast = if state.primary && state.enabled then ColorRole.OnAccent else ColorRole.Accent
        if feedback.pressed then
          canvas.fill(bounds, canvas.theme.color(contrast).withAlpha(46))
        if feedback.focused && feedback.keyboard && size.width.toDouble > 0 && size.height.toDouble > 0 then
          val ring = if state.primary && state.enabled then ColorRole.OnAccent else ColorRole.FocusRing
          canvas.stroke(bounds, canvas.theme.color(ring), 2.lp)

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
          Semantics(Role.Button, name = Some(state.label.text), busy = state.busy),
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
      singleLine: Boolean,
      enabled: Signal[Boolean],
      running: Signal[Int],
      invoke: UiTxn => Unit
  ): View =
    val shaped = label.map(text => Label(text, singleLine))
    val state = (shaped, enabled, running).mapN((text, active, count) => State(text, active, count > 0, primary))
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
      ElementInputs(shaped, state, Signal.const(()), state, Presentation.const(())),
      definition,
      focusable = true,
      handlers = Some(handlers)
    )
