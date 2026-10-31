package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

/** Checkbox is a public element: real text, the shared renderer, pointer, keyboard and assistive activation. */
class CheckboxSeamSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")

  private final class Fixture(view: Scope => View) extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val unicode = NativeUnicode.open()
    val renderer = new RenderSession(Path.of("../graphics/src/test/resources/fonts"), Path.of("target/checkbox-seam"))
    val owner = runtime.scope("checkbox")
    val mounted = runtime.mount(owner, view(owner))
    owner.observe(mounted.snapshot)(_ => ())
    val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
    private var serial = 0L
    var captured = CapturedTree(Node(NodeId(Vector("empty")), Content.Empty), Map.empty)

    /** Renders one frame and returns its checkbox semantics and pixels. */
    def frame(save: Option[String] = None): (Semantics, Array[Byte]) =
      captured = runtime.withCommitted(reader =>
        reader(mounted.snapshot) match
          case Value.Ok(snapshot) =>
            CommittedTree.captureWithBindings(snapshot, reader)
          case Value.Failed(error) =>
            throw error
      )
      controller.update(captured)
      serial += 1
      val state = controller.interaction.state
      val request = FrameRequest(
        serial,
        FramePayload(captured.tree, 240, 40, 240, 40, state.focused, state.keyboard, state.pressed.map(_.target))
      )
      val rendered = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
      try
        controller.install(serial, rendered.layout)
        save.foreach(name => FrameWorker.save(rendered.pixels, Path.of("target/checkbox-seam").resolve(name)))
        val parts = rendered.layout.semanticParts.map(_._2)
        assertEquals(parts.size, 1)
        (parts.head.semantics, rendered.pixels.pixelsCopy)
      finally rendered.close()
    def read[A](signal: Signal[A]): A = ok(runtime.transact(signal.get))
    def close(): Unit =
      renderer.close()
      unicode.close()
      runtime.close()

  test("a bound checkbox toggles by pointer, Space and assistive activation, repainting without reshaping") {
    var value = Option.empty[Cell[Boolean]]
    val f = new Fixture(scope => {
      val cell = scope.cell(false)
      value = Some(cell)
      Checkbox.bind(cell, "Send me the newsletter")
    })
    try
      val (start, unchecked) = f.frame(Some("unchecked.png"))
      assertEquals(start.role, Role.Checkbox)
      assertEquals(start.name, Some("Send me the newsletter"))
      assertEquals(start.checked, Some(false))
      val shaped = f.renderer.shaped
      f.controller.handle(HostInput.Down(8, 20))
      f.controller.handle(HostInput.Up(8, 20))
      assert(f.read(value.get))
      val (on, checked) = f.frame(Some("checked.png"))
      assertEquals(on.checked, Some(true))
      assert(!java.util.Arrays.equals(unchecked, checked), "the checked box paints differently")
      assertEquals(f.renderer.shaped, shaped, "toggling does not reshape the label")
      f.controller.handle(HostInput.KeyDown(Key.Space, Mods.none))
      assert(f.read(value.get), "Space toggles on release")
      f.controller.handle(HostInput.KeyUp(Key.Space, Mods.none))
      assert(!f.read(value.get))
      val (off, focused) = f.frame(Some("focused.png"))
      assertEquals(off.checked, Some(false))
      assert(!java.util.Arrays.equals(unchecked, focused), "keyboard focus adds a ring to the unchecked box")
      val instance = f.captured.elements.keys.head
      ok(f.runtime.transact(f.captured.bindings(instance).run(summon[UiTxn])))
      assert(f.read(value.get), "assistive Activate toggles")
    finally f.close()
  }

  test("a controlled checkbox reports the requested state and shows only what its owner commits") {
    var requests = Vector.empty[Boolean]
    var state = Option.empty[Cell[Boolean]]
    val f = new Fixture(scope => {
      val cell = scope.cell(false)
      state = Some(cell)
      Checkbox(cell, Signal.const("Locked"))(next => requests :+= next)
    })
    try
      val (_, before) = f.frame()
      f.controller.handle(HostInput.Down(8, 20))
      f.controller.handle(HostInput.Up(8, 20))
      assertEquals(requests, Vector(true))
      assert(!f.read(state.get.map(identity)), "the owner did not write, so the state is unchanged")
      val (shown, after) = f.frame()
      assertEquals(shown.checked, Some(false))
      assert(java.util.Arrays.equals(before, after))
      ok(f.runtime.transact(state.get.set(true)))
      f.controller.handle(HostInput.Down(8, 20))
      f.controller.handle(HostInput.Up(8, 20))
      assertEquals(requests, Vector(true, false), "the request is the negation of the committed state")
    finally f.close()
  }
