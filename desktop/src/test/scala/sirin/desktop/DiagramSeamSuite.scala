package sirin.desktop

import java.nio.file.{Files, Path}
import demo.diagram.*
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics as g
import sirin.graphics.unicode.NativeUnicode
import sirin.render.BatchGpu

class DiagramSeamSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other                         => fail(s"expected commit: $other")
  private def value[A](runtime: HeadlessRuntime, signal: Signal[A]): A = runtime.read(signal) match
    case Value.Ok(value)     => value
    case Value.Failed(error) => throw error

  private final class Fixture(
      initial: DiagramGraph = DiagramGraph.sample,
      fullView: Boolean = false,
      direct: Boolean = false,
      width: Int = 800,
      height: Int = 600
  ) extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val owner = runtime.scope("diagram")
    val editor = new DiagramEditor(owner, initial)
    val mounted = runtime.mount(owner, if fullView then editor.view else editor.surface)
    owner.observe(mounted.snapshot)(_ => ())
    private val unicode = NativeUnicode.open()
    val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
    private val batch = Option.when(direct)(BatchGpu.open())
    val renderer = new RenderSession(
      Path.of("../graphics/src/test/resources/fonts"),
      Path.of("target/diagram"),
      tileEdge = if direct then 128 else 64,
      batch = batch
    )
    private var serial = 0L
    var lastLayout = Option.empty[LayoutResult]
    var lastTree = Option.empty[CapturedTree]
    var pixels = Option.empty[g.ImageSnapshot]
    var tileCount = 0
    def gpuStats: Option[sirin.render.BatchGpuStats] = batch.map(_.stats)
    def frame(capture: Boolean = false): LayoutResult =
      runtime.drive(128)
      val tree = runtime.withCommitted(reader =>
        reader(mounted.snapshot) match
          case Value.Ok(snapshot)  => CommittedTree.captureWithBindings(snapshot, reader)
          case Value.Failed(error) => throw error
      )
      lastTree = Some(tree)
      controller.update(tree)
      serial += 1
      val focus = controller.interaction.state
      val request = FrameRequest(
        serial,
        FramePayload(
          controller.tree,
          width,
          height,
          width,
          height,
          focus.scrolls,
          focus.focused,
          focus.keyboard,
          focus.pressed.map(_.target),
          editors = controller.snapshots
        )
      )
      val output = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
      try
        if direct then
          assert(output.gpuFrame.nonEmpty)
          assertEquals(output.readbackBytes, 0L)
        controller.install(serial, output.layout)
        runtime.takeFocusRequests().foreach(controller.focusReference)
        lastLayout = Some(output.layout)
        tileCount = output.tiles
        if capture then pixels = Some(output.gpuFrame.fold(output.pixels)(_.readback()))
        output.layout
      finally output.close()
    def surface: Placed = lastLayout.get.elements.find(_.node.content match
      case Content.Custom(frame) => frame.name == "diagram"
      case _                     => false).get
    def point(local: Point): (Double, Double) =
      (surface.bounds.x + local.x.toDouble, surface.bounds.y + local.y.toDouble)
    def input(event: HostInput, mods: Mods = Mods.none): Unit = controller.handle(event, mods)
    def close(): Unit =
      renderer.close()
      unicode.close()
      runtime.close()

  test("presented pointer capture, wheel, keyboard and semantic actions drive one public diagram") {
    val fixture = new Fixture()
    try
      import fixture.*
      frame(capture = true)
      Files.createDirectories(Path.of("target/diagram"))
      FrameWorker.save(pixels.get, Path.of("target/diagram/reference.png"))
      val textShapes = renderer.shaped
      input(HostInput.Down(50, 110))
      assert(controller.elements.captured.nonEmpty)
      frame()
      input(HostInput.Move(90, 150, primary = true))
      val moved = frame()
      assertEquals(value(runtime, editor.graph), DiagramGraph.sample)
      val part = moved.semanticParts.find(_._2.part == PartId(1)).get._2
      assertEquals(part.bounds.origin, Point(80.lp, 140.lp))
      input(HostInput.Up(100, 160))
      frame()
      assertEquals(value(runtime, editor.graph).nodes.head.at, Point(90.lp, 150.lp))
      assertEquals(controller.elements.captured, None)
      assertEquals(renderer.shaped, textShapes)
      input(HostInput.KeyDown(Key.Character('z'), Mods.Primary))
      frame()
      assertEquals(value(runtime, editor.graph), DiagramGraph.sample)
      input(HostInput.Wheel(300, 200, 1))
      frame()
      val camera = value(runtime, editor.camera)
      assert(camera.scale > 1)
      assertEqualsDouble(camera.world(Point(300.lp, 200.lp)).x.toDouble, 300.0, 1e-8)
      assertEqualsDouble(camera.world(Point(300.lp, 200.lp)).y.toDouble, 200.0, 1e-8)
      val instance = lastTree.get.elements.keys.head
      assertEquals(
        controller.elements.action(instance, PartId(2), SemanticAction.Activate),
        Some(CommitResult.Committed(true))
      )
      frame()
      assertEquals(value(runtime, editor.selection), Set(2L))
      assertEquals(controller.elements.action(instance, PartId(9999), SemanticAction.Activate), None)
      val before = value(runtime, editor.graph)
      input(HostInput.KeyDown(Key.ArrowRight, Mods.none))
      frame()
      assertEquals(value(runtime, editor.graph).nodes(1).at.x, before.nodes(1).at.x + 8.lp)
      val at = camera.screen(value(runtime, editor.graph).nodes(1).at + Point(10.lp, 10.lp))
      input(HostInput.Down(at.x.toDouble, at.y.toDouble))
      frame()
      input(HostInput.Move(at.x.toDouble + 100, at.y.toDouble + 50, primary = true))
      frame()
      input(HostInput.Deactivate)
      frame()
      assert(!value(runtime, editor.dragging))
      assertEquals(controller.elements.captured, None)
      assertEquals(value(runtime, editor.graph).nodes(1).at.x, before.nodes(1).at.x + 8.lp)
    finally fixture.close()
  }

  test("public Layout places and retires the floating toolbar on the same transformed node geometry") {
    val fixture = new Fixture(fullView = true)
    try
      import fixture.*
      val initial = frame()
      val baseButtons = initial.elements.count(_.node.content.asInstanceOf[Content.Custom].frame.name == "button")
      val (x, y) = point(Point(50.lp, 110.lp))
      input(HostInput.Down(x, y))
      frame()
      input(HostInput.Up(x, y))
      val selected = frame(capture = true)
      assertEquals(
        selected.elements.count(_.node.content.asInstanceOf[Content.Custom].frame.name == "button"),
        baseButtons + 2
      )
      val part = selected.semanticParts.find(_._2.part == PartId(1)).get._2
      assertEquals(part.bounds.origin, Point((surface.bounds.x + 40).lp, (surface.bounds.y + 100).lp))
      FrameWorker.save(pixels.get, Path.of("target/diagram/with-tools.png"))
      ok(runtime.transact { editor.select(Set.empty) })
      val cleared = frame()
      assertEquals(
        cleared.elements.count(_.node.content.asInstanceOf[Content.Custom].frame.name == "button"),
        baseButtons
      )
    finally fixture.close()
  }

  test("normal retained GPU path renders a bounded 512-node scene and keeps readback explicit") {
    val fixture = new Fixture(DiagramGraph.grid(512), direct = true, width = 800, height = 600)
    try
      import fixture.*
      ok(runtime.transact { editor.zoom(Point.origin, 0.25) })
      val layout = frame(capture = true)
      val parts = layout.semanticParts.filter(_._2.semantics.role == Role.GraphicNode)
      assertEquals(parts.size, 512)
      assertEquals(layout.text.requests.size, 512)
      assertEquals(value(runtime, editor.graph).edges.size, 511)
      assert(tileCount > 35 && tileCount <= 256)
      assertEquals(gpuStats.get.pinnedEntries, 0L)
      println(s"Diagram: 512 nodes, 511 edges, 512 labels, $tileCount GPU tiles; ${gpuStats.get}")
      assert(pixels.get.pixelsCopy.exists(_ != 0))
      FrameWorker.save(pixels.get, Path.of("target/diagram/dense-gpu.png"))
      val shaped = renderer.shaped
      frame()
      assertEquals(renderer.shaped, shaped)
      ok(runtime.transact { editor.zoom(Point.origin, 2) })
      val zoomed = frame()
      assert(zoomed.text.requests.size < 512)
      assert(zoomed.semanticParts.size < layout.semanticParts.size)
    finally fixture.close()
  }

  test("recorded diagram really uses cubic paths and refuses stale handlers after retirement") {
    val fixture = new Fixture()
    try
      import fixture.*
      val layout = frame()
      def commands(drawing: g.Drawing): Vector[g.DrawCommand] = drawing.commands.flatMap {
        case g.DrawCommand.Draw(child) => commands(child)
        case command                   => Vector(command)
      }
      val paths = commands(layout.drawing(DevelopmentTheme)).collect { case g.DrawCommand.Stroke(path, _, _) => path }
      assert(paths.exists(_.segments.exists(_.isInstanceOf[g.PathSegment.CubicTo])))
      val binding = lastTree.get.elements.values.head
      input(HostInput.Down(50, 110))
      frame()
      input(HostInput.Move(90, 150, primary = true))
      frame()
      ok(runtime.transact { mounted.update(View.empty) })
      runtime.drive(128)
      assert(!value(runtime, editor.dragging))
      assertEquals(value(runtime, editor.graph), DiagramGraph.sample)
      assertEquals(
        runtime.transact { binding.action(PartId(1), SemanticAction.Activate) },
        CommitResult.Failed(KernelError.DisposedScope)
      )
    finally fixture.close()
  }
