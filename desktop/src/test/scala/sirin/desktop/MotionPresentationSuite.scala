package sirin.desktop

import java.nio.file.Path
import scala.concurrent.duration.*
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

/** The A-0b motion program (examples/authoring/sirin/examples/ReducedMotion.scala) as a behavioral test. The cell is
  * lifted out of the component so the test can observe application state.
  */
class MotionPresentationSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")

  private def program(scope: Scope, expanded: Cell[Boolean]): View =
    val position = scope.animate(expanded.map(e => if e then 180.0 else 0.0))
    val offset = position.map(x => Point(x.lp, Lp.zero))
    val alpha = scope.animate(expanded.map(e => if e then 1.0 else 0.5))
    val toggle = scope.action[Unit]("motion.toggle", "Toggle target")(_ => expanded.update(!_))
    Column(
      Button(toggle),
      Button("Moving target").command(toggle).offset(offset).opacity(alpha),
      Show(expanded)(Text("Motion preference changes presentation, never application state"))
    )

  private final class Fixture extends AutoCloseable:
    val clock = new ManualClock
    val runtime = new HeadlessRuntime(clock = clock)
    val owner = runtime.scope("motion")
    val expanded = owner.cell(false)
    val unicode = NativeUnicode.open(65536)
    val mounted = runtime.mount(owner, program(owner, expanded))
    runtime.observe(mounted.snapshot)(_ => ())
    val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
    val root = Path.of("..").toAbsolutePath.normalize()
    val renderer = new RenderSession(
      root.resolve("graphics/src/test/resources/fonts"),
      root.resolve("desktop/target/motion"),
      64
    )
    var generation = 0L
    var tree = CapturedTree(Node(NodeId(Vector("empty")), Content.Empty), Map.empty)
    var layout = Option.empty[LayoutResult]
    def frame(): LayoutResult =
      runtime.drive(32)
      tree = runtime.present(
        runtime.withCommitted(reader =>
          reader(mounted.snapshot) match
            case Value.Ok(snapshot) =>
              CommittedTree.captureWithBindings(snapshot, reader)
            case Value.Failed(error) =>
              throw error
        )
      )
      controller.update(tree)
      generation += 1
      val state = controller.interaction.state
      val request = FrameRequest(
        generation,
        FramePayload(controller.tree, 480, 160, 480, 160, state.scrolls, state.focused, state.keyboard)
      )
      val rendered = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
      try
        controller.install(generation, rendered.layout)
        layout = Some(rendered.layout)
        rendered.layout
      finally rendered.close()
    def bounds(name: String): Rect =
      val parts = layout.get.semanticParts.map(_._2).filter(_.semantics.name.contains(name))
      assertEquals(parts.size, 1, s"one $name")
      parts.head.bounds
    def opacity: Vector[Double] =
      def visit(node: Node): Vector[Double] =
        node.content match
          case Content.Styled(Hint.Opacity(alpha), child) =>
            alpha +: visit(child)
          case Content.Styled(_, child) =>
            visit(child)
          case Content.Reference(_, child) =>
            visit(child)
          case Content.CustomLayout(_, children) =>
            children.flatMap((_, child) => visit(child))
          case _ =>
            Vector.empty
      visit(tree.tree)
    def click(at: Rect): Unit =
      val x = at.origin.x.toDouble + at.size.width.toDouble / 2
      val y = at.origin.y.toDouble + at.size.height.toDouble / 2
      controller.handle(HostInput.Down(x, y), Mods.none, 1)
      frame()
      controller.handle(HostInput.Up(x, y), Mods.none, 1)
      frame()
    def state: Boolean = runtime.withCommitted(_(expanded)) match
      case Value.Ok(value) =>
        value
      case Value.Failed(error) =>
        throw error
    def close(): Unit =
      renderer.close()
      unicode.close()
      runtime.close()

  test("offset motion moves drawing, hit testing and semantics together; ticks write no application state") {
    val f = new Fixture
    try
      f.frame()
      val toggle = f.bounds("Toggle target")
      val rest = f.bounds("Moving target")
      assertEquals(f.opacity, Vector(0.5))
      f.click(toggle)
      assert(f.state)
      val committed = f.runtime.driverSnapshot.transactions
      assert(f.runtime.animationWaitMillis >= 0, "a visible retarget schedules frames")
      f.clock.advanceBy(80.millis)
      f.frame()
      val halfway = f.bounds("Moving target")
      assertEqualsDouble(halfway.origin.x.toDouble, rest.origin.x.toDouble + 90, 1e-6)
      assertEquals(halfway.origin.y, rest.origin.y)
      assertEquals(halfway.size, rest.size)
      assertEqualsDouble(f.opacity.head, 0.75, 1e-9)
      f.clock.advanceBy(80.millis)
      f.frame()
      val moved = f.bounds("Moving target")
      assertEqualsDouble(moved.origin.x.toDouble, rest.origin.x.toDouble + 180, 1e-6)
      assertEquals(f.opacity, Vector.empty, "full opacity paints without a group")
      assertEquals(f.runtime.animationWaitMillis, -1)
      assertEquals(f.runtime.driverSnapshot.transactions, committed, "ticks run outside transactions")
      assert(moved.origin.x.toDouble > rest.origin.x.toDouble + rest.size.width.toDouble)
      f.click(rest)
      assert(f.state, "the vacated position no longer hits the target")
      f.click(moved)
      assert(!f.state, "the target is clickable where it appears")
    finally f.close()
  }

  test("reduced motion reaches the presented target in the next frame and schedules no animation frames") {
    val f = new Fixture
    try
      f.runtime.reducedMotion = true
      f.frame()
      val toggle = f.bounds("Toggle target")
      val rest = f.bounds("Moving target")
      f.click(toggle)
      assert(f.state)
      assertEquals(f.runtime.animationWaitMillis, -1)
      assertEqualsDouble(f.bounds("Moving target").origin.x.toDouble, rest.origin.x.toDouble + 180, 1e-6)
      assertEquals(f.opacity, Vector.empty)
      f.click(toggle)
      assert(!f.state)
      assertEquals(f.runtime.animationWaitMillis, -1)
      assertEquals(f.bounds("Moving target"), rest)
      assertEquals(f.opacity, Vector(0.5))
    finally f.close()
  }

  test("unmounting the animating content stops its frames") {
    val f = new Fixture
    try
      f.frame()
      f.click(f.bounds("Toggle target"))
      f.clock.advanceBy(40.millis)
      f.frame()
      assert(f.runtime.animationWaitMillis >= 0)
      ok(
        f.runtime.transact {
          f.mounted.update(Text("replaced"))
        }
      )
      f.frame()
      assertEquals(f.runtime.animationWaitMillis, -1)
      assertEquals(f.layout.get.semanticParts.map(_._2.semantics), Vector(Semantics(Role.Label, Some("replaced"))))
    finally f.close()
  }
