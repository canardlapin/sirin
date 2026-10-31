package sirin.host

import sirin.*
import sirin.engine.*

/** A headless host loop for the public scroll: lay out, write viewport reports back until they settle, present. */
private[host] object ScrollSeam:
  def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        throw new AssertionError(s"expected commit: $other")

  /** A wheel-transparent focusable block of fixed height, so no text services are needed. */
  private val blockDefinition =
    new Element.Simple[Double]:
      def paint(state: Double, size: Size, canvas: Canvas)(using Committed): Unit = ()
      override def measure(state: Double, c: Constraints)(using Committed): Size =
        Size(c.maxWidth.min(100.lp).max(c.minWidth), Lp(state).max(c.minHeight).min(c.maxHeight))
  def block(height: Double): View = Element.simple("block", Signal.const(height), blockDefinition, focusable = true)

  def surface(runtime: HeadlessRuntime): Node =
    val mounted = runtime.mount(runtime.scope("surface"), Scroll(Spacer(10.lp)))
    runtime.observe(mounted.snapshot)(_ => ())
    val tree = runtime.withCommitted(reader =>
      reader(mounted.snapshot) match
        case Value.Ok(snapshot) =>
          CommittedTree.capture(snapshot, reader)
        case Value.Failed(error) =>
          throw error
    )
    tree.content match
      case Content.CustomLayout(_, children) =>
        children.head._2
      case other =>
        throw new AssertionError(s"expected the scroll layout: $other")

  final class Harness(view: View, width: Double = 100, height: Double = 80) extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val owner = runtime.scope("scroll")
    val mounted = runtime.mount(owner, view)
    runtime.observe(mounted.snapshot)(_ => ())
    val interaction = new Interaction
    val elements = new ElementInput(runtime, interaction)
    private var reported = Map.empty[Long, Viewport]
    private var epoch = 0L
    var shown: LayoutResult = frame()
    var rounds = 0

    def frame(): LayoutResult =
      rounds = 0
      var result = Option.empty[LayoutResult]
      while result.isEmpty do
        rounds += 1
        require(rounds < 20, "viewport feedback did not settle")
        val captured = runtime.withCommitted(reader =>
          reader(mounted.snapshot) match
            case Value.Ok(snapshot) =>
              CommittedTree.captureWithBindings(snapshot, reader)
            case Value.Failed(error) =>
              throw error
        )
        val laid = sirin.host.Layout(captured.tree, width, height)(_ => throw new AssertionError("no text"))
        val changed = laid.viewports.filter(v => !reported.get(v.instance).contains(v.viewport))
        if changed.isEmpty then
          epoch += 1
          interaction.install(InteractionSnapshot(epoch, laid))
          elements.update(captured.elements)
          elements.install(laid)
          result = Some(laid)
        else
          ok(runtime.transact(changed.foreach(v => mounted.reportViewport(v.instance, v.viewport))))
          reported = reported ++ changed.map(v => v.instance -> v.viewport)
      shown = result.get
      shown

    def layouts(name: String): Vector[Placed] =
      def visit(p: Placed): Vector[Placed] =
        val own =
          p.node.content match
            case Content.CustomLayout(frame, _) if frame.name == name =>
              Vector(p)
            case _ =>
              Vector.empty
        own ++ p.children.flatMap(visit)
      visit(shown.root)

    def report(name: String): Viewport =
      val instances = layouts(name).map(_.node.content.asInstanceOf[Content.CustomLayout].frame.instance).toSet
      val found = shown.viewports.filter(v => instances(v.instance))
      require(found.size == 1, s"one $name report: $found")
      found.head.viewport

    def key(key: Key): Unit =
      ok(elements.key(KeyEvent(key, Mods.none, false)).get)
      frame()

    def wheel(x: Double, y: Double, delta: Double): Boolean =
      val handled = elements.wheel(x, y, delta, Mods.none).map(ok).getOrElse(false)
      frame()
      handled

    def focusScroll(): Unit =
      val surface = interaction.presented.targets.find(_.element.exists(_.name == "scroll")).get
      interaction.handle(HostInput.Down(surface.clip.x + 1, surface.clip.y + 1))
      interaction.handle(HostInput.Up(surface.clip.x + 1, surface.clip.y + 1))
      assertFocused(surface.id)

    private def assertFocused(id: NodeId): Unit =
      require(interaction.state.focused.contains(id), s"expected focus on $id, got ${interaction.state.focused}")

    def close(): Unit = runtime.close()

class ScrollSeamSuite extends munit.FunSuite:
  import ScrollSeam.*

  test("a virtual list reports its presented range and realizes exactly the rows it covers") {
    var source = Option.empty[KeyedCell[Int, Int]]
    val view = Component[Unit]("list") { (scope, _) =>
      val cell = scope.keyed(Vector.empty[Int])(identity)
      source = Some(cell)
      Scroll(VirtualRows(cell, 20.lp, 0)(_ => Spacer(4.lp)))
    }
    val h = new Harness(view.view)
    try
      ok(h.runtime.transact(source.get.setAll(0 until 100)))
      h.frame()
      def realized: Int = h.layouts("virtual-rows").head.children.size
      val start = h.report("virtual-rows")
      assertEquals(start.size, Size(100.lp, 2000.lp))
      assertEquals(start.visible, Rect(Point.origin, Size(100.lp, 80.lp)))
      assertEquals(realized, 4)
      val outer = h.report("scroll")
      assertEquals(outer.size, Size(100.lp, 80.lp))
      assertEquals(outer.content, Size(100.lp, 2000.lp), "the scroll's content extent is the full list")
      h.focusScroll()
      h.key(Key.PageDown)
      assertEquals(h.report("virtual-rows").visible.origin.y, 72.lp)
      assertEquals(realized, 5, "rows 3 through 7 cover 72 to 152")
      assertEquals(h.layouts("virtual-rows").head.children.head.bounds.y, -12.0)
      assert(h.wheel(10, 10, -2))
      assertEquals(h.report("virtual-rows").visible.origin.y, 152.lp)
      h.key(Key.End)
      assertEquals(h.report("virtual-rows").visible.origin.y, 1920.lp, "End clamps to the presented extent")
      h.key(Key.ArrowDown)
      assertEquals(h.report("virtual-rows").visible.origin.y, 1920.lp)
      h.key(Key.Home)
      assertEquals(h.report("virtual-rows").visible.origin.y, Lp.zero)
      ok(h.runtime.transact(source.get.setAll(Vector.empty)))
      h.frame()
      assertEquals(h.report("virtual-rows").visible.size.height, Lp.zero, "an empty list shows nothing")
      assertEquals(realized, 0)
    finally h.close()
  }

  test("wheel input over content reaches the scroller beneath it unless the content handles it") {
    val greedy = Element(
      "greedy",
      ElementInputs(Signal.const(()), Signal.const(()), Signal.const(()), Signal.const(()), Presentation.const(())),
      new Element[Unit, Unit, Unit, Unit, Unit]:
        def paint(state: Unit, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit = ()
        override def measure(state: Unit, c: Constraints)(using Committed): Size =
          Size(c.maxWidth.min(100.lp).max(c.minWidth), 50.lp.max(c.minHeight).min(c.maxHeight))
      ,
      handlers = Some(new ElementHandlers:
        override def wheel(event: WheelEvent)(using UiTxn): Boolean = true
      )
    )
    val h = new Harness(Scroll(Column(block(50), block(50), greedy, block(50))))
    try
      def offset: Lp = Lp(-h.layouts("scroll").head.children(1).bounds.y)
      assert(h.wheel(10, 10, -1), "a block declines the wheel, so the scroll takes it")
      assertEquals(offset, 40.lp)
      val target = h.interaction.presented.targets.find(_.element.exists(_.name == "greedy")).get
      assert(h.wheel(target.clip.x + 1, target.clip.y + 1, -1))
      assertEquals(offset, 40.lp, "content that handles the wheel keeps it")
    finally h.close()
  }

  test("a scroll keeps its offset and its content's identity across new descriptions and loses both on remount") {
    def scene(blocks: Int): View = Scroll(Column((0 until blocks).map(_ => block(50))*))
    val h = new Harness(scene(4))
    try
      def offset: Lp = Lp(-h.layouts("scroll").head.children(1).bounds.y)
      def blocks: Vector[NodeId] =
        h.interaction.presented.targets.filter(_.element.exists(_.name == "block")).map(_.id)
      assert(h.wheel(10, 10, -1))
      assertEquals(offset, 40.lp)
      val before = blocks
      ok(h.runtime.transact(h.mounted.update(scene(5))))
      h.frame()
      assertEquals(offset, 40.lp, "the offset belongs to the mounted scroll")
      assert(before.nonEmpty && before.forall(blocks.contains), "the visible content stays mounted")
      ok(h.runtime.transact(h.mounted.update(Column(scene(5)))))
      h.frame()
      assertEquals(offset, Lp.zero, "a scroll in a new position is a new scroll")
      assert(!before.exists(blocks.contains))
    finally h.close()
  }

  test("a ref on a scroll focuses its surface, and keys then scroll it") {
    val view = Component[Unit]("refs") { (scope, _) =>
      Scroll(Column(block(50), block(50), block(50))).ref(scope.ref())
    }
    val h = new Harness(view.view)
    try
      val id =
        h.shown.root.node.content match
          case Content.Reference(reference, _) =>
            reference
          case other =>
            fail(s"expected the ref on the scroll: $other")
      assert(h.interaction.focusRef(id))
      assert(h.interaction.focused.exists(_.element.exists(_.name == "scroll")))
      h.key(Key.PageDown)
      assertEquals(h.report("scroll").content, Size(100.lp, 150.lp))
      assertEquals(
        h.elements.key(KeyEvent(Key.PageDown, Mods.Primary, false)).map(ok),
        Some(false),
        "a chord the scroll does not use falls through to shortcuts"
      )
      assertEquals(-h.layouts("scroll").head.children(1).bounds.y, 70.0, "PageDown clamps to 150 - 80")
    finally h.close()
  }
