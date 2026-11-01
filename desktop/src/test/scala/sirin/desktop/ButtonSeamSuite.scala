package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

class ButtonSeamSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")

  test("public Button uses custom phases with shared pointer, keyboard, semantics and retained feedback") {
    val runtime = new HeadlessRuntime
    val unicode = NativeUnicode.open()
    val renderer = new RenderSession(Path.of("../graphics/src/test/resources/fonts"), Path.of("target/button-seam"))
    try
      val owner = runtime.scope("button")
      val count = owner.cell(0)
      val enabled = owner.cell(true)
      val command = owner.action[Unit]("press", "Press me", enabled)(_ => count.update(_ + 1))
      val mounted = runtime.mount(owner, Button(command))
      owner.observe(mounted.snapshot)(_ => ())
      val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
      var serial = 0L
      def frame(): (CapturedTree, Array[Byte]) =
        val tree = runtime.withCommitted(reader =>
          reader(mounted.snapshot) match
            case Value.Ok(snapshot) =>
              CommittedTree.captureWithBindings(snapshot, reader)
            case Value.Failed(error) =>
              throw error
        )
        assert(tree.tree.content.isInstanceOf[Content.Custom])
        controller.update(tree)
        serial += 1
        val state = controller.interaction.state
        val request = FrameRequest(
          serial,
          FramePayload(
            tree.tree,
            160,
            60,
            160,
            60,
            state.focused,
            state.keyboard,
            state.pressed.map(_.target)
          )
        )
        val rendered = renderer.render(request, () => false).asInstanceOf[RenderedFrame]
        try
          controller.install(serial, rendered.layout)
          val semantic = rendered.layout.semanticParts.head._2
          assertEquals(semantic.semantics.role, Role.Button)
          assertEquals(semantic.semantics.name, Some("Press me"))
          assertEquals(semantic.bounds, Rect(Point.origin, Size(160.lp, 60.lp)))
          (tree, rendered.pixels.pixelsCopy)
        finally
          rendered.close()
      val (first, base) = frame()
      val shaped = renderer.shaped
      val instance = first.elements.keys.head
      controller.handle(HostInput.Down(10, 10))
      val (_, pressed) = frame()
      assert(!java.util.Arrays.equals(base, pressed))
      assertEquals(renderer.shaped, shaped)
      controller.handle(HostInput.Up(200, 10))
      assertEquals(
        ok(
          runtime.transact {
            count.get
          }
        ),
        0
      )
      controller.handle(HostInput.Down(10, 10))
      controller.handle(HostInput.Up(10, 10))
      assertEquals(
        ok(
          runtime.transact {
            count.get
          }
        ),
        1
      )
      frame()
      controller.handle(HostInput.KeyDown(Key.Space, Mods.none))
      assertEquals(
        ok(
          runtime.transact {
            count.get
          }
        ),
        1
      )
      controller.handle(HostInput.KeyUp(Key.Space, Mods.none))
      assertEquals(
        ok(
          runtime.transact {
            count.get
          }
        ),
        2
      )
      ok(
        runtime.transact {
          mounted.update(Button(command))
        }
      )
      val (rebuilt, _) = frame()
      assertEquals(rebuilt.elements.keys.toSet, Set(instance))
      ok(
        runtime.transact {
          enabled.set(false)
        }
      )
      val (disabled, _) = frame()
      assert(!disabled.bindings(instance).enabled)
      assertEquals(renderer.shaped, shaped)
      ok(
        runtime.transact {
          first.bindings(instance).run(summon[UiTxn])
        }
      )
      assertEquals(
        ok(
          runtime.transact {
            count.get
          }
        ),
        2
      )
      ok(
        runtime.transact {
          mounted.update(View.empty)
        }
      )
      assertEquals(
        runtime.transact {
          first.bindings(instance).run(summon[UiTxn])
        },
        CommitResult.Failed(KernelError.DisposedScope)
      )
    finally
      renderer.close()
      unicode.close()
      runtime.close()
  }

  test("single-line labels bound height, ellipsize both directions and preserve names and activation") {
    val runtime = new HeadlessRuntime
    val unicode = NativeUnicode.open()
    val root = Path.of("target/button-single-line")
    val renderer = new RenderSession(Path.of("../graphics/src/test/resources/fonts"), root, editorSystemFonts = true)
    try
      val owner = runtime.scope("single-line")
      val label = owner.cell("Short")
      val count = owner.cell(0)
      val command = owner.action[Unit]("press", "Press")(_ => count.update(_ + 1))
      def button(single: Boolean): View =
        val base = Button(label).primary
        Column((if single then base.singleLine else base).command(command))
      val mounted = runtime.mount(owner, button(true))
      owner.observe(mounted.snapshot)(_ => ())
      val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
      var serial = 0L
      var lineHeight = Lp.zero
      def frame(width: Int = 160): (Long, SemanticNode, Vector[(String, Double)]) =
        val tree = runtime.withCommitted(reader =>
          reader(mounted.snapshot) match
            case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot, reader)
            case Value.Failed(error) => throw error
        )
        controller.update(tree)
        serial += 1
        val output = renderer.render(FrameRequest(serial, FramePayload(tree.tree, width, 220, width, 220)), () => false)
        try
          controller.install(serial, output.layout)
          val semantic = output.layout.semanticParts.find(_._2.semantics.role == Role.Button).get
          output.layout.text.requests.find(r => r.content != "…" && r.maxWidth.toDouble.isPosInfinity)
            .foreach(request => lineHeight = output.layout.text.get(request).get.size.height)
          def glyphs(drawing: sirin.graphics.Drawing): Vector[(String, Double)] =
            drawing.commands.flatMap {
              case sirin.graphics.DrawCommand.Glyphs(run, at, _) => Vector(run.source.text -> at.x)
              case sirin.graphics.DrawCommand.Draw(child) => glyphs(child)
              case _ => Vector.empty
            }
          output match
            case rendered: RenderedFrame => FrameWorker.save(rendered.pixels, root.resolve(s"label-$serial.png"))
            case _ => fail("missing pixels")
          (semantic._1, semantic._2, glyphs(output.layout.drawing(DevelopmentTheme)))
        finally output.close()
      val (identity, short, shortGlyphs) = frame()
      assert(!shortGlyphs.exists(_._1 == "…"))
      val titles = Vector(
        "A lengthy title with café and office ligatures that cannot fit",
        "عنوان عربي طويل جدا لا يتسع داخل هذا الزر",
        "First line\r\nSecond line\u2028Third line\u000BFourth line\u000CLast line"
      )
      for title <- titles do
        ok(runtime.transact(label.set(title)))
        val (next, node, glyphs) = frame()
        assertEquals(next, identity)
        assertEquals(node.semantics.name, Some(title))
        assertEquals(
          node.bounds.size.height,
          lineHeight + 16.lp,
          "height follows one shaped line including fallback fonts"
        )
        val marker = glyphs.find(_._1 == "…").getOrElse(fail("overflow needs an ellipsis"))
        assertEquals(marker._2 <= 12.01, title.startsWith("عنوان"), "RTL marker is on the left")
        assert(!glyphs.exists(_._1.exists(ch => ch == '\n' || ch == '\r' || ch == '\u2028')))
        val at = node.bounds.center
        controller.handle(HostInput.Down(at.x.toDouble, at.y.toDouble))
        controller.handle(HostInput.Up(at.x.toDouble, at.y.toDouble))
        frame()
        controller.handle(HostInput.KeyDown(Key.Space, Mods.none))
        controller.handle(HostInput.KeyUp(Key.Space, Mods.none))
      assertEquals(ok(runtime.transact(count.get)), titles.size * 2)
      frame(30)
      frame(16)
      ok(runtime.transact(mounted.update(button(false))))
      val (_, wrapped, _) = frame()
      assert(wrapped.bounds.size.height.toDouble > short.bounds.size.height.toDouble, "ordinary buttons still wrap")
    finally
      renderer.close()
      unicode.close()
      runtime.close()
  }
