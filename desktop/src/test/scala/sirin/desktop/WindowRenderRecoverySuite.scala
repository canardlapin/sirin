package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

class WindowRenderRecoverySuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"$other")
  private def value[A](r: HeadlessRuntime, signal: Signal[A]): A =
    r.read(signal) match
      case Value.Ok(value) =>
        value
      case Value.Failed(error) =>
        throw error
  private def capture(r: HeadlessRuntime, mounted: MountedView): CapturedApplication = r.withCommitted(reader =>
    reader(mounted.snapshot) match
      case Value.Ok(snapshot) =>
        WindowCapture.captureApplication(snapshot, reader)
      case Value.Failed(error) =>
        throw error
  )
  private def command(window: CapturedWindow, id: String): Activation =
    window
      .menu
      .actions
      .values
      .collectFirst {
        case MenuAction(MenuEntry.Item(command, _), activation, _) if command.id.name == id =>
          activation
      }
      .get
  test("the unchanged complex diagnostic refuses, then a real recovery frame retains draft, field identity and undo") {
    val r = new HeadlessRuntime
    val owner = r.scope("application")
    val text = owner.cell("original")
    val problem = owner.cell(Option.empty[String])
    val clearRef = owner.ref()
    val clear = owner.action[Unit]("clear", "Clear diagnostic", problem.map(_.nonEmpty))(_ => problem.set(None))
    val source = owner.keyed(Seq(1, 2))(identity)
    val mounted = r.mountWindows(
      owner,
      Windows(source) { (_, input) =>
        Window(input.map(i => s"Document $i"), MenuBar.empty)(
          Switch(input) {
            case 1 =>
              TextField.bind(text).label("Document").error(problem).multiline
            case _ =>
              Text("Other window remains available")
          }
        ).renderFailureContent(
          Switch(input) {
            case 1 =>
              Button(clear).ref(clearRef)
            case _ =>
              View.empty
          }
        )
      }
    )
    owner.observe(mounted.snapshot)(_ => ())
    val unicode = NativeUnicode.open(65536)
    val inputs = new WindowInputControllers(r, unicode.analyze(_), () => "", _ => (), () => ())
    val coordinator = new FrameCoordinator
    val first = capture(r, mounted).windows.head
    val router = new WindowRenderRecovery(r, mounted, first.instance, coordinator)
    val renderer =
      new RenderSession(
        Path.of("../graphics/src/test/resources/fonts"),
        Path.of("target/render-recovery"),
        editorSystemFonts = true
      )
    def current = capture(r, mounted).windows.head
    def request(): FrameRequest =
      val window = current
      router.update(window.renderStatus)
      inputs.update(window)
      val controller = inputs.current
      val state = controller.interaction.state
      router
        .request(
          FramePayload(
            controller.tree,
            640,
            400,
            640,
            400,
            state.scrolls,
            state.focused,
            state.keyboard,
            editors = controller.snapshots
          )
        )
        .get
    def freeze(request: FrameRequest, name: String): Unit =
      val file = Path.of("target/render-recovery").resolve(name)
      java.nio.file.Files.createDirectories(file.getParent)
      val output = new java.io.ObjectOutputStream(java.nio.file.Files.newOutputStream(file))
      try output.writeObject(request)
      finally output.close()
    def present(save: Boolean = false): Unit =
      val req = request()
      val frame = renderer.render(req, () => false).asInstanceOf[RenderedFrame]
      try
        assert(coordinator.accepts(frame.request))
        inputs.current.install(req.generation, frame.layout)
        if save then
          val file = Path.of("target/render-recovery/recovery.drawing")
          FrozenDrawing.save(file, req, frame.layout)
          val replayed = FrozenDrawing.replay(FrozenDrawing.read(file))
          assert(java.util.Arrays.equals(replayed.pixelsCopy, frame.pixels.pixelsCopy))
          FrameWorker.save(frame.pixels, Path.of("target/render-recovery/recovery.png"))
      finally
        frame.close()
    def focus(ref: NodeRef): Unit =
      ok(
        r.transact {
          ref.focus()
        }
      )
      val requests = r.takeFocusRequests()
      assert(requests.nonEmpty)
      assert(requests.exists(inputs.current.focusReference))
    try
      present()
      val field = current.content.editors.head._1
      inputs.current.focusEditor(field)
      inputs.current.handle(HostInput.KeyDown(Key.Character('a'), Mods.Primary))
      inputs.current.handle(HostInput.TextInput("retained draft"))
      inputs.current.update(current.content)
      inputs.current.handle(HostInput.Composition("仮", 1, 0))
      assert(inputs.current.state(field).snapshot.preedit.nonEmpty)
      ok(
        r.transact {
          problem.set(Some("a" + "😀" * 255))
        }
      )
      val failed = request()
      freeze(failed, "refused.frame")
      val error = intercept[IllegalArgumentException](renderer.render(failed, () => false))
      assert(error.getMessage.contains("paragraph exceeds shaping work budget"))
      val pending = new OwnerInputQueue[Int]
      pending.add(1, Some(HostInput.Down(1, 1)))
      pending.add(2, Some(HostInput.TextInput("unapplied")))
      assertEquals(
        router.report(failed.generation, error, current.renderStatus, pending.size, true),
        RenderFailureDisposition.Recovery
      )
      assertEquals(pending.abandon(), 2)
      assert(inputs.current.suspendPresentation())
      val retryFocus = r.takeFocusRequests()
      assert(retryFocus.nonEmpty)
      assertEquals(value(r, text), "retained draft")
      assertEquals(current.renderStatus.current.map(_.unappliedInputs), Some(2L))
      assert(current.renderStatus.current.exists(_.cancelledComposition))
      inputs.update(current)
      assert(!inputs.accepts(HostInput.KeyDown(Key.Tab, Mods.none), 100, 100, false))
      val quit = HostInput.KeyDown(Key.Character('q'), Mods.Primary)
      assert(inputs.accepts(quit, 101, 100, true, shortcut = true))
      assert(!inputs.accepts(quit, 99, 100, true, shortcut = true))
      assert(!inputs.needsGeometry(quit, shortcut = true))
      present(save = true)
      assert(!inputs.accepts(HostInput.KeyDown(Key.Tab, Mods.none), 99, 100, false))
      assert(!inputs.accepts(HostInput.KeyDown(Key.Enter, Mods.none), 99, 100, false))
      assert(inputs.accepts(HostInput.KeyDown(Key.Tab, Mods.none), 101, 100, false))
      assert(inputs.current.snapshots.isEmpty)
      assert(retryFocus.exists(inputs.current.focusReference))
      val other = capture(r, mounted).windows(1)
      val healthy = renderer.render(
        FrameRequest(100, FramePayload(other.content.tree, 320, 180, 320, 180, Map.empty)),
        () => false
      )
      healthy.close()
      focus(clearRef)
      inputs.current.handle(HostInput.KeyDown(Key.Enter, Mods.none))
      assertEquals(value(r, problem), None)
      present()
      assert(retryFocus.exists(inputs.current.focusReference))
      inputs.current.handle(HostInput.KeyDown(Key.Enter, Mods.none))
      assertEquals(current.renderStatus.current, None)
      present()
      assertEquals(current.content.editors.keySet, Set(field))
      assert(inputs.current.state(field).canUndo)
      assertEquals(inputs.current.state(field).snapshot.preedit, None)
      inputs.current.handle(HostInput.KeyDown(Key.Character('z'), Mods.Primary))
      assertEquals(value(r, text), "original")
      assertEquals(current.renderStatus.recent.map(_.generation), Vector(failed.generation))
      assertEquals(NativeApplication.liveApplications(), 0)
      assertEquals(NativeWindow.liveWindows(), 0)
    finally {
      renderer.close()
      unicode.close()
      r.close()
    }
  }
  test("current-generation recovery escalates once, parks repeated failure and rejects stale/replacement outcomes") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("application")
      val source = owner.keyed(Seq(1))(identity)
      val mounted = r.mountWindows(owner, Windows(source)((_, _) => Window("Document", MenuBar.empty)(Text("normal"))))
      owner.observe(mounted.snapshot)(_ => ())
      def current = capture(r, mounted).windows.head
      val coordinator = new FrameCoordinator
      val router = new WindowRenderRecovery(r, mounted, current.instance, coordinator)
      def payload(text: String) = FramePayload(
        Node(NodeId(Vector("scene")), Content.Label(text, TextStyle.Body, ColorRole.OnSurface)),
        100,
        100,
        100,
        100,
        Map.empty
      )
      val first = router.request(payload("old")).get
      val next = router.request(payload("current")).get
      val error = new IllegalArgumentException("resource refusal")
      assertEquals(
        router.report(first.generation, error, current.renderStatus, 2, false),
        RenderFailureDisposition.Ignored
      )
      assertEquals(current.renderStatus.current, None)
      assertEquals(
        router.report(next.generation, error, current.renderStatus, 2, false),
        RenderFailureDisposition.Recovery
      )
      assert(!coordinator.acceptsGeneration(next.generation))
      val recovery = router.request(payload("recovery")).get
      assertEquals(
        router.report(recovery.generation, error, current.renderStatus, 0, false),
        RenderFailureDisposition.Recovery
      )
      assert(current.renderStatus.current.exists(_.recoveryFailed))
      val minimal = router.request(payload("minimal")).get
      assertEquals(
        router.report(minimal.generation, error, current.renderStatus, 0, false),
        RenderFailureDisposition.Unavailable
      )
      assert(router.unavailable)
      assertEquals(router.request(payload("no automatic retry")), None)
      val old = current.instance
      val retry = command(current, "window.render.retry")
      ok(
        r.transact {
          retry.run(summon[UiTxn])
        }
      )
      router.update(current.renderStatus)
      assert(!router.unavailable)
      val again = router.request(payload("current")).get
      assert(again.generation > minimal.generation)
      ok(
        r.transact {
          source.remove(1)
        }
      )
      ok(
        r.transact {
          source.append(1)
        }
      )
      assert(current.instance != old)
      assertEquals(
        router.report(again.generation, error, current.renderStatus, 0, false),
        RenderFailureDisposition.Ignored
      )
      assertEquals(current.renderStatus, WindowRenderStatus.empty)
    finally
      r.close()
  }
