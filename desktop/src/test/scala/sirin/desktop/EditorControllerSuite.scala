package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

/** TextField's acceptance bar. It observes only what the public seam presents: the field's text semantics and
  * prepared layout, the bound cell, platform effects and host refusal feedback.
  */
class EditorControllerSuite extends munit.FunSuite:
  /** One presented field: its semantic node, text semantics, layout, absolute text origin and text area. */
  private final case class Field(
      instance: Long,
      node: SemanticNode,
      text: TextSemantics,
      layout: TextLayout,
      origin: Point,
      area: Box,
      placed: Placed
  ):
    def display: String = text.request.content
    def selection: TextRange = text.selection
    def selected: String = display.substring(selection.start, selection.end)
    def error: Option[String] = Option.when(node.semantics.invalid)(node.semantics.description.get)
    def caret: TextCaret = layout
      .caret(TextPosition(selection.focus, TextAffinity.Downstream))
      .orElse(layout.caretsAt(selection.focus).headOption)
      .get
    def at(offset: Int): (Double, Double) =
      val c = layout.caretsAt(offset).head
      (origin.x.toDouble + c.bounds.origin.x.toDouble, origin.y.toDouble + c.bounds.origin.y.toDouble + 2)

  private def fields(layout: LayoutResult): Vector[Field] = layout
    .semanticParts
    .filter(_._2.semantics.role == Role.TextInput)
    .map { (instance, node) =>
      val text = node.text.get
      val placed = layout.elements.find(_.node.id == NodeId(Vector("element", instance))).get
      val b = node.bounds
      Field(
        instance,
        node,
        text,
        layout.text.get(text.request).get,
        Point((placed.bounds.x + text.origin.x.toDouble).lp, (placed.bounds.y + text.origin.y.toDouble).lp),
        Box(b.origin.x.toDouble, b.origin.y.toDouble, b.size.width.toDouble, b.size.height.toDouble),
        placed
      )
    }

  private class Fixture(
      initial: String = "alpha café שלום",
      commit: UiTxn => Unit = _ => (),
      multiple: Boolean = true,
      extraField: Boolean = false,
      limit: Int = 16384,
      width: Int = 220,
      height: Int = 120
  ) extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val scope = runtime.scope("editor-test")
    val value = scope.cell(initial)
    var clipboard = ""
    var clears = 0
    val unicode = NativeUnicode.open(65536)
    val base = TextField.bind(value).maxLength(limit).label("Document")
    val field = (
      if multiple then
        base.multiline
      else
        base
    ).onCommit(commit(summon[UiTxn]))
    val mounted = runtime.mount(
      scope,
      if extraField then
        Column(field, TextField.bind(value).label("Second"))
      else
        field
    )
    runtime.observe(mounted.snapshot)(_ => ())
    val controller =
      new EditorController(runtime, unicode.analyze(_), () => clipboard, s => clipboard = s, () => clears += 1)
    val root = Path.of("..").toAbsolutePath.normalize()
    val renderer = new RenderSession(
      root.resolve("graphics/src/test/resources/fonts"),
      root.resolve("desktop/target/unused"),
      64,
      editorSystemFonts = true
    )
    var generation = 0L
    var tree = CapturedTree(Node(NodeId(Vector("empty")), Content.Empty), Map.empty)
    def update(): Unit =
      runtime.drive(32)
      tree = runtime.withCommitted { r =>
        r(mounted.snapshot) match
          case Value.Ok(snapshot) =>
            CommittedTree.captureWithBindings(snapshot, r)
          case Value.Failed(error) =>
            throw error
      }
      controller.update(tree)
    def settle(snapshot: Option[Path] = None): LayoutResult =
      update()
      generation += 1
      val state = controller.interaction.state
      val request = FrameRequest(
        generation,
        FramePayload(
          controller.tree,
          width,
          height,
          width,
          height,
          Map.empty,
          state.focused,
          state.keyboard,
          textFocus = controller.textFocus
        )
      )
      val frame = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
      try
        snapshot.foreach(path => FrameWorker.save(frame.pixels, path))
        controller.install(generation, frame.layout)
        frame.layout
      finally
        frame.close()
    val id = fields(settle()).head.instance
    controller.focusElement(id)
    settle()
    def shown: Field = fields(settle()).find(_.instance == id).get
    /** Like the desktop host, input that needs geometry waits for a frame of the current tree. */
    def deliver(input: HostInput): Unit =
      if !controller.ready then
        settle()
      controller.handle(input)
      update()
    def key(key: Key, mods: Mods = Mods.none): Unit = deliver(HostInput.KeyDown(key, mods))
    def text(value: String): Unit = deliver(HostInput.TextInput(value))
    def compose(value: String, start: Int, length: Int): Unit = deliver(HostInput.Composition(value, start, length))
    def current: String =
      runtime.transact {
        value.get
      } match
        case CommitResult.Committed(v) =>
          v
        case other =>
          throw new IllegalStateException(s"$other")
    def close(): Unit =
      renderer.close()
      unicode.close()
      runtime.close()

  /** Presented editing facts that a refused edit must leave unchanged. */
  private def facts(f: Field): (String, TextRange) = (f.display, f.selection)

  test("Unicode typing, select/copy/cut/paste and undo/redo preserve exact Cell values") {
    val f = new Fixture
    try
      f.key(Key.Character('a'), Mods.Primary)
      f.text("á😀👩‍💻")
      assertEquals(f.current, "á😀👩‍💻")
      f.key(Key.Backspace)
      assertEquals(f.current, "á😀")
      f.key(Key.Backspace)
      assertEquals(f.current, "á")
      f.key(Key.Backspace)
      assertEquals(f.current, "")
      f.text("office café")
      f.key(Key.Character('a'), Mods.Primary)
      f.key(Key.Character('c'), Mods.Primary)
      assertEquals(f.clipboard, "office café")
      f.key(Key.Character('x'), Mods.Primary)
      assertEquals(f.current, "")
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "office café")
      f.key(Key.Character('z'), Mods.Primary | Mods.Shift)
      assertEquals(f.current, "")
      f.key(Key.Character('v'), Mods.Primary)
      assertEquals(f.current, "office café")
    finally
      f.close()
  }

  test("composition uses codepoint indices, stays provisional, consumes shortcuts and commits atomically") {
    val f = new Fixture("draft")
    try
      f.key(Key.Character('a'), Mods.Primary)
      f.compose("😀にほ", 2, 1)
      val shown = f.shown
      assertEquals(f.current, "draft")
      assertEquals(shown.display, "😀にほ")
      assertEquals(shown.selection.start, 3)
      assertEquals(shown.selection.end, 4)
      f.key(Key.Character('x'), Mods.Primary)
      assertEquals(f.current, "draft")
      assertEquals(f.clipboard, "")
      f.text("日本語")
      assertEquals(f.current, "日本語")
      f.settle()
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "draft")
      f.compose("未確定", 3, 0)
      f.key(Key.Escape)
      assertEquals(f.current, "draft")
      assert(!f.controller.composingText)
      assertEquals(f.shown.display, "draft")
      assert(f.clears > 0)
    finally
      f.close()
  }

  test("transaction refusal on commit preserves Cell and edit history") {
    val f = new Fixture("draft", txn => UiTxn.refuse("reject save")(using txn))
    try
      f.key(Key.Character('a'), Mods.Primary)
      f.text("edited")
      val before = facts(f.shown)
      f.key(Key.Enter, Mods.Primary)
      assertEquals(f.current, "edited")
      val after = f.shown
      assertEquals(facts(after), before)
      assertEquals(after.error, Some("reject save"))
      assertEquals(f.controller.rejected, 1)
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "draft")
    finally
      f.close()
  }

  test("external replacement/removal cancels preedit and retires history and stale AX actions") {
    val f = new Fixture
    try
      f.text("changed")
      f.compose("pending", 7, 0)
      f.settle()
      assert(f.controller.composingText)
      val clears = f.clears
      f.runtime.transact {
        f.value.set("external")
      }
      f.update()
      val stale = new NativeWindow.AccessibilityAction(f.generation, f.id, 3, 0, 0, "stale")
      assert(!f.controller.accessibility(stale))
      assertEquals(f.current, "external")
      assertEquals(f.shown.display, "external")
      assert(!f.controller.composingText, "external replacement must end the platform composition")
      assert(f.clears > clears)
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "external", "external replacement must retire history")
      f.settle()
      val invalid = new NativeWindow.AccessibilityAction(f.generation, f.id, 2, 2, 999, "")
      assert(!f.controller.accessibility(invalid))
      f.runtime.transact {
        f.mounted.update(View.empty)
      }
      f.update()
      assert(fields(f.settle()).isEmpty)
      assert(f.controller.textElement.isEmpty)
      assert(!f.controller.accessibility(stale))
    finally
      f.close()
  }

  test("visual arrows and hit testing use presented mixed-direction geometry at valid stops") {
    val f = new Fixture("abc שלום xyz")
    try
      val shown = f.shown
      val target = shown.layout.caretsAt(5).head
      f.controller.handle(
        HostInput.Down(
          shown.origin.x.toDouble + target.bounds.origin.x.toDouble,
          shown.origin.y.toDouble + target.bounds.origin.y.toDouble + 2
        )
      )
      f.update()
      val hit = f.shown.selection.focus
      assert(shown.layout.caretsAt(hit).nonEmpty)
      f.key(Key.ArrowRight, Mods.Shift)
      val after = f.shown
      assert(shown.layout.caretsAt(after.selection.focus).nonEmpty)
      assertEquals(after.selection.anchor, hit)
      assertNotEquals(after.selection.focus, hit)
    finally
      f.close()
  }

  test("wrapped selection, viewport caret and native publication geometry are clipped coherently") {
    val f = new Fixture((1 to 18).map(i => s"Line $i café").mkString("\n"))
    try
      f.key(Key.Character('a'), Mods.Primary)
      val shown = f.shown
      val caret = shown.caret.bounds
      val area = shown.area
      assert(shown.origin.y.toDouble + caret.origin.y.toDouble >= area.y)
      assert(
        shown.origin.y.toDouble + caret.origin.y.toDouble + caret.size.height.toDouble <= area.y + area.height + 0.001
      )
      assert(shown.layout.selection(0, f.current.length).size >= 18)
      val request = f.controller.textElement.get._3
      val placed = f.controller.textElement.get._2
      val ime = request.caret.translate(Point(placed.bounds.x.lp, placed.bounds.y.lp))
      assert(ime.origin.y.toDouble >= area.y - 0.001)
      assert(ime.origin.y.toDouble + ime.size.height.toDouble <= area.y + area.height + 0.001)
    finally
      f.close()
  }

  test("hard-line documents preserve CRLF and blank-line offsets after native owners close") {
    val f = new Fixture("á\r\nשלום\n\nx")
    val text =
      try f.shown.layout
      finally f.close()
    assertEquals(text.text, "á\r\nשלום\n\nx")
    assertEquals(text.caretsAt(3), Vector.empty)
    assert(text.caretsAt(4).head.bounds.origin.y.toDouble > text.caretsAt(2).head.bounds.origin.y.toDouble)
    assert(text.caretsAt(10).head.lineIndex > text.caretsAt(9).head.lineIndex)
    assertEquals(text.selection(2, 4).size, 1)
    intercept[IllegalArgumentException](text.selection(3, 4))
    val caret = text.caretsAt(4).head
    assert(
      text.hit(Point(caret.bounds.origin.x, (caret.bounds.origin.y.toDouble + 2).lp)).primary.position.utf16Offset >= 4
    )
  }

  test("single-line fields retain unwrapped geometry and keep the insertion caret visible") {
    val f = new Fixture("long text " * 50, multiple = false)
    try
      f.key(Key.Character('a'), Mods.Primary)
      val shown = f.shown
      val area = shown.area
      val caret = shown.caret.bounds
      assert(shown.layout.size.width.toDouble > area.width)
      assert(shown.origin.x.toDouble < area.x)
      assert(shown.origin.x.toDouble + caret.origin.x.toDouble >= area.x)
      assert(shown.origin.x.toDouble + caret.origin.x.toDouble < area.x + area.width)
      f.text("line1\r\nline2\tend")
      assertEquals(f.current, "line1 line2 end")
    finally
      f.close()
  }

  test("double-click word and shift-click selection follow shaped hit positions") {
    val f = new Fixture("one café two")
    try
      val shown = f.shown
      val (x, y) = shown.at(5)
      f.controller.handle(HostInput.Down(x, y), clicks = 2)
      f.update()
      assertEquals(f.shown.selected, "café")
      val (endX, endY) = shown.at(11)
      f.controller.handle(HostInput.Up(x, y))
      f.controller.handle(HostInput.Down(endX, endY), Mods.Shift)
      f.update()
      assertEquals(f.shown.selection.start, 4)
      assertEquals(f.shown.selection.end, 11)
    finally
      f.close()
  }

  test("oversized paste refuses atomically and reports an editor notice") {
    val f = new Fixture("retained")
    try
      f.clipboard = "x" * 16385
      f.key(Key.Character('v'), Mods.Primary)
      assertEquals(f.current, "retained")
      assert(f.controller.notice.nonEmpty)
      assertEquals(f.controller.rejected, 1)
      assert(f.shown.error.nonEmpty)
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "retained", "a refused paste must leave no undo step")
    finally
      f.close()
  }

  test("Japanese composition has actual font coverage and retained glyph geometry") {
    val f = new Fixture("日本語 にほんご")
    try
      val text = f.shown.layout
      assert(text.isComplete, "editor CJK fallback must cover the IME fixture")
      assert(text.caretsAt(3).nonEmpty)
    finally
      f.close()
  }

  test("copy ends typing coalescence and removing a composing field clears native marked text") {
    val f = new Fixture("")
    try
      f.text("a")
      f.key(Key.Character('c'), Mods.Primary)
      f.text("b")
      f.settle()
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "a")
      f.settle()
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "")
      f.settle()
      f.compose("未確定", 3, 0)
      val before = f.clears
      f.runtime.transact {
        f.mounted.update(View.empty)
      }
      f.update()
      assertEquals(f.clears, before + 1)
      assert(!f.controller.composingText)
      assert(fields(f.settle()).isEmpty)
    finally
      f.close()
  }

  test("composition offsets follow normalized text and valid native actions are transactional") {
    val f = new Fixture("draft")
    try
      f.key(Key.Character('a'), Mods.Primary)
      f.compose("a\r\nb", 4, 0)
      assertEquals(f.shown.selection.focus, 3)
      assertEquals(f.current, "draft")
      f.key(Key.Escape)
      f.settle()
      assert(f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation, f.id, 2, 1, 3, "")))
      assertEquals(f.shown.selected, "raf")
      assert(f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation, f.id, 2, 4, -3, "")))
      assertEquals(f.shown.selection.anchor, 4)
      assertEquals(f.shown.selection.focus, 1)
      assert(f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation, f.id, 3, 0, 0, "changed")))
      f.update()
      assertEquals(f.current, "changed")
      f.settle()
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "draft")
    finally
      f.close()
  }

  test("editor and IME reserve editing keys while unknown command chords reach application routing") {
    val f = new Fixture("draft", extraField = true)
    try
      def consumes(key: Key, mods: Mods = Mods.none) = f.controller.consumesKey(HostInput.KeyDown(key, mods))
      assert(consumes(Key.Character('a'), Mods.Primary))
      assert(consumes(Key.Character('z'), Mods.Primary | Mods.Shift))
      assert(consumes(Key.Character('界')))
      assert(consumes(Key.Backspace))
      assert(consumes(Key.ArrowLeft))
      assert(!consumes(Key.Character('s'), Mods.Primary))
      assert(!consumes(Key.Character('k'), Mods.Control))
      assert(consumes(Key.Tab))
      assert(consumes(Key.Tab, Mods.Shift))
      assert(!consumes(Key.Tab, Mods.Primary))
      f.compose("未確定", 3, 0)
      val focused = f.controller.interaction.state.focused
      assert(consumes(Key.Character('s'), Mods.Primary))
      assert(consumes(Key.Tab, Mods.Primary))
      f.key(Key.Tab)
      assertEquals(f.controller.interaction.state.focused, focused)
      assert(f.controller.composingText)
      assertEquals(f.shown.display, "未確定draft")
      f.key(Key.Escape)
      assert(!f.controller.composingText)
      assertEquals(f.shown.display, "draft")
      assert(!consumes(Key.Character('s'), Mods.Primary))
      assertEquals(f.current, "draft")
    finally
      f.close()
  }

  test("button activation, focus traversal and scroll keys take precedence over shortcuts") {
    val runtime = new HeadlessRuntime
    try
      val controller = new EditorController(runtime, _ => fail("no text analysis expected"), () => "", _ => (), () => ())
      val id = NodeId(Vector("button"))
      val bounds = Box(0, 0, 100, 100)
      def focus(kind: TargetKind): Unit =
        controller
          .interaction
          .install(
            InteractionSnapshot(1, Vector(Target(id, kind, bounds, bounds, true, Vector.empty, Vector.empty, Vector.empty))),
            Map.empty
          )
        controller.interaction.state = FocusState(Some(id))
      def key(k: Key, mods: Mods = Mods.none) = controller.consumesKey(HostInput.KeyDown(k, mods))
      focus(TargetKind.Button(1))
      assert(key(Key.Enter))
      assert(key(Key.Space))
      assert(key(Key.Tab))
      assert(!key(Key.Enter, Mods.Primary))
      assert(!key(Key.Character('s'), Mods.Primary))
      focus(TargetKind.Scroll(2, 500))
      assert(key(Key.PageDown))
      assert(key(Key.Home))
      assert(key(Key.ArrowUp))
      assert(!key(Key.PageDown, Mods.Primary))
      assert(!key(Key.Character('s'), Mods.Primary))
    finally
      runtime.close()
  }

  test("field limits count UTF-16, reject whole edits and render recoverable feedback") {
    val f = new Fixture("old", multiple = false, limit = 3, width = 140, height = 160)
    try
      f.key(Key.Character('a'), Mods.Primary)
      f.text("😀a")
      assertEquals(f.current, "😀a")
      f.clipboard = "😀😀"
      f.settle()
      f.key(Key.Character('a'), Mods.Primary)
      val selected = facts(f.shown)
      f.controller.handle(HostInput.KeyDown(Key.Character('v'), Mods.Primary))
      f.update()
      assertEquals(f.current, "😀a")
      assert(!f.controller.ready, "refusal feedback must change the presented field")
      val refused = f.settle(Some(Path.of("target/field-feedback/limit-refusal.png")))
      val shown = fields(refused).head
      assertEquals(facts(shown), selected)
      val problem = shown.error
      assert(problem.exists(_.contains("3 UTF-16")))
      val diagnostic = refused.text.get(TextRequest(problem.get, TextStyle.Caption, Lp(shown.placed.bounds.width))).get
      assert(diagnostic.size.height.toDouble > 20, "fixture must wrap its field diagnostic")
      val borderBottom = shown.area.y + shown.area.height + 8
      assert(borderBottom + 2 + diagnostic.size.height.toDouble <= shown.placed.bounds.y + shown.placed.bounds.height + 0.001)
      assert(f.controller.ready)
      f.text("ok")
      val recovered = f.shown
      assertEquals(f.current, "ok")
      assertEquals(recovered.error, None)
      assertEquals(f.controller.notice, None)
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "😀a", "the refused paste must not have added an undo step")
    finally
      f.close()
  }

  test("IME preedit can exceed the input limit but a refused commit preserves the draft and clears marked text") {
    val f = new Fixture("old", multiple = false, limit = 3)
    try
      f.key(Key.Character('a'), Mods.Primary)
      f.compose("かなかな", 4, 0)
      assertEquals(f.current, "old")
      assertEquals(f.shown.display, "かなかな")
      f.text("かなかな")
      assertEquals(f.current, "old")
      assert(!f.controller.composingText)
      assertEquals(f.shown.display, "old")
      assert(f.clears > 0)
      assert(f.shown.error.nonEmpty)
      f.text("語")
      assertEquals(f.current, "語")
      f.settle()
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "old")
    finally
      f.close()
  }

  test("current accessibility replacements share field limits and malformed text cannot escape the edit boundary") {
    val f = new Fixture("old", multiple = false, limit = 3)
    try
      val before = facts(f.shown)
      val oversized = new NativeWindow.AccessibilityAction(f.generation, f.id, 3, 0, 0, "oversized")
      assert(!f.controller.accessibility(oversized))
      assertEquals(f.current, "old")
      val refused = f.shown
      assertEquals(facts(refused), before)
      assert(refused.error.nonEmpty)
      val malformed = new NativeWindow.AccessibilityAction(f.generation, f.id, 3, 0, 0, "\ud800")
      assert(!f.controller.accessibility(malformed))
      assertEquals(f.current, "old")
      f.settle()
      val valid = new NativeWindow.AccessibilityAction(f.generation, f.id, 3, 0, 0, "new")
      assert(f.controller.accessibility(valid))
      f.update()
      assertEquals(f.current, "new")
      assertEquals(f.shown.error, None)
      assertEquals(NativeApplication.liveApplications(), 0)
    finally
      f.close()
  }

  test("tightening a limit rejects oversized undo while programmatic drafts remain recoverable") {
    val f = new Fixture("", multiple = false, limit = 5)
    try
      f.text("12345")
      f.settle()
      f.key(Key.Character('a'), Mods.Primary)
      f.text("x")
      f.runtime.transact {
        f.mounted.update(f.field.maxLength(3))
      }
      f.update()
      val before = facts(f.shown)
      f.key(Key.Character('z'), Mods.Primary)
      assertEquals(f.current, "x")
      assertEquals(facts(f.shown), before)
      f.runtime.transact {
        f.value.set("external draft")
      }
      f.update()
      assertEquals(f.current, "external draft")
      assert(f.shown.error.nonEmpty)
      f.key(Key.Character('a'), Mods.Primary)
      f.text("ok")
      assertEquals(f.current, "ok")
      assertEquals(f.shown.error, None)
    finally
      f.close()
  }

  test("bounded refusal feedback never introduces an unpaired surrogate into rendered text") {
    val reason = "a" * 511 + "😀"
    val f = new Fixture("retained", txn => UiTxn.refuse(reason)(using txn))
    try
      f.key(Key.Enter, Mods.Primary)
      val message = f.shown.error.get
      TextEditing.validate(message)
      assert(message.length <= 512)
      assertEquals(f.current, "retained")
      assertEquals(f.controller.notice, Some(message))
    finally
      f.close()
  }

  test("complex unsupported-glyph feedback retains the explicit paragraph resource refusal") {
    val reason = "a" + "😀" * 256
    val f = new Fixture("retained", txn => UiTxn.refuse(reason)(using txn))
    try
      f.key(Key.Enter, Mods.Primary)
      val message = f.controller.notice.get
      TextEditing.validate(message)
      assert(message.length <= 512)
      val refusal = intercept[IllegalArgumentException](f.settle())
      assert(refusal.getMessage.contains("paragraph exceeds shaping work budget"))
      assertEquals(f.current, "retained")
      // This is a bounded negative receipt, not usable complex-diagnostic qualification.
    finally
      f.close()
  }
