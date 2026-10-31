package sirin

/** The geometry of `VirtualRows` as a public layout policy. Realized rows sit at their absolute positions in a column
  * whose height covers every row, realized or not, so the viewport the host reports is the realization range.
  */
private[sirin] object VirtualRowsLayout:
  final case class State(first: Int, total: Int, rowHeight: Double)

  val policy: LayoutPolicy[State] =
    new LayoutPolicy[State]:
      def layout(state: State, c: Constraints, children: LayoutChildren)(using Committed): Size =
        require(
          state.rowHeight.isFinite && state.rowHeight > 0 && state.total >= 0 && state.first >= 0 &&
            state.first.toLong + children.count <= state.total,
          "invalid virtual metadata"
        )
        val row = Lp(state.rowHeight)
        (0 until children.count).foreach { i =>
          children.measure(i, Constraints(Lp.zero, c.maxWidth, row, row))
          children.place(i, Point(Lp.zero, Lp((state.first.toDouble + i) * state.rowHeight)))
        }
        val extent = Lp(state.total * state.rowHeight)
        Size(c.maxWidth, extent.max(c.minHeight).min(c.maxHeight))
