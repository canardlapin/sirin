package sirin.desktop

import java.nio.file.Path
import scala.annotation.unused
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.ImageSnapshot
import sirin.graphics.unicode.NativeUnicode

class AppearanceSuite extends munit.FunSuite:
  private class MeasuredSurface extends Element.Simple[Unit]:
    var measures = 0
    def paint(@unused state: Unit, size: Size, canvas: Canvas)(using @unused committed: Committed): Unit =
      canvas.fill(Rect(Point.origin, size), canvas.theme.color(ColorRole.SurfaceRaised))
    override def measure(@unused state: Unit, constraints: Constraints)(using @unused committed: Committed): Size =
      measures += 1
      constraints.smallest

  test("render sessions remeasure only elements declaring a theme-color layout dependency") {
    val runtime = new HeadlessRuntime
    val renderer = new RenderSession(Path.of("../graphics/src/test/resources/fonts"), Path.of("target/appearance"))
    try
      val owner = runtime.scope("measure-colors")
      val ordinary = new MeasuredSurface
      val dependent = new MeasuredSurface
      val fixed = Signal.const(())
      val inputs = ElementInputs(fixed, fixed, fixed, fixed, Presentation.const(()))
      val mounted = runtime.mount(owner, Row(
        Element("ordinary", inputs, ordinary),
        Element("dependent", inputs.copy(layoutGlobals = Set(PhaseDependency.ThemeColors)), dependent)
      ))
      owner.observe(mounted.snapshot)(_ => ())
      val tree = runtime.withCommitted { reader =>
        reader(mounted.snapshot) match
          case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot, reader).tree
          case Value.Failed(error) => throw error
      }
      def render(generation: Long, scheme: ColorScheme): Unit =
        val payload = FramePayload(tree, 100, 80, 100, 80, theme = ThemeSnapshot(scheme))
        renderer.render(FrameRequest(generation, payload), () => false).asInstanceOf[RenderedFrame].close()
      render(1, ColorScheme.Dark)
      val ordinaryFirst = ordinary.measures
      val dependentFirst = dependent.measures
      assert(ordinaryFirst > 0 && dependentFirst > 0)
      render(2, ColorScheme.Dark)
      assertEquals(ordinary.measures, ordinaryFirst)
      assertEquals(dependent.measures, dependentFirst)
      render(3, ColorScheme.Light)
      assertEquals(ordinary.measures, ordinaryFirst)
      assert(dependent.measures > dependentFirst)
      val dependentLight = dependent.measures
      render(4, ColorScheme.Dark)
      assertEquals(ordinary.measures, ordinaryFirst)
      assert(dependent.measures > dependentLight)
    finally
      renderer.close()
      runtime.close()
  }

  test("system theme decoding preserves the unknown default and keeps Reduce Motion independent") {
    assertEquals(DesktopAppearance.snapshot(0, false), ThemeSnapshot(ColorScheme.Dark))
    assertEquals(DesktopAppearance.snapshot(1, false), ThemeSnapshot(ColorScheme.Light))
    assertEquals(DesktopAppearance.snapshot(2, true), ThemeSnapshot(ColorScheme.Dark, true))
    intercept[IllegalArgumentException](DesktopAppearance.snapshot(-1, false))
    intercept[IllegalArgumentException](DesktopAppearance.snapshot(3, false))
  }

  for retained <- Vector(false, true) do
    test(s"theme switching preserves the draft and matches a fresh render, retained=$retained") {
      val runtime = new HeadlessRuntime
      val owner = runtime.scope("appearance")
      val title = owner.cell("")
      val body = owner.cell("Unsaved document text")
      val checked = owner.cell(true)
      val note = owner.cell(demo.text.NoteField.State("Public custom text input"))
      val save = owner.action[Unit]("save", "Save")(_ => ())
      val cancel = owner.action[Unit]("cancel", "Cancel")(_ => ())
      val disabled = owner.action[Unit]("disabled", "Disabled", Signal.const(false))(_ => ())
      val mounted = runtime.mount(owner, Column(
        Text("Document workspace").style(TextStyle.Heading),
        TextField.bind(title).label("Title").error(Signal.const(Some("Enter a title."))),
        TextField.bind(body).label("Document").multiline.grow,
        demo.text.NoteField(note),
        Checkbox(Inputs.from(checked), Signal.const("Keep local draft"))(value => checked.set(value)),
        Row(Button(save).primary, Button(cancel), Button(disabled).primary).spacing(8.lp)
      ).spacing(8.lp).padding(16.lp))
      owner.observe(mounted.snapshot)(_ => ())
      val unicode = NativeUnicode.open()
      val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
      val fonts = Path.of("../graphics/src/test/resources/fonts")
      val output = Path.of(s"target/appearance/$retained")
      def session(): RenderSession = new RenderSession(fonts, output,
        batch = Option.when(retained)(sirin.render.BatchGpu.open()))
      val renderer = session()
      var generation = 0L
      def update(): Unit = runtime.withCommitted { reader =>
        reader(mounted.snapshot) match
          case Value.Ok(snapshot) => controller.update(CommittedTree.captureWithBindings(snapshot, reader))
          case Value.Failed(error) => throw error
      }
      def payload(scheme: ColorScheme): FramePayload =
        update()
        val state = controller.interaction.state
        FramePayload(controller.tree, 640, 400, 640, 400, state.focused, state.keyboard,
          textFocus = controller.textFocus, theme = ThemeSnapshot(scheme))
      def render(target: RenderSession, value: FramePayload, name: String): (ImageSnapshot, LayoutResult) =
        generation += 1
        val frame = target.render(FrameRequest(generation, value), () => false).asInstanceOf[RenderedFrame]
        try
          val pixels = frame.gpuFrame.fold(frame.pixels)(_.readback())
          if target eq renderer then controller.install(generation, frame.layout)
          FrameWorker.save(pixels, output.resolve(name + ".png"))
          (pixels, frame.layout)
        finally frame.close()
      try
        val initial = render(renderer, payload(ColorScheme.Dark), "initial")
        val document = initial._2.semanticParts.find(_._2.semantics.name.contains("Document")).get._1
        controller.focusElement(document)
        render(renderer, payload(ColorScheme.Dark), "focused")
        controller.handle(HostInput.TextInput("local edit "))
        val darkPayload = payload(ColorScheme.Dark)
        val draft = owner.read(body)
        val dark = render(renderer, darkPayload, "dark")
        val shaped = renderer.shaped
        val lightPayload = payload(ColorScheme.Light)
        val light = render(renderer, lightPayload, "light")
        assertEquals(renderer.shaped, shaped, "palette changes do not reshape unchanged text")
        assertEquals(owner.read(body), draft)
        assertEquals(light._2.semanticParts, dark._2.semanticParts)
        assertEquals(controller.textFocus, lightPayload.textFocus)
        assert(!java.util.Arrays.equals(light._1.pixelsCopy, dark._1.pixelsCopy))
        val fresh = session()
        try
          val reference = render(fresh, lightPayload, "light-fresh")
          assertEquals(light._1.pixelsCopy.toVector, reference._1.pixelsCopy.toVector)
        finally fresh.close()
        val restored = render(renderer, payload(ColorScheme.Dark), "dark-restored")
        assertEquals(restored._1.pixelsCopy.toVector, dark._1.pixelsCopy.toVector)
        assertEquals(renderer.shaped, shaped)
        assertEquals(owner.read(body), draft)
        for scheme <- ColorScheme.values; name <- Vector("Save", "Keep local draft") do
          val id = restored._2.semanticParts.find(_._2.semantics.name.contains(name)).get._1
          assert(controller.focusElement(id))
          val focusedPayload = payload(scheme)
          val stem = s"$scheme-${name.replace(' ', '-')}-focus"
          val focused = render(renderer, focusedPayload, stem)
          val restingPayload = focusedPayload.copy(focus = None, keyboardFocus = false)
          val resting = render(renderer, restingPayload, stem + "-off")
          assert(!java.util.Arrays.equals(focused._1.pixelsCopy, resting._1.pixelsCopy),
            "keyboard focus must remain visible on an accent background")
          val pressedPayload = restingPayload.copy(pressed = focusedPayload.focus)
          val pressed = render(renderer, pressedPayload, stem + "-pressed")
          assert(!java.util.Arrays.equals(pressed._1.pixelsCopy, resting._1.pixelsCopy),
            "press feedback must remain visible on an accent background")
        assertEquals(owner.read(body), draft)
      finally
        renderer.close()
        unicode.close()
        runtime.close()
    }
