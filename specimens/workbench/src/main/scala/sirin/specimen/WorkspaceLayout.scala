package sirin.specimen

import sirin.*

/** Public layout: the active retained editor receives all space beside the document list. */
private[specimen] object WorkspaceLayout:
  private val policy = new LayoutPolicy[Boolean]:
    def layout(right: Boolean, c: Constraints, children: LayoutChildren)(using Committed): Size =
      val width = c.maxWidth.toDouble
      val height = c.maxHeight.toDouble
      require(width.isFinite && height.isFinite, "workspace requires a bounded window")
      val sidebar = math.min(176.0, width * 0.3)
      val gap = math.min(12.0, math.max(0, width - sidebar))
      val editor = math.max(0, width - sidebar - gap)
      val editorX = if right then sidebar + gap else 0.0
      val sidebarX = if right then 0.0 else editor + gap
      def place(index: Int, x: Double, w: Double): Unit =
        children.measure(index, Constraints(w.lp, w.lp, height.lp, height.lp))
        children.place(index, Point(x.lp, Lp.zero))
      place(0, sidebarX, sidebar)
      place(1, editorX, if right then 0.0 else editor)
      place(2, editorX, if right then editor else 0.0)
      place(3, editorX, editor)
      Size(width.lp, height.lp)

  def apply(right: Signal[Boolean], sidebar: View, left: View, other: View, empty: View): View =
    Layout("workspace-panes", right, policy)(sidebar, left, other, empty)
