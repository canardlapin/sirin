package sirin

/** `Scroll` as a public composition. A component owns the offset; a reporting layout clips the content and places it
  * at that offset; a focusable element beneath the content takes wheel and keyboard scrolling and clamps against the
  * last presented viewport.
  */
private[sirin] object ScrollControl:
  private val line = 40.0

  private def clamp(offset: Double, viewport: Viewport): Double =
    val limit = math.max(0, viewport.content.height.toDouble - viewport.size.height.toDouble)
    math.max(0, math.min(offset, limit))

  /** Child 0 is the surface, sized to the viewport; child 1 is the content, measured at unbounded height. */
  private val policy =
    new LayoutPolicy[Double]:
      def layout(offset: Double, c: Constraints, children: LayoutChildren)(using Committed): Size =
        val height = c.maxHeight.toDouble
        require(height.isFinite, "scroll viewport needs a finite allocated height")
        require(offset.isFinite, "invalid scroll offset")
        val content = children.measure(1, Constraints(Lp.zero, c.maxWidth, Lp.zero, Lp(Double.PositiveInfinity)))
        val shown = math.max(0, math.min(offset, content.height.toDouble - height))
        children.measure(0, Constraints(c.maxWidth, c.maxWidth, c.maxHeight, c.maxHeight))
        children.place(0, Point.origin)
        children.place(1, Point(Lp.zero, Lp(-shown)))
        c.biggest

  private val surface =
    new Element[Unit, Unit, Unit, Unit, Unit]:
      def paint(state: Unit, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit = ()

      override def paint(state: Unit, presentation: Unit, size: Size, canvas: Canvas, feedback: ElementFeedback)(using
          Committed
      ): Unit = paintOverlay(state, presentation, size, canvas, feedback)

      override def paintOverlay(state: Unit, presentation: Unit, size: Size, canvas: Canvas, feedback: ElementFeedback)(
          using Committed
      ): Unit =
        if feedback.focused && feedback.keyboard then
          canvas.fill(Rect(Point.origin, Size(size.width.min(2.lp), size.height)), canvas.theme.color(ColorRole.Accent))

  private def handlers(offset: Cell[Double], viewport: Signal[Viewport]): ElementHandlers =
    new ElementHandlers:
      private def scroll(delta: Double, absolute: Boolean)(using UiTxn): Boolean =
        val shown = viewport.get
        val from =
          if absolute then
            0.0
          else
            clamp(offset.get, shown)
        offset.set(clamp(from + delta, shown))
        true

      override def wheel(event: WheelEvent)(using UiTxn): Boolean = scroll(-event.delta.y.toDouble * line, false)

      override def key(event: KeyEvent)(using UiTxn): Boolean =
        val page = viewport.get.size.height.toDouble * 0.9
        if !event.mods.isEmpty then
          false
        else
          event.key match
            case Key.ArrowDown =>
              scroll(line, false)
            case Key.ArrowUp =>
              scroll(-line, false)
            case Key.PageDown =>
              scroll(page, false)
            case Key.PageUp =>
              scroll(-page, false)
            case Key.Home =>
              scroll(0, true)
            case Key.End =>
              scroll(Double.PositiveInfinity, true)
            case _ =>
              false

  private val component = Component[View]("scroll") { (scope, content) =>
    val offset = scope.cell(0.0)
    val viewport = scope.cell(Viewport.empty)
    val unit = Signal.const(())
    val input = Element(
      "scroll",
      ElementInputs(unit, unit, unit, unit, Presentation.const(())),
      surface,
      focusable = true,
      handlers = Some(handlers(offset, viewport))
    )
    Layout.reporting("scroll", offset, policy, viewport)(input, View.live(content))
  }

  def view(content: View): View = component(content)
