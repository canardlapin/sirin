package sirin.desktop

import java.nio.file.Path
import demo.text.NoteField
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

class TextInputSeamSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")

  private class Fixture(build: Scope => View, focusAt: Option[(Double, Double)] = Some((20, 14))) extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val owner = runtime.scope("text-seam")
    val unicode = NativeUnicode.open(65536)
    var clipboard = ""
    var clears = 0
    val mounted = runtime.mount(owner, build(owner))
    runtime.observe(mounted.snapshot)(_ => ())
    val controller = new EditorController(runtime, unicode.analyze(_), () => clipboard, clipboard = _, () => clears += 1)
    val root = Path.of("..").toAbsolutePath.normalize()
    val renderer = new RenderSession(
      root.resolve("graphics/src/test/resources/fonts"),
      root.resolve("desktop/target/text-seam"),
      64,
      editorSystemFonts = true
    )
    var generation = 0L
    var tree = CapturedTree(Node(NodeId(Vector("empty")), Content.Empty), Map.empty)
    def update(): Unit =
      runtime.drive(32)
      tree = runtime.withCommitted(reader =>
        reader(mounted.snapshot) match
          case Value.Ok(snapshot) =>
            CommittedTree.captureWithBindings(snapshot, reader)
          case Value.Failed(error) =>
            throw error
      )
      controller.update(tree)
    def settle(): LayoutResult =
      update()
      generation += 1
      val state = controller.interaction.state
      val request = FrameRequest(
        generation,
        FramePayload(controller.tree, 240, 40, 240, 40, state.focused, state.keyboard)
      )
      val frame = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
      try
        controller.install(generation, frame.layout)
        frame.layout
      finally
        frame.close()
    def input(event: HostInput, mods: Mods = Mods.none, clicks: Int = 1): LayoutResult =
      controller.handle(event, mods, clicks)
      settle()
    def key(key: Key, mods: Mods = Mods.none): LayoutResult = input(HostInput.KeyDown(key, mods))
    settle()
    focusAt.foreach { (x, y) =>
      input(HostInput.Down(x, y))
      input(HostInput.Up(x, y))
    }
    def close(): Unit =
      renderer.close()
      unicode.close()
      runtime.close()

  private def note(initial: String): (Cell[NoteField.State], Fixture) =
    var cell = Option.empty[Cell[NoteField.State]]
    val f = new Fixture(scope =>
      val value = scope.cell(NoteField.State(initial))
      cell = Some(value)
      NoteField(value)
    )
    (cell.get, f)

  private def state(f: Fixture, cell: Cell[NoteField.State]): NoteField.State = ok(
    f.runtime.transact {
      cell.get
    }
  )

  test("committed text and codepoint-indexed composition reach a public element as UTF-16 text events") {
    val (cell, f) = note("draft")
    try
      assert(f.controller.textElement.nonEmpty)
      f.key(Key.Character('a'), Mods.Primary)
      f.input(HostInput.TextInput("á😀"))
      assertEquals(state(f, cell).text, "á😀")
      f.input(HostInput.Composition("😀にほ", 2, 1))
      val composing = state(f, cell)
      assertEquals(composing.text, "á😀")
      assertEquals(composing.composition, Some(("😀にほ", TextRange(3, 4))))
      assertEquals(composing.display, "á😀😀にほ")
      f.key(Key.Character('x'), Mods.Primary)
      assertEquals(f.clipboard, "")
      assertEquals(state(f, cell).composition.map(_._1), Some("😀にほ"))
      assert(f.controller.consumesKey(HostInput.KeyDown(Key.Tab, Mods.none)))
      f.input(HostInput.TextInput("日本語"))
      assertEquals(state(f, cell), NoteField.State("á😀日本語"))
      f.input(HostInput.Composition("未確定", 3, 0))
      val before = f.clears
      f.key(Key.Escape)
      assertEquals(state(f, cell), NoteField.State("á😀日本語"))
      assert(f.clears > before)
      f.key(Key.Backspace)
      assertEquals(state(f, cell).text, "á😀日本")
    finally
      f.close()
  }

  test("printable keys stay on the text channel while command chords reach application routing") {
    val (_, f) = note("draft")
    try
      assert(f.controller.consumesKey(HostInput.KeyDown(Key.Character('q'), Mods.none)))
      assert(f.controller.consumesKey(HostInput.KeyDown(Key.Space, Mods.Shift)))
      assert(!f.controller.consumesKey(HostInput.KeyDown(Key.Character('q'), Mods.Primary)))
      assert(f.controller.consumesKey(HostInput.KeyDown(Key.Character('c'), Mods.Primary)))
      assert(!f.controller.consumesKey(HostInput.KeyDown(Key.Character('c'), Mods.Primary | Mods.Shift)))
      assert(f.controller.consumesKey(HostInput.KeyDown(Key.ArrowLeft, Mods.Shift | Mods.Alt)))
      assert(!f.controller.consumesKey(HostInput.KeyDown(Key.Enter, Mods.none)))
      assert(!f.controller.handleElementKey(HostInput.KeyDown(Key.Character('q'), Mods.Primary)))
    finally
      f.close()
  }

  test("clipboard effects are staged and the IME caret follows presented geometry") {
    val (cell, f) = note("office café")
    try
      val start = f.controller.textElement.get._3.caret
      f.key(Key.Character('a'), Mods.Primary)
      f.key(Key.Character('c'), Mods.Primary)
      assertEquals(f.clipboard, "office café")
      f.key(Key.End)
      val end = f.controller.textElement.get._3.caret
      assert(end.origin.x.toDouble > start.origin.x.toDouble)
      f.clipboard = " pasted"
      f.key(Key.Character('v'), Mods.Primary)
      assertEquals(state(f, cell).text, "office café pasted")
    finally
      f.close()
  }

  test("a refused handler transaction discards its staged clipboard write and composition cancellation") {
    val copier =
      new ElementHandlers:
        override def key(event: KeyEvent, services: TextEditingServices)(using txn: UiTxn): Boolean =
          services.copy("leaked")
          services.cancelComposition()
          UiTxn.refuse("no edits here")
    val element =
      new Element[Unit, Unit, Unit, Unit, Unit]:
        def paint(state: Unit, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit = ()
        override def textInput(state: Unit, size: Size, text: TextServices)(using
            Committed
        ): Option[TextInputRequest] = Some(TextInputRequest(Rect(Point.origin, Size(1.lp, 10.lp))))
    val unit = Signal.const(())
    val f = new Fixture(_ =>
      Element(
        "refusing",
        ElementInputs(unit, unit, unit, unit, Presentation.const(())),
        element,
        focusable = true,
        handlers = Some(copier)
      )
    )
    try
      val clears = f.clears
      f.key(Key.Character('c'), Mods.Primary)
      assertEquals(f.clipboard, "")
      assertEquals(f.clears, clears)
      assertEquals(f.controller.rejected, 1)
    finally
      f.close()
  }

  test("text semantics publish presented ranges and assistive selection and replacement are transactional") {
    val (cell, f) = note("alpha beta")
    try
      val layout = f.settle()
      val (instance, node) = layout.semanticParts.head
      val text = node.text.get
      assertEquals(node.semantics.role, Role.TextInput)
      assertEquals(text.request.content, "alpha beta")
      assert(layout.text.get(text.request).nonEmpty)
      def action(kind: Int, start: Int, length: Int, value: String = "") =
        new NativeWindow.AccessibilityAction(f.generation, instance, kind, start, length, value)
      assert(f.controller.accessibility(action(2, 0, 5)))
      assertEquals(state(f, cell).selection, TextRange(0, 5))
      assert(!f.controller.accessibility(action(2, 2, 999)))
      assertEquals(state(f, cell).selection, TextRange(0, 5))
      val stale = new NativeWindow.AccessibilityAction(f.generation - 1, instance, 3, 0, 0, "stale")
      assert(!f.controller.accessibility(stale))
      assert(f.controller.accessibility(action(3, 0, 0, "gamma")))
      assertEquals(state(f, cell), NoteField.State("gamma"))
      f.settle()
      assertEquals(f.settle().semanticParts.head._2.text.get.selection, TextRange.at(5))
    finally
      f.close()
  }

  test("pointer hits use presented geometry and double-click selects a word") {
    val (cell, f) = note("alpha beta")
    try
      val layout = f.settle()
      val placed = layout.elements.head
      val shaped = layout.text.get(TextRequest("alpha beta", TextStyle.Body)).get
      val target = shaped.caretsAt(6).head.bounds
      val x = placed.bounds.x + 6 + target.origin.x.toDouble + 1
      val y = placed.bounds.y + 6 + target.origin.y.toDouble + 2
      f.input(HostInput.Down(x, y))
      f.input(HostInput.Up(x, y))
      assertEquals(state(f, cell).selection, TextRange.at(6))
      f.input(HostInput.Down(x + 4, y), clicks = 2)
      f.input(HostInput.Up(x + 4, y), clicks = 2)
      assertEquals(state(f, cell).selection, TextRange(6, 10))
    finally
      f.close()
  }

  test("removing or blurring a composing element retires the platform composition") {
    val (cell, f) = note("draft")
    try
      f.input(HostInput.Composition("にほ", 2, 0))
      assert(state(f, cell).composition.nonEmpty)
      val before = f.clears
      f.input(HostInput.Deactivate)
      assert(f.clears > before)
      assertEquals(state(f, cell).composition, None)
      f.input(HostInput.Down(20, 14))
      f.input(HostInput.Up(20, 14))
      f.input(HostInput.Composition("にほ", 2, 0))
      val mid = f.clears
      ok(
        f.runtime.transact {
          f.mounted.update(View.empty)
        }
      )
      f.update()
      assert(f.clears > mid)
      assert(f.controller.textElement.isEmpty)
    finally
      f.close()
  }

  test("an element without text semantics still receives cancellation when its composition is retired") {
    // It declares a composition throughout, so only retirement can end the platform's.
    var events = Vector.empty[TextInputEvent]
    val recorder =
      new ElementHandlers:
        override def text(event: TextInputEvent, services: TextEditingServices)(using UiTxn): Boolean =
          events :+= event
          true
    val element =
      new Element[Unit, Unit, Unit, Unit, Unit]:
        def paint(state: Unit, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit = ()
        override def textInput(state: Unit, size: Size, text: TextServices)(using
            Committed
        ): Option[TextInputRequest] = Some(TextInputRequest(Rect(Point.origin, Size(1.lp, 10.lp)), composing = true))
    val unit = Signal.const(())
    val f = new Fixture(_ =>
      Element(
        "composer",
        ElementInputs(unit, unit, unit, unit, Presentation.const(())),
        element,
        focusable = true,
        handlers = Some(recorder)
      )
    )
    try
      f.input(HostInput.Composition("にほ", 2, 0))
      f.input(HostInput.Deactivate)
      assertEquals(events, Vector(TextInputEvent.Compose("にほ", TextRange(2, 2)), TextInputEvent.CancelComposition))
    finally
      f.close()
  }

  private final class FocusRecorder(name: String, var refuseLoss: Boolean = false) extends ElementHandlers:
    var events = Vector.empty[FocusEvent]
    override def focus(event: FocusEvent)(using UiTxn): Unit =
      events :+= event
      if event == FocusEvent.Lost && refuseLoss then
        UiTxn.refuse(s"$name keeps focus")
  private val box =
    new Element[Unit, Unit, Unit, Unit, Unit]:
      override def measure(state: Unit, c: Constraints, text: TextServices)(using Committed): Size =
        Size(c.maxWidth.min(240.lp).max(c.minWidth), 20.lp.max(c.minHeight).min(c.maxHeight))
      def paint(state: Unit, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit = ()
  private def focusable(name: String, handlers: ElementHandlers): View =
    val unit = Signal.const(())
    Element(name, ElementInputs(unit, unit, unit, unit, Presentation.const(())), box, true, handlers = Some(handlers))

  test("focus moves deliver Lost then Gained, a refused Lost vetoes the move and deactivation reports Lost") {
    val first = FocusRecorder("first")
    val second = FocusRecorder("second")
    val f = new Fixture(_ => Column(focusable("first", first), focusable("second", second)), focusAt = Some((20, 10)))
    try
      assertEquals(first.events, Vector(FocusEvent.Gained))
      f.key(Key.Tab)
      assertEquals(first.events, Vector(FocusEvent.Gained, FocusEvent.Lost))
      assertEquals(second.events, Vector(FocusEvent.Gained))
      second.refuseLoss = true
      val focused = f.controller.interaction.state.focused
      f.input(HostInput.Down(20, 10))
      f.input(HostInput.Up(20, 10))
      assertEquals(f.controller.interaction.state.focused, focused)
      assertEquals(first.events.count(_ == FocusEvent.Gained), 1)
      assert(f.controller.rejected > 0)
      second.refuseLoss = false
      f.input(HostInput.Deactivate)
      assertEquals(second.events.last, FocusEvent.Lost)
      assertEquals(f.controller.interaction.state.focused, focused)
    finally
      f.close()
  }

  test("hard-line element text resolves to one document in measure, paint and semantics") {
    val content = "a\u0301\r\nשלום\n\nx"
    val request = TextRequest(content, TextStyle.Body)
    var measured = Option.empty[TextLayout]
    val element =
      new Element[Unit, Unit, Unit, Unit, Unit]:
        override def measureTextRequests(state: Unit, c: Constraints)(using Committed): Seq[TextRequest] =
          Vector(request)
        override def measure(state: Unit, c: Constraints, text: TextServices)(using Committed): Size =
          val layout = text.layout(content, TextStyle.Body, Lp(Double.PositiveInfinity))
          measured = Some(layout)
          Size(
            layout.size.width.max(c.minWidth).min(c.maxWidth),
            layout.size.height.max(c.minHeight).min(c.maxHeight)
          )
        override def textRequests(state: Unit, presentation: Unit, size: Size)(using Committed): Seq[TextRequest] =
          Vector(request)
        def paint(state: Unit, presentation: Unit, size: Size, canvas: Canvas)(using Committed): Unit =
          canvas.text(content, Point.origin, TextStyle.Body, Color.rgba(255, 255, 255))
        override def semantics(state: Unit, size: Size)(using Committed): Seq[SemanticNode] = Vector(
          SemanticNode(
            PartId(0),
            Semantics(Role.TextInput, value = Some(content)),
            Rect(Point.origin, size),
            Set.empty,
            Some(TextSemantics(request, Point.origin, TextRange.at(0), multiline = true))
          )
        )
    val unit = Signal.const(())
    val f = new Fixture(
      _ => Element("document", ElementInputs(unit, unit, unit, unit, Presentation.const(())), element),
      focusAt = None
    )
    try
      val layout = f.settle()
      val text = layout.text.get(request).get
      assertEquals(text.text, content)
      assert(measured.exists(m => m.text == content && m.size == text.size))
      assertEquals(text.caretsAt(3), Vector.empty)
      assert(text.caretsAt(4).head.lineIndex > text.caretsAt(2).head.lineIndex)
      assert(text.caretsAt(10).head.lineIndex > text.caretsAt(9).head.lineIndex)
      assertEquals(layout.semanticParts.head._2.text.map(_.request), Some(request))
    finally
      f.close()
  }

  test("handlers learn their presented size and report refusals to host feedback only when they commit") {
    var sizes = Vector.empty[Option[Size]]
    val reporter =
      new ElementHandlers:
        override def key(event: KeyEvent, services: TextEditingServices)(using UiTxn): Boolean =
          sizes :+= services.presentedSize
          services.refused(s"refused ${event.key}")
          if event.key == Key.Escape then
            UiTxn.refuse("rolled back")
          true
    val unit = Signal.const(())
    val f = new Fixture(_ =>
      Element(
        "reporter",
        ElementInputs(unit, unit, unit, unit, Presentation.const(())),
        box,
        focusable = true,
        handlers = Some(reporter)
      )
    )
    try
      f.key(Key.Enter)
      assertEquals(sizes, Vector(Some(Size(240.lp, 40.lp))))
      assertEquals(f.controller.rejected, 1)
      assertEquals(f.controller.notice, Some("refused Enter"))
      f.key(Key.Escape)
      assertEquals(f.controller.rejected, 2)
      assertEquals(f.controller.notice, Some("This element action could not be committed."))
    finally
      f.close()
  }
