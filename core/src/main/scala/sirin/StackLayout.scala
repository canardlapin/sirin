package sirin

/** `Row` and `Column` as a public layout policy over their children. */
private[sirin] object StackLayout:
  final case class State(horizontal: Boolean, gap: Double, alignment: Alignment)

  private def bounded(value: Double, limit: Double): Double = math.max(0, math.min(value, limit))

  private val policy =
    new LayoutPolicy[State]:
      def layout(state: State, c: Constraints, children: LayoutChildren)(using Committed): Size =
        require(state.gap.isFinite && state.gap >= 0, "invalid stack gap")
        require(state.alignment != Alignment.Baseline, "baseline layout remains deferred")
        val horizontal = state.horizontal
        val w = c.maxWidth.toDouble
        val h = c.maxHeight.toDouble
        val fillWidth = c.minWidth == c.maxWidth
        val fillHeight = c.minHeight == c.maxHeight
        val active = (0 until children.count).filterNot(children.collapsed).toVector
        val main =
          if horizontal then
            w
          else
            h
        val cross =
          if horizontal then
            h
          else
            w
        val available = math.max(0, main - state.gap * math.max(0, active.size - 1))
        def expanding(i: Int): Boolean = available.isFinite && children.grows(i)
        val stretch = state.alignment == Alignment.Stretch && cross.isFinite
        def constraint(size: Double, fill: Boolean): (Lp, Lp) =
          (
            if fill then
              size
            else
              0.0
          ).lp -> size.lp
        def measure(i: Int, mainSize: Double, fillMain: Boolean): Size =
          val (minMain, maxMain) = constraint(mainSize, fillMain)
          val (minCross, maxCross) = constraint(cross, stretch)
          val child =
            if horizontal then
              Constraints(minMain, maxMain, minCross, maxCross)
            else
              Constraints(minCross, maxCross, minMain, maxMain)
          children.measure(i, child)
        def along(size: Size): Double =
          if horizontal then
            size.width.toDouble
          else
            size.height.toDouble
        def across(size: Size): Double =
          if horizontal then
            size.height.toDouble
          else
            size.width.toDouble
        val fixed = active.filterNot(expanding).map(i => i -> measure(i, available, false)).toMap
        val used = fixed.valuesIterator.map(along).sum
        val growing = active.count(expanding)
        val share =
          if growing == 0 then
            0.0
          else
            math.max(0, available - used) / growing
        val sizes = active.map(i =>
          if expanding(i) then
            i -> measure(i, share, true)
          else
            i -> fixed(i)
        )
        val naturalCross = sizes.map((_, size) => across(size)).maxOption.getOrElse(0.0)
        val fillCross =
          if horizontal then
            fillHeight
          else
            fillWidth
        val crossSize =
          if fillCross then
            cross
          else
            bounded(naturalCross, cross)
        var cursor = 0.0
        sizes.foreach { (i, size) =>
          val offset =
            state.alignment match
              case Alignment.Center =>
                math.max(0, (crossSize - across(size)) / 2)
              case Alignment.End =>
                math.max(0, crossSize - across(size))
              case _ =>
                0.0
          val at =
            if horizontal then
              Point(cursor.lp, offset.lp)
            else
              Point(offset.lp, cursor.lp)
          children.place(i, at)
          cursor += along(size) + state.gap
        }
        val naturalMain =
          if active.isEmpty then
            0.0
          else
            bounded(cursor - state.gap, main)
        val fillMain =
          if horizontal then
            fillWidth
          else
            fillHeight
        val mainSize =
          if fillMain then
            main
          else
            naturalMain
        val (width, height) =
          if horizontal then
            (mainSize, crossSize)
          else
            (crossSize, mainSize)
        Size(
          width.lp.max(c.minWidth).min(c.maxWidth),
          height.lp.max(c.minHeight).min(c.maxHeight)
        )

  def view(stack: View.Stack): View =
    val name =
      if stack.horizontal then
        "row"
      else
        "column"
    val state = State(
      stack.horizontal,
      stack.gap.getOrElse(Lp.zero).toDouble,
      stack.alignment.getOrElse(Alignment.Start)
    )
    Layout(name, Signal.const(state), policy)(stack.children*)
