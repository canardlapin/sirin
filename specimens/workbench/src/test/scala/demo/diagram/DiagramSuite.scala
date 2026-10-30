package demo.diagram

import sirin.*
import sirin.engine.*

class DiagramSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other                         => fail(s"expected commit: $other")
  private def value[A](r: HeadlessRuntime, signal: Signal[A]): A = r.read(signal) match
    case Value.Ok(value)     => value
    case Value.Failed(error) => throw error
  private def pointer(
      phase: PointerPhase,
      x: Double,
      y: Double,
      id: Long = 1,
      button: PointerButton = PointerButton.Primary,
      mods: Mods = Mods.none,
      captured: Boolean = false
  ): PointerEvent =
    PointerEvent(
      phase,
      Point(x.lp, y.lp),
      button,
      mods,
      1,
      if id == 0 then Hit.Self else Hit.Part(PartId(id)),
      captured
    )
  private def withEditor(test: (HeadlessRuntime, DiagramEditor, ElementHandlers) => Unit): Unit =
    val r = new HeadlessRuntime
    try
      val editor = new DiagramEditor(r.scope("diagram"))
      test(r, editor, new DiagramHandlers(editor))
    finally r.close()
  private def press(r: HeadlessRuntime, handler: ElementHandlers, event: PointerEvent): PointerResponse =
    ok(r.transact { handler.pointer(event) })
  private def dispatch(r: HeadlessRuntime, command: Command[Unit, Unit]): Unit =
    assert(ok(r.transact { command.dispatch(()) }).isInstanceOf[Dispatched.Started[?]])

  test("many drag samples preview only; release records exactly one undo and redo step") {
    withEditor { (r, editor, handler) =>
      val before = value(r, editor.graph)
      assertEquals(press(r, handler, pointer(PointerPhase.Down, 50, 110)), PointerResponse.Capture)
      for i <- 1 to 100 do press(r, handler, pointer(PointerPhase.Move, 50 + i, 110 + i, captured = true))
      assertEquals(value(r, editor.graph), before)
      assertEquals(value(r, editor.scene).graph.nodes.head.at, Point(140.lp, 200.lp))
      assert(!value(r, editor.undo.enabled))
      assertEquals(press(r, handler, pointer(PointerPhase.Up, 170, 230, captured = true)), PointerResponse.Release)
      val moved = value(r, editor.graph)
      assertEquals(moved.nodes.head.at, Point(160.lp, 220.lp))
      assert(!value(r, editor.dragging))
      dispatch(r, editor.undo)
      assertEquals(value(r, editor.graph), before)
      assert(!value(r, editor.undo.enabled))
      dispatch(r, editor.redo)
      assertEquals(value(r, editor.graph), moved)
    }
  }
  test("Escape and pointer cancellation restore selection and discard graph/camera previews") {
    withEditor { (r, editor, handler) =>
      ok(r.transact { editor.select(Set(2)) })
      press(r, handler, pointer(PointerPhase.Down, 50, 110))
      press(r, handler, pointer(PointerPhase.Move, 80, 140, captured = true))
      assert(ok(r.transact { handler.key(KeyEvent(Key.Escape, Mods.none, false)) }))
      assertEquals(value(r, editor.graph), DiagramGraph.sample)
      assertEquals(value(r, editor.selection), Set(2L))
      assert(!value(r, editor.undo.enabled))
      press(r, handler, pointer(PointerPhase.Down, 5, 5, 0, PointerButton.Secondary))
      press(r, handler, pointer(PointerPhase.Move, 80, 50, 0, PointerButton.Secondary, captured = true))
      assertEquals(value(r, editor.camera).origin, Point(75.lp, 45.lp))
      press(r, handler, pointer(PointerPhase.Cancel, 0, 0, 0, PointerButton.Secondary, captured = true))
      assertEquals(value(r, editor.camera), DiagramCamera())
      assert(!value(r, editor.dragging))
    }
  }
  test("pan changes camera only; mismatched button release does not end the gesture") {
    withEditor { (r, editor, handler) =>
      press(r, handler, pointer(PointerPhase.Down, 20, 30, 0, PointerButton.Secondary))
      press(r, handler, pointer(PointerPhase.Move, 100, 90, 0, PointerButton.Secondary, captured = true))
      assertEquals(press(r, handler, pointer(PointerPhase.Up, 100, 90, 0, captured = true)), PointerResponse.Handled)
      assert(value(r, editor.dragging))
      press(r, handler, pointer(PointerPhase.Up, 120, 100, 0, PointerButton.Secondary, captured = true))
      assertEquals(value(r, editor.camera).origin, Point(100.lp, 70.lp))
      assertEquals(value(r, editor.graph), DiagramGraph.sample)
      assert(!value(r, editor.undo.enabled))
    }
  }
  test("zoom preserves its pointer anchor and clamps scale; invalid attempts leave the scene intact") {
    withEditor { (r, editor, _) =>
      val pivot = Point(235.lp, 145.lp)
      val world = value(r, editor.camera).world(pivot)
      ok(r.transact { editor.zoom(pivot, 1.5) })
      val after = value(r, editor.camera).world(pivot)
      assertEqualsDouble(after.x.toDouble, world.x.toDouble, 1e-10)
      assertEqualsDouble(after.y.toDouble, world.y.toDouble, 1e-10)
      ok(r.transact { editor.zoom(pivot, Double.MaxValue) })
      assertEquals(value(r, editor.camera).scale, 4.0)
      ok(r.transact { editor.zoom(pivot, Double.MinPositiveValue) })
      assertEquals(value(r, editor.camera).scale, 0.125)
      val stable = value(r, editor.scene)
      assert(r.transact { editor.zoom(pivot, Double.NaN) }.isInstanceOf[CommitResult.Refused[?]])
      assertEquals(value(r, editor.scene), stable)
    }
  }
  test("selection, connect, delete and undo preserve graph integrity and stable identities") {
    withEditor { (r, editor, handler) =>
      press(r, handler, pointer(PointerPhase.Down, 50, 110))
      press(r, handler, pointer(PointerPhase.Up, 50, 110, captured = true))
      press(r, handler, pointer(PointerPhase.Down, 450, 160, 3, mods = Mods.Shift))
      press(r, handler, pointer(PointerPhase.Up, 450, 160, 3, captured = true))
      assertEquals(value(r, editor.selection), Set(1L, 3L))
      dispatch(r, editor.connect)
      assert(value(r, editor.graph).edges.contains(DiagramEdge(1, 3)))
      assert(!value(r, editor.connect.enabled))
      dispatch(r, editor.delete)
      assertEquals(value(r, editor.graph).nodes.map(_.id), Vector(2L))
      assertEquals(value(r, editor.graph).edges, Vector.empty)
      dispatch(r, editor.undo)
      assertEquals(value(r, editor.graph).nodes.map(_.id), Vector(1L, 2L, 3L))
      dispatch(r, editor.add)
      val added = value(r, editor.selection).head
      dispatch(r, editor.undo)
      dispatch(r, editor.add)
      assert(value(r, editor.selection).head > added)
      assert(!value(r, editor.redo.enabled))
    }
  }
  test("history is capped and group movement clamps without distorting relative positions") {
    withEditor { (r, editor, _) =>
      ok(r.transact { editor.select(Set(1, 2)) })
      val initial = value(r, editor.graph)
      for _ <- 1 to 80 do ok(r.transact { editor.moveSelection(Point(1.lp, 0.lp)) })
      for _ <- 1 to 64 do dispatch(r, editor.undo)
      assert(!value(r, editor.undo.enabled))
      assertEquals(value(r, editor.graph).nodes.head.at.x, initial.nodes.head.at.x + 16.lp)
      ok(r.transact { editor.moveSelection(Point(2000000.lp, 2000000.lp)) })
      val graph = value(r, editor.graph)
      assertEquals(graph.nodes(1).at.x.toDouble, DiagramLimits.coordinate)
      assertEquals((graph.nodes(1).at.x - graph.nodes.head.at.x).toDouble, 200.0)
      assertEquals((graph.nodes(1).at.y - graph.nodes.head.at.y).toDouble, -30.0)
    }
  }
  test("refused release rolls back history and retains the pending preview for a later release") {
    withEditor { (r, editor, handler) =>
      press(r, handler, pointer(PointerPhase.Down, 50, 110))
      press(r, handler, pointer(PointerPhase.Move, 90, 150, captured = true))
      val before = value(r, editor.scene)
      assertEquals(
        r.transact {
          handler.pointer(pointer(PointerPhase.Up, 100, 160, captured = true))
          UiTxn.refuse("keep preview")
        },
        CommitResult.Refused("keep preview")
      )
      assertEquals(value(r, editor.scene), before)
      assertEquals(value(r, editor.graph), DiagramGraph.sample)
      press(r, handler, pointer(PointerPhase.Up, 100, 160, captured = true))
      dispatch(r, editor.undo)
      assertEquals(value(r, editor.graph), DiagramGraph.sample)
      assert(!value(r, editor.undo.enabled))
    }
  }
  test("focus loss and removal discard the preview without editing the committed graph") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("diagram")
      val editor = new DiagramEditor(owner)
      val handler = new DiagramHandlers(editor)
      val mounted = r.mount(owner, editor.surface)
      owner.observe(mounted.snapshot)(_ => ())
      r.drive(64)
      val ref = r.attachments.head.reference
      ok(r.transact { r.publishFocus(Set(ref), Set(ref), false) })
      r.drive(64)
      press(r, handler, pointer(PointerPhase.Down, 50, 110))
      press(r, handler, pointer(PointerPhase.Move, 90, 150, captured = true))
      ok(r.transact { mounted.update(View.empty) })
      r.drive(64)
      assert(!value(r, editor.dragging))
      assertEquals(value(r, editor.graph), DiagramGraph.sample)
      assert(!value(r, editor.undo.enabled))
    finally r.close()
  }
  test("node, edge, identity, Unicode and coordinate limits reject invalid input") {
    val sample = DiagramGraph.sample
    intercept[IllegalArgumentException](DiagramGraph(sample.nodes :+ sample.nodes.head, sample.edges))
    intercept[IllegalArgumentException](DiagramGraph(sample.nodes, Vector(DiagramEdge(1, 99))))
    intercept[IllegalArgumentException](DiagramGraph(sample.nodes, Vector(DiagramEdge(1, 1))))
    intercept[IllegalArgumentException](DiagramGraph(sample.nodes, Vector.fill(2)(DiagramEdge(1, 2))))
    intercept[IllegalArgumentException](DiagramNode(0, "bad", Point.origin))
    intercept[IllegalArgumentException](DiagramNode(1, "\ud800", Point.origin))
    intercept[IllegalArgumentException](DiagramNode(1, "bad", Point(Double.NaN.lp, 0.lp)))
    intercept[IllegalArgumentException](DiagramGraph.grid(513))
    val nodes = DiagramGraph.grid(50).nodes
    val edges = (for a <- 1L to 50L; b <- 1L to 50L if a != b yield DiagramEdge(a, b)).take(2049).toVector
    intercept[IllegalArgumentException](DiagramGraph(nodes, edges))
    assertEquals(DiagramGraph.grid(512).nodes.size, 512)
  }
  test("dense scene prepares only visible labels and exposes coherent hit and semantic geometry") {
    given Committed = new Committed {}
    val graph = DiagramGraph.grid(512)
    val viewport = Size(400.lp, 200.lp)
    val scene = DiagramScene(graph, DiagramCamera(), Set(1L))
    assertEquals(DiagramSurface.textRequests(scene, (), viewport).size, 9)
    val parts = DiagramSurface.semantics(scene, viewport)
    assertEquals(parts.size, 10)
    assertEquals(parts(1).semantics.positionInSet, Some((1, 512)))
    parts.tail.foreach { part =>
      val bounds = part.bounds
      val x = math.min(399.0, bounds.center.x.toDouble)
      val y = math.min(199.0, bounds.center.y.toDouble)
      assertEquals(DiagramSurface.hitTest(scene, viewport, Point(x.lp, y.lp)), Hit.Part(part.part))
    }
    val distant = scene.copy(camera = DiagramCamera(Point(900000.lp, 900000.lp)))
    assertEquals(DiagramSurface.textRequests(distant, (), viewport), Nil)
    assertEquals(DiagramSurface.semantics(distant, viewport).size, 1)
    val overlapping = DiagramScene(
      DiagramGraph(
        Vector(graph.nodes.head, graph.nodes(1).copy(at = graph.nodes.head.at)),
        Vector.empty
      ),
      DiagramCamera(),
      Set.empty
    )
    assertEquals(DiagramSurface.hitTest(overlapping, viewport, Point(30.lp, 30.lp)), Hit.Part(PartId(2)))
  }

  test("short, backward and vertical connectors prepare without cusps; coincident ports have no visible edge") {
    import sirin.graphics as g
    val target = g.RenderTarget(new g.DeviceGeneration("diagram-test", "geometry"), 800, 600, g.Affine.identity)
    for at <- Vector(Point(155.lp, 20.lp), Point(0.lp, 90.lp), Point(152.lp, 160.lp), Point(152.lp, 20.lp)) do
      val graph = DiagramGraph(
        Vector(DiagramNode(1, "A", Point(20.lp, 20.lp)), DiagramNode(2, "B", at)),
        Vector(DiagramEdge(1, 2))
      )
      val path = DiagramSurface.edge(
        DiagramScene(graph, DiagramCamera(), Set.empty),
        graph.edges.head,
        Size(800.lp, 600.lp)
      )
      if at == Point(152.lp, 20.lp) then assertEquals(path, None)
      else
        val drawing = g.Drawing.record(_.stroke(
          path.get,
          g.Paint.Solid(g.Color.rgba8(30, 80, 130)),
          g.StrokeStyle.logical(1.5.lp)
        ))
        assert(g.DrawingPreparation.prepare(drawing, target).isRight)
  }
