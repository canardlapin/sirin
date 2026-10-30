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
            state.scrolls,
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
