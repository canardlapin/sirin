package sirin.host

import sirin.*

/** Overlay geometry and popup placement in the window's overlay layer. */
class PopupLayoutSuite extends munit.FunSuite:
  test("overlay layers take the base's size and stack above it") {
    val h = new ScrollSeam.Harness(Column(Overlay(ScrollSeam.block(30), ScrollSeam.block(5), ScrollSeam.block(50))), 100, 80)
    try
      val overlay = h.layouts("overlay").head
      assertEquals(overlay.bounds.height, 30.0)
      assertEquals(overlay.children.map(c => (c.bounds.y, c.bounds.height)), Vector((0.0, 30.0), (0.0, 30.0), (0.0, 30.0)))
      val targets = h.interaction.presented.targets
      assertEquals(targets.size, 3)
      h.interaction.handle(HostInput.Down(5, 5))
      assertEquals(h.interaction.state.focused, Some(targets.last.id), "the last layer is on top")
    finally h.close()
  }

  test("placement sits outside the requested edge, flips when only the other side fits, and keeps margins") {
    val anchor = Box(100, 100, 40, 20)
    import PopupPlacement.position
    assertEquals(position(Placement.Below, anchor, 60, 30, 400, 300), (100.0, 124.0))
    assertEquals(position(Placement.Above, anchor, 60, 30, 400, 300), (100.0, 66.0))
    assertEquals(position(Placement.End, anchor, 60, 30, 400, 300), (144.0, 100.0))
    assertEquals(position(Placement.Start, anchor, 60, 30, 400, 300), (36.0, 100.0))
    assertEquals(position(Placement.Over, anchor, 60, 30, 400, 300), (100.0, 100.0))
    val low = Box(100, 260, 40, 20)
    assertEquals(position(Placement.Below, low, 60, 30, 400, 300), (100.0, 226.0), "no room below, so above")
    val edge = Box(380, 100, 20, 20)
    assertEquals(position(Placement.Below, edge, 60, 30, 400, 300)._1, 332.0, "clamped inside the right margin")
    assertEquals(position(Placement.Below, anchor, 500, 30, 400, 300)._1, 0.0, "too wide for margins starts at 0")
  }
