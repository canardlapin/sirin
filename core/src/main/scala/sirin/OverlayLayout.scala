package sirin

/** `Overlay` as a public layout: the base takes the offered constraints and every layer is sized to the base. */
private[sirin] object OverlayLayout:
  private val policy =
    new LayoutPolicy[Unit]:
      def layout(state: Unit, c: Constraints, children: LayoutChildren)(using Committed): Size =
        val base = children.measure(0, c)
        val tight = Constraints(base.width, base.width, base.height, base.height)
        children.place(0, Point.origin)
        (1 until children.count).foreach { i =>
          children.measure(i, tight)
          children.place(i, Point.origin)
        }
        base

  def view(children: Vector[View]): View = Layout("overlay", Signal.const(()), policy)(children*)
