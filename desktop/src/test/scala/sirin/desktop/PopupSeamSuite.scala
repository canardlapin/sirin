package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

/** A-0 corpus program 4 (examples/authoring/sirin/examples/PopupFocus.scala) on the desktop host. The layer cell is
  * lifted out so the test can observe it, and the notes field has a ref so focus can start there.
  */
object PopupSeamSuite:
  final case class Layer(name: String, locked: Boolean)

  final class Program(
      scope: Scope, val layer: Cell[Layer], restore: NodeRef => FocusRestore = _ => FocusRestore.Previous):
    val open = scope.cell(false)
    val draft = scope.cell("")
    val error = scope.cell(Option.empty[String])
    val renameButton = scope.ref()
    val nameField = scope.ref()
    val notesField = scope.ref()
    val beginRename: Command[Unit, Unit] =
      scope.action("layer.rename", "Rename Layer…", enabled = layer.map(l => !l.locked)) { _ =>
        draft.set(layer.get.name)
        open.set(true)
      }
    val commit: Action =
      UiTxn.attempt {
        val name = draft.get.trim
        UiTxn.require(name.nonEmpty, "a layer needs a name")
        UiTxn.require(!layer.get.locked, "layer is locked")
        layer.update(_.copy(name = name))
        open.set(false)
      } match
        case Attempt.Accepted(_) =>
          error.set(None)
        case Attempt.Refused(reason) =>
          error.set(Some(reason))
    scope.effect(layer.map(_.locked)) { locked =>
      if locked then
        open.set(false)
    }
    val header: View =
      Row(
        Text(layer.map(_.name)).style(TextStyle.Heading).grow,
        Checkbox(layer.map(_.locked), Signal.const("Locked"))(v => layer.update(_.copy(locked = v))),
        Button(beginRename).ref(renameButton),
        Popup(
          open,
          anchor = renameButton,
          placement = Placement.Below,
          initialFocus = Some(nameField),
          restoreFocus = restore(notesField),
          onDismiss = reason =>
            if reason == DismissReason.OutsidePress && draft.get != layer.get.name then
              DismissDecision.Veto
            else
              DismissDecision.Allow
        )(
          Column(
            TextField.bind(draft).label("Layer name").error(error).onCommit(commit).ref(nameField),
            Row(Spacer().grow, Button("Cancel").onPress(open.set(false)), Button("Rename").primary.onPress(commit))
              .spacing(Spacing.small)
          ).spacing(Spacing.small)
            .padding(Spacing.medium)
            .semantics(Semantics(Role.Dialog, name = Some("Rename layer")))
        )
      ).spacing(Spacing.small)
        .align(Alignment.Center)
        .shortcut(Chord(Key.Character('r'), Mods.Primary | Mods.Shift), beginRename)
    val view: View =
      Column(header, TextField.bind(scope.cell("")).label("Notes").ref(notesField))
        .spacing(Spacing.medium)
        .padding(Spacing.large)


class PopupSeamSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")

  import PopupSeamSuite.*

  private final class Fixture(width: Int = 560, height: Int = 320,
      restore: NodeRef => FocusRestore = _ => FocusRestore.Previous) extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val owner = runtime.scope("popup")
    val layer = owner.cell(Layer("Background", false))
    private var built = Option.empty[Program]
    private val panel = Component[Unit]("LayerPanel") { (scope, _) =>
      val created = new Program(scope, layer, restore)
      built = Some(created)
      created.view
    }
    val unicode = NativeUnicode.open(65536)
    val mounted = runtime.mount(owner, panel.view)
    runtime.observe(mounted.snapshot)(_ => ())
    def program: Program = built.get
    val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
    val root = Path.of("..").toAbsolutePath.normalize()
    val renderer = new RenderSession(
      root.resolve("graphics/src/test/resources/fonts"),
      root.resolve("desktop/target/popup-seam"),
      64,
      editorSystemFonts = true
    )
    var generation = 0L
    var captured = CapturedTree(Node(NodeId(Vector("empty")), Content.Empty), Map.empty)
    def refresh(): Unit =
      runtime.drive(32)
      captured = runtime.withCommitted(reader =>
        reader(mounted.snapshot) match
          case Value.Ok(snapshot) =>
            CommittedTree.captureWithBindings(snapshot, reader)
          case Value.Failed(error) =>
            throw error
      )
      controller.update(captured)
    def frame(save: Option[String] = None): LayoutResult =
      refresh()
      generation += 1
      val state = controller.interaction.state
      val request = FrameRequest(
        generation,
        FramePayload(controller.tree, width, height, width, height, state.focused, state.keyboard)
      )
      val rendered = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
      try
        controller.install(generation, rendered.layout)
        save.foreach(name => FrameWorker.save(rendered.pixels, root.resolve("desktop/target/popup-seam").resolve(name)))
        rendered.layout
      finally rendered.close()
    def layout: LayoutResult = controller.layout.get
    def part(name: String): (Long, SemanticNode) =
      val found = layout.semanticParts.filter(_._2.semantics.name.contains(name))
      assertEquals(found.size, 1, s"one $name in ${layout.semanticParts.map(_._2.semantics.name)}")
      found.head
    def has(name: String): Boolean = layout.semanticParts.exists(_._2.semantics.name.contains(name))
    def click(name: String): Unit = clickAt(part(name)._2.bounds)
    def clickAt(at: Rect): Unit =
      val x = at.origin.x.toDouble + at.size.width.toDouble / 2
      val y = at.origin.y.toDouble + at.size.height.toDouble / 2
      controller.handle(HostInput.Down(x, y), Mods.none, 1)
      controller.handle(HostInput.Up(x, y), Mods.none, 1)
      frame()
    def key(key: Key, mods: Mods = Mods.none): Unit =
      controller.handle(HostInput.KeyDown(key, mods))
      controller.handle(HostInput.KeyUp(key, mods))
      frame()
    def focused(name: String): Boolean =
      controller.interaction.state.focused.contains(NodeId(Vector("element", part(name)._1)))
    def read[A](signal: Signal[A]): A = ok(runtime.transact(signal.get))
    def close(): Unit =
      renderer.close()
      unicode.close()
      runtime.close()

  test("Rename opens a dialog below its button with the name field focused, and Escape restores the button") {
    val f = new Fixture
    try
      f.frame()
      assert(!f.has("Rename layer"))
      val button = f.part("Rename Layer…")._2.bounds
      f.click("Rename Layer…")
      assert(f.read(f.program.open))
      assertEquals(f.layout.layers.size, 1)
      val dialog = f.part("Rename layer")._2
      assertEquals(dialog.semantics.role, Role.Dialog)
      assert(dialog.bounds.origin.y.toDouble >= button.origin.y.toDouble + button.size.height.toDouble + 4 - 1e-9)
      assert(f.focused("Layer name"), "initial focus is the name field")
      f.frame(Some("open.png"))
      f.key(Key.Escape)
      assert(!f.read(f.program.open), "Escape in the field dismisses the popup")
      assert(!f.has("Rename layer"))
      assert(f.focused("Rename Layer…"), "focus returns to the button that opened it")
    finally f.close()
  }

  test("text after popup dismissal waits for the restored field's accepted presentation") {
    val f = new Fixture
    try
      f.frame()
      f.click("Notes")
      ok(f.runtime.transact { f.program.beginRename.dispatch(()) })
      f.frame()
      assert(f.controller.focusElement(f.part("Cancel")._1))
      f.frame()
      assertEquals(f.controller.textElement, None)
      f.controller.handle(HostInput.KeyDown(Key.Escape, Mods.none))
      f.refresh()
      assert(!f.controller.ready)
      assertEquals(f.controller.textElement, None, "logical focus does not move before presentation")
      assertEquals(f.controller.textInputTransport.map(_._1), Some(f.part("Notes")._1))
      assert(!f.read(f.program.notesField.hasFocus), "transport preparation does not publish reference focus")
      assert(f.controller.needsGeometry(HostInput.KeyDown(Key.Character('a'), Mods.none)))
      val queue = new OwnerInputQueue[Int]
      queue.add(1, Some(HostInput.TextInput("prefix")))
      assert(f.controller.needsGeometry(HostInput.TextInput("prefix")))
      assert(f.controller.needsGeometry(HostInput.Composition("candidate", 0, 0)))
      def drain(): Unit =
        assert(queue.drain(() => f.controller.ready, f.controller.needsGeometry)(
          (_, event) => {
            f.controller.handle(event)
            true
          }, _ => fail("no close request")))
      drain()
      assert(queue.nonEmpty)
      f.frame()
      assert(f.focused("Notes"))
      drain()
      assert(!queue.nonEmpty)
      f.frame()
      assertEquals(f.part("Notes")._2.text.get.request.content, "prefix")
    finally f.close()
  }

  test("pending transport obeys explicit, non-text and Nowhere restoration policies") {
    val policies: Vector[(NodeRef => FocusRestore, Boolean)] = Vector(
      ((_ => FocusRestore.Previous), false),
      ((ref => FocusRestore.To(ref)), true),
      ((_ => FocusRestore.Nowhere), false)
    )
    for (restore, preparesText) <- policies do
      val f = new Fixture(restore = restore)
      try
        f.frame()
        f.click("Rename Layer…")
        assert(f.controller.focusElement(f.part("Cancel")._1))
        f.frame()
        f.controller.handle(HostInput.KeyDown(Key.Escape, Mods.none))
        f.refresh()
        assert(!f.controller.ready)
        assertEquals(f.controller.textInputTransport.nonEmpty, preparesText)
        f.frame()
        assertEquals(f.controller.textElement.nonEmpty, preparesText)
        if preparesText then assert(f.focused("Notes"))
      finally f.close()
  }

  test("vetoed dismissal and retired restore targets do not prepare unrelated text input") {
    val f = new Fixture
    try
      f.frame()
      f.click("Notes")
      ok(f.runtime.transact { f.program.beginRename.dispatch(()) })
      f.frame()
      ok(f.runtime.transact { f.program.draft.set("changed") })
      f.frame()
      assert(f.controller.focusElement(f.part("Cancel")._1))
      f.frame()
      val outside = f.part("Locked")._2.bounds.origin
      f.controller.handle(HostInput.Down(outside.x.toDouble, outside.y.toDouble))
      f.refresh()
      assert(f.read(f.program.open), "the edited draft vetoes the outside press")
      assertEquals(f.controller.textInputTransport, None)
      f.controller.handle(HostInput.KeyDown(Key.Escape, Mods.none))
      ok(f.runtime.transact { f.mounted.update(View.empty) })
      f.refresh()
      assertEquals(f.controller.textInputTransport, None)
      f.frame()
      assertEquals(f.controller.textElement, None)
    finally f.close()
  }

  test("an outside press is consumed, vetoed while the draft is edited, and Rename commits and restores focus") {
    val f = new Fixture
    try
      f.frame()
      f.click("Rename Layer…")
      f.controller.handle(HostInput.TextInput("X"))
      f.frame()
      assertEquals(f.read(f.program.draft), "XBackground", "the caret starts at the beginning")
      val locked = f.part("Locked")._2.bounds
      f.clickAt(locked)
      assert(f.read(f.program.open), "an edited draft vetoes outside dismissal")
      assert(!f.read(f.layer).locked, "the press did not reach the checkbox beneath")
      f.click("Rename")
      assertEquals(f.read(f.layer).name, "XBackground")
      assert(!f.read(f.program.open))
      assert(f.focused("Rename Layer…"))
      f.click("Rename Layer…")
      f.clickAt(f.part("Locked")._2.bounds)
      assert(!f.read(f.program.open), "an unedited draft allows outside dismissal")
      assert(!f.read(f.layer).locked, "the dismissing press is consumed")
    finally f.close()
  }

  test("Tab stays inside the popup, the header shortcut still applies, and locking the layer closes it") {
    val f = new Fixture
    try
      f.frame()
      f.click("Rename Layer…")
      val inside = Set("Layer name", "Cancel", "Rename")
      for _ <- 0 until 6 do
        f.key(Key.Tab)
        assert(inside.exists(f.focused), s"focus left the popup: ${f.controller.interaction.state.focused}")
      val chord = Chord(Key.Character('r'), Mods.Primary | Mods.Shift)
      assert(
        KeyboardRouting.local(f.captured, f.controller.interaction.state.focused, chord).nonEmpty,
        "the originator's shortcut is in scope inside its popup"
      )
      ok(f.runtime.transact(f.layer.update(_.copy(locked = true))))
      f.frame()
      f.frame()
      assert(!f.read(f.program.open), "the effect closed the popup")
      assert(f.focused("Rename Layer…"), "a disabled but focusable originator still takes focus back")
    finally f.close()
  }

  test("a popup opened from a focused field restores focus there, and its content dies with the originator") {
    val f = new Fixture
    try
      f.frame()
      f.click("Notes")
      assert(f.focused("Notes"))
      ok(f.runtime.transact(f.program.beginRename.dispatch(())))
      f.frame()
      assert(f.focused("Layer name"))
      f.click("Cancel")
      assert(!f.read(f.program.open))
      assert(f.focused("Notes"), "Previous restores the field that had focus")
      f.click("Rename Layer…")
      ok(f.runtime.transact(f.mounted.update(View.empty)))
      f.frame()
      assert(f.layout.layers.isEmpty)
      assertEquals(f.layout.semanticParts, Vector.empty)
    finally f.close()
  }

  test("a modal popup blocks presses beneath it and holds Tab, and placement flips above near the bottom") {
    val runtime = new HeadlessRuntime
    val unicode = NativeUnicode.open(65536)
    val renderer = new RenderSession(Path.of("../graphics/src/test/resources/fonts"), Path.of("target/popup-seam"), 64)
    try
      val owner = runtime.scope("modal")
      val open = owner.cell(true)
      val presses = owner.cell(0)
      val anchor = owner.ref()
      val view = Column(
        Button("Beneath").onPress(presses.update(_ + 1)),
        Spacer().grow,
        Button("Anchor").ref(anchor),
        Popup(open, anchor, modal = true, dismissal = Dismissal(onEscape = true, onOutsidePress = false))(
          Row(Button("One"), Button("Two")).padding(8.lp)
        )
      )
      val mounted = runtime.mount(owner, view)
      runtime.observe(mounted.snapshot)(_ => ())
      val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
      var generation = 0L
      def frame(): LayoutResult =
        val captured = runtime.withCommitted(reader =>
          reader(mounted.snapshot) match
            case Value.Ok(snapshot) =>
              CommittedTree.captureWithBindings(snapshot, reader)
            case Value.Failed(error) =>
              throw error
        )
        controller.update(captured)
        generation += 1
        val state = controller.interaction.state
        val request = FrameRequest(generation, FramePayload(controller.tree, 300, 200, 300, 200, state.focused, state.keyboard))
        val rendered = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
        try
          controller.install(generation, rendered.layout)
          rendered.layout
        finally rendered.close()
      def bounds(layout: LayoutResult, name: String): Rect =
        layout.semanticParts.find(_._2.semantics.name.contains(name)).get._2.bounds
      val shown = frame()
      val anchorBox = bounds(shown, "Anchor")
      val popup = shown.layers.head.placed.bounds
      assert(popup.y + popup.height <= anchorBox.origin.y.toDouble - 4 + 1e-9, "no room below, so it flips above")
      val beneath = bounds(shown, "Beneath")
      val x = beneath.origin.x.toDouble + 4
      val y = beneath.origin.y.toDouble + 4
      controller.handle(HostInput.Down(x, y))
      controller.handle(HostInput.Up(x, y))
      frame()
      assertEquals(ok(runtime.transact(presses.get)), 0, "a modal popup blocks the press")
      assert(ok(runtime.transact(open.get)), "and does not dismiss when outside presses are off")
      val inside = Set("One", "Two")
      for _ <- 0 until 4 do
        controller.handle(HostInput.KeyDown(Key.Tab, Mods.none))
        frame()
        val name = controller
          .layout
          .get
          .semanticParts
          .find(p => controller.interaction.state.focused.contains(NodeId(Vector("element", p._1))))
        assert(name.exists(p => inside.exists(p._2.semantics.name.contains)), s"Tab left the modal popup: $name")
      controller.handle(HostInput.KeyDown(Key.Escape, Mods.none))
      frame()
      assert(!ok(runtime.transact(open.get)))
      val anchorPart = frame().semanticParts.find(_._2.semantics.name.contains("Anchor")).get._1
      assertEquals(controller.interaction.state.focused, Some(NodeId(Vector("element", anchorPart))), "focus falls back to the anchor")
    finally
      renderer.close()
      unicode.close()
      runtime.close()
  }

  test("a modal popup with no focusable content blocks keyboard activation of the base") {
    val f = new Fixture
    try
      val open = f.owner.cell(false)
      val presses = f.owner.cell(0)
      val anchor = f.owner.ref()
      val view = Column(
        Button("Underlying action").ref(anchor).onPress(presses.update(_ + 1)),
        Popup(open, anchor, modal = true)(Text("Please wait"))
      )
      ok(f.runtime.transact(f.mounted.update(view)))
      f.frame()
      f.click("Underlying action")
      assertEquals(f.read(presses), 1)
      assert(f.focused("Underlying action"))
      ok(f.runtime.transact(open.set(true)))
      f.frame()
      assertEquals(f.layout.layers.size, 1)
      assert(f.layout.layers.head.frame.modal)
      f.key(Key.Space)
      assertEquals(f.read(presses), 1, "a presented modal popup must block the underlying action")
      val id = f.part("Underlying action")._1
      assert(!f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation, id, 4, 0, 0, "")))
      assert(!f.controller.focusReference(f.captured.popups.head.anchor))
      f.key(Key.Escape)
      assert(!f.read(open))
      f.key(Key.Space)
      assertEquals(f.read(presses), 2, "the stored focus becomes usable after closing")
    finally f.close()
  }

  test("an empty modal blocks text, composition and assistive edits while window shortcuts remain available") {
    val f = new Fixture
    try
      val open = f.owner.cell(false)
      val text = f.owner.cell("draft")
      val anchor = f.owner.ref()
      val local = f.owner.action("local", "Local")(_ => ())
      val view = Column(
        TextField.bind(text).label("Draft").ref(anchor),
        Popup(open, anchor, modal = true)(Text("Please wait"))
      ).shortcut(Chord.primary('k'), local)
      ok(f.runtime.transact(f.mounted.update(view)))
      f.frame()
      f.click("Draft")
      val id = f.part("Draft")._1
      f.controller.handle(HostInput.Composition("に", 1, 0))
      f.frame()
      assert(f.controller.composingText)
      ok(f.runtime.transact(open.set(true)))
      f.frame()
      assert(!f.controller.composingText)
      assertEquals(f.controller.textElement, None)
      f.controller.handle(HostInput.TextInput("blocked"))
      f.controller.handle(HostInput.Composition("blocked", 7, 0))
      f.key(Key.Backspace)
      assertEquals(f.read(text), "draft")
      assert(!f.controller.focusElement(id))
      assert(!f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation, id, 3, 0, 0, "blocked")))
      val focus = f.controller.interaction.inputFocus.map(_.id)
      assertEquals(KeyboardRouting.local(f.captured, focus, Chord.primary('k')), None)
      val menu = CapturedMenus(
        Vector(MenuNode.Item(1, "Window command", Some(Chord.primary('k')), true, false)),
        Map(1 -> MenuAction(MenuItem(local, Chord.primary('k')), Activation(true, _ => ()), None))
      )
      assert(KeyboardRouting.command(f.captured, focus, Chord.primary('k'), menu, CapturedMenus.empty).nonEmpty)
      f.key(Key.Escape)
      assert(!f.read(open))
      assert(f.controller.textElement.nonEmpty)
      f.controller.handle(HostInput.TextInput("ok"))
      f.frame()
      assert(f.read(text).contains("ok"))
    finally f.close()
  }
