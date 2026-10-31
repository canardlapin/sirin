package sirin.host

import sirin.*
import sirin.engine.*
import scala.annotation.unused

class ElementHostSuite extends munit.FunSuite:
  private val theme =
    new Theme:
      def color(
          @unused
          role: ColorRole
      ): Color = Color.rgba(20, 40, 60)
      def reducedMotion: Boolean = false
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")
  private def captured(r: HeadlessRuntime, m: MountedView): CapturedTree =
    r.read(m.snapshot)
    r.withCommitted(reader =>
      reader(m.snapshot) match
        case Value.Ok(s) =>
          CommittedTree.captureWithBindings(s, reader)
        case Value.Failed(error) =>
          throw error
    )
  private def layout(tree: CapturedTree): LayoutResult =
    sirin.host.Layout(tree.tree, 100, 80)(_ => fail("no text declared"))
  private class Surface extends Element[Int, Int, Int, Int, Unit]:
    var measures = 0
    def paint(
        state: Int,
        @unused
        presentation: Unit,
        size: Size,
        canvas: Canvas
    )(using
        @unused
        c: Committed
    ): Unit = canvas.fill(Rect(Point.origin, size), Color.rgba(state, 0, 0))
    override def measure(
        @unused
        state: Int,
        c: Constraints
    )(using
        @unused
        committed: Committed
    ): Size = {
      measures += 1
      c.biggest
    }
    override def hitTest(
        @unused
        state: Int,
        size: Size,
        at: Point
    )(using
        @unused
        c: Committed
    ): Hit =
      if Rect(Point.origin, size).contains(at) then
        Hit.Part(PartId(1))
      else
        Hit.Miss
    override def semantics(state: Int, size: Size)(using
        @unused
        c: Committed
    ): Seq[SemanticNode] = Vector(
      SemanticNode(
        PartId(1),
        Semantics(Role.GraphicNode, name = Some(state.toString)),
        Rect(Point.origin, size),
        Set(SemanticAction.Activate)
      )
    )
  private def element(value: Signal[Int], surface: Surface): View = Element(
    "surface",
    ElementInputs(value, value, value, value, Presentation.const(())),
    surface,
    focusable = true
  )

  test("named layout caches measurement and unplaced children stay mounted without paint/hit/semantics") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("layout")
      val state = s.cell(7)
      val surface = new Surface
      var leaked = Option.empty[LayoutChildren]
      val policy =
        new LayoutPolicy[Int]:
          def layout(
              @unused
              state: Int,
              c: Constraints,
              children: LayoutChildren
          )(using
              @unused
              committed: Committed
          ): Size =
            leaked = Some(children)
            val child = Constraints(20.lp, 20.lp, 30.lp, 30.lp)
            assertEquals(children.measure(Slot("shown"), child), Size(20.lp, 30.lp))
            assertEquals(children.measure(Slot("shown"), child), Size(20.lp, 30.lp))
            children.place(Slot("shown"), Point(90.lp, 70.lp))
            c.biggest
      val mounted = r.mount(
        s,
        sirin
          .Layout
          .named("named", state, policy)(
            Slot("shown") -> element(state, surface),
            Slot("hidden") -> element(state, new Surface)
          )
      )
      val tree = captured(r, mounted)
      assertEquals(tree.elements.size, 2)
      val result = layout(tree)
      assertEquals(surface.measures, 1)
      assertEquals(result.elements.size, 1)
      val part = result.semanticParts.head._2
      assertEquals(part.bounds, Rect(Point(90.lp, 70.lp), Size(10.lp, 10.lp)))
      assertEquals(InteractionSnapshot(1, result).targets.size, 1)
      result.drawing(theme)
      intercept[IllegalArgumentException](leaked.get.count)
    finally
      r.close()
  }
  test("duplicate placement, unknown slots, invalid sizes and undeclared multimeasurement refuse") {
    for mode <- 0 to 4 do
      val r = new HeadlessRuntime
      try
        val s = r.scope("invalid")
        val state = s.cell(1)
        val policy =
          new LayoutPolicy[Int]:
            def layout(
                @unused
                state: Int,
                c: Constraints,
                ch: LayoutChildren
            )(using
                @unused
                committed: Committed
            ): Size =
              val bounds = Constraints(10.lp, 10.lp, 10.lp, 10.lp)
              mode match
                case 0 =>
                  ch.place(0, Point.origin)
                case 1 =>
                  ch.measure(Slot("unknown"), bounds)
                  ()
                case 2 =>
                  ch.measure(0, bounds)
                  ch.place(0, Point.origin)
                  ch.place(0, Point.origin)
                case 3 =>
                  ch.measure(0, bounds)
                  ch.measure(0, Constraints(20.lp, 20.lp, 20.lp, 20.lp))
                  ()
                case _ =>
                  ()
              if mode == 4 then
                Size(Double.NaN.lp, 10.lp)
              else
                c.biggest
        val mounted = r.mount(s, sirin.Layout("invalid", state, policy)(element(state, new Surface)))
        intercept[IllegalArgumentException](layout(captured(r, mounted)))
      finally
        r.close()
  }
  test("capture survives geometry changes, sends local out-of-bounds moves and releases on up/removal") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("input")
      val state = s.cell(0)
      var seen = Vector.empty[PointerEvent]
      val surface =
        new Surface:
          override def pointer(e: PointerEvent)(using UiTxn): PointerResponse =
            seen :+= e
            if e.phase == PointerPhase.Down then {
              state.set(1)
              PointerResponse.Capture
            } else
              PointerResponse.Handled
          override def key(
              @unused
              e: KeyEvent
          )(using UiTxn): Boolean = {
            state.set(state.get + 1)
            true
          }
          override def action(
              @unused
              p: PartId,
              @unused
              a: SemanticAction
          )(using UiTxn): Boolean = {
            state.set(9)
            true
          }
      val mounted = r.mount(s, element(state, surface).padding(10.lp))
      val interaction = new Interaction
      val input = new ElementInput(r, interaction)
      def settle(): Unit =
        val tree = captured(r, mounted)
        val result = layout(tree)
        input.update(tree.elements)
        interaction.install(InteractionSnapshot(state.hashCode.toLong, result))
        input.install(result)
      settle()
      interaction.handle(HostInput.Down(15, 15))
      input.pointer(HostInput.Down(15, 15))
      val id = input.captured.get
      settle()
      assertEquals(input.captured, Some(id))
      input.pointer(HostInput.Move(200, 200, true))
      assertEquals(seen.last.position, Point(190.lp, 190.lp))
      assertEquals(seen.last.hit, Hit.Miss)
      assert(seen.last.captured)
      input.pointer(HostInput.Up(200, 200))
      assertEquals(input.captured, None)
      assertEquals(input.key(KeyEvent(Key.Enter, Mods.none, false)), Some(CommitResult.Committed(true)))
      assertEquals(input.action(id, PartId(999), SemanticAction.Activate), None)
      assertEquals(input.action(id, PartId(1), SemanticAction.Activate), Some(CommitResult.Committed(true)))
      ok(r.transact(mounted.update(View.empty)))
      settle()
      assertEquals(input.pointer(HostInput.Down(15, 15)), None)
    finally
      r.close()
  }
  test("render code cannot reuse an otherwise live committed graph reader") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("reader")
      val state = s.cell(1)
      val cell = state
      var saved = Option.empty[CommittedRead]
      val surface =
        new Surface:
          override def measure(
              @unused
              state: Int,
              c: Constraints
          )(using
              @unused
              committed: Committed
          ): Size =
            try
              saved.get.apply(cell)
            catch
              case _: Throwable =>
                ()
            c.biggest
      val mounted = r.mount(s, element(state, surface))
      val tree = captured(r, mounted)
      r.withCommitted { reader =>
        saved = Some(reader)
        assertEquals(intercept[KernelError](layout(tree)), KernelError.ReadOnlyPhase)
      }
      assertEquals(ok(r.transact(state.get)), 1)
    finally
      r.close()
  }

  test("measurement cache responds only to declared global epochs and stays bounded") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("epochs")
      val state = owner.cell(1)
      val surface = new Surface
      val mounted = runtime.mount(owner, element(state, surface))
      val frame = captured(runtime, mounted).tree.content.asInstanceOf[Content.Custom].frame
      val cache = new ElementMeasureCache(2)
      val constraints = Constraints(0.lp, 100.lp, 0.lp, 80.lp)
      def measure(epochs: PhaseEpochs): Size = cache.measure(frame, constraints, epochs)(frame.measure(constraints))
      measure(PhaseEpochs())
      measure(PhaseEpochs(themeColors = 1))
      assertEquals(surface.measures, 1)
      measure(PhaseEpochs(fontMetrics = 1))
      assertEquals(surface.measures, 2)
      measure(PhaseEpochs(fontMetrics = 1, displayScale = 1))
      assertEquals(surface.measures, 3)
      assertEquals(cache.size, 2)
    finally
      runtime.close()
  }

  test("public activation honors custom hit geometry and focus policy; paint does not cancel a press") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("activation")
      val paint = owner.cell(0)
      val fixed = Signal.const(0)
      val surface =
        new Surface:
          override def activation(
              @unused
              state: Int
          )(using
              @unused
              c: Committed
          ): Option[ElementActivation] = Some(ElementActivation(PartId(1), enabled = true))
          override def hitTest(
              @unused
              state: Int,
              size: Size,
              at: Point
          )(using
              @unused
              c: Committed
          ): Hit =
            if Rect(Point.origin, Size(size.width / 2, size.height)).contains(at) then
              Hit.Part(PartId(1))
            else
              Hit.Miss
      val mounted = runtime.mount(
        owner,
        Element("activation", ElementInputs(fixed, paint, fixed, fixed, Presentation.const(())), surface)
      )
      val interaction = new Interaction
      def present(epoch: Long): Unit = interaction.install(
        InteractionSnapshot(epoch, layout(captured(runtime, mounted)))
      )
      present(1)
      val target = interaction.presented.targets.head
      assert(!target.focusable)
      assert(!target.hit(80, 10))
      interaction.handle(HostInput.Down(10, 10))
      assertEquals(interaction.state.focused, None)
      ok(
        runtime.transact {
          paint.set(1)
        }
      )
      present(2)
      assertEquals(interaction.presented.epoch, 1L)
      assert(interaction.handle(HostInput.Up(10, 10)).activate.nonEmpty)
    finally
      runtime.close()
  }

  test("releasing another pointer button preserves capture until its owning button releases") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("buttons")
      val state = owner.cell(0)
      val surface = new Surface:
        override def pointer(event: PointerEvent)(using @unused txn: UiTxn): PointerResponse =
          if event.phase == PointerPhase.Down then PointerResponse.Capture else PointerResponse.Handled
      val mounted = runtime.mount(owner, element(state, surface))
      val tree = captured(runtime, mounted)
      val result = layout(tree)
      val interaction = new Interaction
      interaction.install(InteractionSnapshot(1, result))
      val input = new ElementInput(runtime, interaction)
      input.update(tree.elements)
      input.install(result)
      input.pointer(HostInput.Down(10, 10, primary = false))
      val capture = input.captured
      assert(capture.nonEmpty)
      input.pointer(HostInput.Up(10, 10, primary = true))
      assertEquals(input.captured, capture)
      input.pointer(HostInput.Up(10, 10, primary = false))
      assertEquals(input.captured, None)
    finally runtime.close()
  }
