package sirin.desktop

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode
import sirin.specimen.*

class PersistentWorkbenchSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"$other")
  private def value[A](r: HeadlessRuntime, signal: Signal[A]): A =
    r.read(signal) match
      case Value.Ok(result) =>
        result
      case Value.Failed(error) =>
        throw error
  test("real public workspace captures, presents headlessly, types Unicode and saves through its menu command") {
    val directory = Files.createTempDirectory("sirin-public-workbench-")
    val repository = new FileDocumentRepository(directory)
    val id = DocumentId("document")
    Work.execute(
      repository.create(DocumentData(id, "Unicode document", "initial")),
      new Cancellation {
        def cancelled = false
      }
    )
    val runtime = new HeadlessRuntime
    val pool = runtime.scheduler.startWorkers(2)
    val app = runtime.scope("application")
    val services = DesktopApplicationPlatform.services(runtime)(app, () => ())
    val model =
      new DocumentWorkspace(app, repository, new FileDocumentImporter(repository), services, () => DocumentId("copy"))
    val mounted = runtime.mountWindows(app, model.windows)
    app.observe(mounted.snapshot)(_ => ())
    val unicode = NativeUnicode.open(65536)
    val captureRoot = Path.of("target/public-workbench")
    val renderer =
      new RenderSession(Path.of("../graphics/src/test/resources/fonts"), captureRoot, editorSystemFonts = true)
    val controller = new EditorController(runtime, unicode.analyze(_), () => "", _ => (), () => ())
    def driveUntil(done: => Boolean): Unit =
      val deadline = System.nanoTime() + 5000000000L
      while !done && System.nanoTime() < deadline do
        val turn = runtime.drive(64)
        assert(turn.failures.isEmpty, s"$turn")
        if !done then
          runtime.awaitActivity(10)
      assert(done)
    def capture(): CapturedApplication = runtime.withCommitted(reader =>
      reader(mounted.snapshot) match
        case Value.Ok(snapshot) =>
          WindowCapture.captureApplication(snapshot, reader)
        case Value.Failed(error) =>
          throw error
    )
    var serial = 0L
    var viewports = Map.empty[Long, RowViewport]
    def frame(): CapturedApplication =
      var result = capture()
      var settled = false
      var attempts = 0
      while !settled && attempts < 10 do
        val tree = result.windows.head.content
        controller.update(tree)
        serial += 1
        val state = controller.interaction.state
        val request = FrameRequest(
          serial,
          FramePayload(
            controller.tree,
            1000,
            720,
            1000,
            720,
            state.scrolls,
            state.focused,
            state.keyboard,
            viewports = viewports,
            editors = controller.snapshots
          )
        )
        val output = renderer.render(request, () => false)
        try
          if output.layout.viewports.exists(v => !viewports.get(v.instance).contains(v.viewport)) then
            ok(
              runtime.transact {
                output.layout.viewports.foreach(v => mounted.setViewport(v.instance, v.viewport))
              }
            )
            viewports = output.layout.viewports.map(v => v.instance -> v.viewport).toMap
            result = capture()
          else
            controller.install(serial, output.layout)
            Files.createDirectories(captureRoot)
            val frozen =
              new java.io.ObjectOutputStream(Files.newOutputStream(captureRoot.resolve(s"workspace-$serial.frame")))
            try frozen.writeObject(request)
            finally frozen.close()
            output match
              case rendered: RenderedFrame =>
                FrameWorker.save(rendered.pixels, captureRoot.resolve("workspace.png"))
              case _ =>
                fail("settled frame omitted pixels")
            settled = true
        finally output.close()
        attempts += 1
      assert(settled)
      result
    try
      driveUntil(value(runtime, model.rows.snapshot).nonEmpty)
      ok(
        runtime.transact {
          model.open.dispatch(id)
        }
      )
      driveUntil(
        ok(
          runtime.transact {
            model.documents.get(id).nonEmpty
          }
        )
      )
      val document = ok(
        runtime.transact {
          model.documents.get(id).get
        }
      )
      val before = frame()
      val field =
        before
          .windows
          .head
          .content
          .editors
          .find((_, binding) => binding.description.value eq document.editor.body)
          .get
          ._1
      controller.focusEditor(field)
      frame()
      controller.handle(HostInput.KeyDown(Key.Character('a'), Mods.Primary))
      controller.handle(HostInput.TextInput("typed café 日本語 😀"))
      assertEquals(value(runtime, document.editor.body), "typed café 日本語 😀")
      val after = frame()
      val save =
        runtime
          .withCommitted(_ =>
            KeyboardRouting.command(
              after.windows.head.content,
              controller.interaction.state.focused,
              Chord.primary('s'),
              after.windows.head.menu,
              after.menu
            )
          )
          .get
      ok(
        runtime.transact {
          save.run(summon[UiTxn])
        }
      )
      driveUntil(!value(runtime, document.editor.dirty))
      frame()
      val reopened = Work.execute(
        new FileDocumentRepository(directory).open(id),
        new Cancellation {
          def cancelled = false
        }
      )
      assertEquals(reopened.initial.data.body, "typed café 日本語 😀")
      assertEquals(NativeApplication.liveApplications(), 0)
      assertEquals(NativeWindow.liveWindows(), 0)
    finally
      renderer.close()
      unicode.close()
      runtime.close()
      assert(pool.awaitStopped(5000))
      val walk = Files.walk(directory)
      try walk.iterator().asScala.toVector.reverse.foreach(Files.delete(_))
      finally walk.close()
  }

/** Offline diagnostic for trusted frames emitted by the test above; no app/window launch. */
object WorkspaceRenderReplay:
  def main(args: Array[String]): Unit =
    require(args.nonEmpty, "Supply trusted generated .frame fixtures")
    val renderer =
      new RenderSession(
        Path.of("../graphics/src/test/resources/fonts"),
        Path.of("target/public-workbench/replay"),
        editorSystemFonts = true
      )
    try args.foreach { name =>
        val input = new java.io.ObjectInputStream(Files.newInputStream(Path.of(name)))
        val request =
          try input.readObject().asInstanceOf[FrameRequest]
          finally input.close()
        println(
          s"replay=${Path.of(name).getFileName} viewport=${request.payload.logicalWidth}x${request
              .payload
              .logicalHeight}"
        )
        val frame = renderer.render(request, () => false)
        try
          frame match
            case rendered: RenderedFrame =>
              FrameWorker.save(
                rendered.pixels,
                Path.of("target/public-workbench/replay").resolve(Path.of(name).getFileName.toString + ".png")
              )
            case _ =>
              throw new IllegalStateException("frozen frame requested new viewport feedback")
        finally frame.close()
      }
    finally renderer.close()
