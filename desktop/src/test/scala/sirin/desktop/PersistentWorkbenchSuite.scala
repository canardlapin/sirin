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
  for (width, height) <- Vector((1000, 720), (640, 400)) do
    test(s"workspace ${width}x$height fits, guards decisions, retains docking and saves Unicode") {
      val directory = Files.createTempDirectory("sirin-public-workbench-")
      val repository = new FileDocumentRepository(directory)
      val id = DocumentId("document")
      val longTitle = "A Unicode document with a title that exceeds the width of its sidebar row"
      Work.execute(
        repository.create(DocumentData(id, longTitle, "initial")),
        new Cancellation {
          def cancelled = false
        }
      )
      Work.execute(
        repository.create(DocumentData(DocumentId("neighbor"), "B adjacent document", "neighbor")),
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
      val captureRoot = Path.of(s"target/public-workbench/${width}x$height")
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
      val nativeTree = new NativeSemanticTree
      var serial = 0L
      var replayChecked = false
      var viewports = Map.empty[Long, Viewport]
      def frame(windowIndex: Int = 0): CapturedApplication =
        var result = capture()
        var settled = false
        var attempts = 0
        while !settled && attempts < 10 do
          val tree = result.windows(windowIndex).content
          controller.update(tree)
          serial += 1
          val state = controller.interaction.state
          val request = FrameRequest(
            serial,
            FramePayload(
              controller.tree,
              width,
              height,
              width,
              height,
              state.focused,
              state.keyboard,
              viewports = viewports,
              textFocus = controller.textFocus
            )
          )
          val output = renderer.render(request, () => false)
          try
            if output.layout.viewports.exists(v => !viewports.get(v.instance).contains(v.viewport)) then
              ok(
                runtime.transact {
                  output.layout.viewports.foreach(v => mounted.reportViewport(v.instance, v.viewport))
                }
              )
              viewports = output.layout.viewports.map(v => v.instance -> v.viewport).toMap
              result = capture()
            else
              controller.install(serial, output.layout)
              nativeTree.capture(output.layout, controller.interaction.state.focused)
              Files.createDirectories(captureRoot)
              val frozenPath = captureRoot.resolve(s"workspace-$serial.drawing")
              FrozenDrawing.save(frozenPath, request, output.layout)
              output match
                case rendered: RenderedFrame =>
                  FrameWorker.save(rendered.pixels, captureRoot.resolve("workspace.png"))
                  if !replayChecked then
                    val replayed = FrozenDrawing.replay(FrozenDrawing.read(frozenPath))
                    assert(java.util.Arrays.equals(replayed.pixelsCopy, rendered.pixels.pixelsCopy))
                    replayChecked = true
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
        frame()
        val body = value(runtime, document.editor.body)
        val field = controller
          .layout
          .get
          .semanticParts
          .find((_, node) => node.semantics.role == Role.TextInput && node.semantics.value.contains(body))
          .get
          ._1
        def part(name: String, role: Role): (Long, SemanticNode) =
          controller.layout.get.semanticParts.find { (_, node) =>
            node.semantics.name.contains(name) && node.semantics.role == role
          }.getOrElse(fail(s"missing $name ($role)"))
        def inside(rect: Rect): Boolean =
          rect.origin.x.toDouble >= 0 && rect.origin.y.toDouble >= 0 &&
            rect.origin.x.toDouble + rect.size.width.toDouble <= width &&
            rect.origin.y.toDouble + rect.size.height.toDouble <= height
        val titlePart = part(longTitle, Role.Button)
        val titleBounds = titlePart._2.bounds
        assert(titleBounds.size.height.toDouble <= 40, "long titles remain one line")
        val moveParts = controller.layout.get.semanticParts.filter { (_, node) =>
          node.semantics.name.exists(name => name == "Up" || name == "Down") &&
            node.bounds.origin.y.toDouble >= titleBounds.origin.y.toDouble &&
            node.bounds.origin.y.toDouble < titleBounds.origin.y.toDouble + 80
        }
        assertEquals(moveParts.size, 2)
        moveParts.foreach { (_, node) =>
          assert(node.bounds.origin.y.toDouble >= titleBounds.origin.y.toDouble + titleBounds.size.height.toDouble)
          assert(node.bounds.origin.y.toDouble + node.bounds.size.height.toDouble <= titleBounds.origin.y.toDouble + 80)
          assert(node.bounds.size.height.toDouble >= 30, "move controls keep a usable target")
        }
        val down = moveParts.find(_._2.semantics.name.contains("Down")).get._2.bounds.center
        controller.handle(HostInput.Down(down.x.toDouble, down.y.toDouble))
        controller.handle(HostInput.Up(down.x.toDouble, down.y.toDouble))
        frame()
        val movedTitle = part(longTitle, Role.Button)
        assertEquals(movedTitle._1, titlePart._1, "reordering retains the title control")
        assertEquals(movedTitle._2.bounds.origin.y, titleBounds.origin.y + 80.lp)
        val originalBody = part("Document", Role.TextInput)
        assert(inside(originalBody._2.bounds))
        assert(originalBody._2.bounds.size.width.toDouble >= 300, "editor must use the available pane width")
        assert(originalBody._2.bounds.size.height.toDouble >= 100, "body is usable at the default window size")
        assert(inside(part("Title", Role.TextInput)._2.bounds))
        assert(inside(part("Save", Role.Button)._2.bounds))
        ok(runtime.transact(model.moveEditor.dispatch(())))
        frame()
        val movedBody = part("Document", Role.TextInput)
        assertEquals(movedBody._1, originalBody._1, "docking preserves the actual field instance")
        assert(movedBody._2.bounds.origin.x.toDouble > originalBody._2.bounds.origin.x.toDouble)
        assertEquals(movedBody._2.bounds.size.width, originalBody._2.bounds.size.width)
        assert(controller.focusElement(field))
        frame()
        controller.handle(HostInput.KeyDown(Key.Character('a'), Mods.Primary))
        controller.handle(HostInput.TextInput("typed café 日本語 😀"))
        assertEquals(value(runtime, document.editor.body), "typed café 日本語 😀")
        frame()
        val newButton = part("New", Role.Button)._2.bounds
        assertEquals(ok(runtime.transact(model.requestWindowClose(1))), CloseDecision.KeepOpen)
        frame()
        assertEquals(controller.layout.get.layers.size, 1)
        assert(controller.layout.get.layers.head.frame.modal)
        val dialog = nativeTree.nodes.find(_.part.semantics.role == Role.Dialog).get
        assert(dialog.modal)
        assert(nativeTree.nodes.exists(n => n.part.semantics.role == Role.Label &&
          n.part.semantics.name.exists(_.contains("unsaved drafts"))))
        assert(nativeTree.nodes.filterNot(_.id == dialog.id).forall(_.parent == dialog.id))
        assert(!nativeTree.nodes.exists(_.part.semantics.name.contains("New")))
        val dialogIds = nativeTree.nodes.map(_.id)
        assert(inside(part("Close this view?", Role.Dialog)._2.bounds))
        val keep = part("Keep editing", Role.Button)
        assert(inside(keep._2.bounds))
        assert(inside(part("Close view", Role.Button)._2.bounds))
        assert(controller.interaction.state.focused.contains(NodeId(Vector("element", keep._1))))
        controller.handle(HostInput.Down(newButton.origin.x.toDouble + 2, newButton.origin.y.toDouble + 2))
        controller.handle(HostInput.Up(newButton.origin.x.toDouble + 2, newButton.origin.y.toDouble + 2))
        frame()
        assert(value(runtime, model.prompt).nonEmpty, "outside clicks neither decide nor dismiss")
        assertEquals(ok(runtime.transact(model.documents.size)), 1, "background New cannot activate")
        for _ <- 0 until 5 do
          controller.handle(HostInput.KeyDown(Key.Tab, Mods.none))
          controller.handle(HostInput.KeyUp(Key.Tab, Mods.none))
          frame()
          assert(controller.interaction.inputFocus.exists(_.layer.nonEmpty), "Tab stays in the decision")
        controller.handle(HostInput.KeyDown(Key.Escape, Mods.none))
        controller.handle(HostInput.KeyUp(Key.Escape, Mods.none))
        val after = frame()
        assertEquals(value(runtime, model.prompt), None)
        assert(!nativeTree.nodes.exists(n => dialogIds.contains(n.id)))
        assert(nativeTree.nodes.exists(_.part.semantics.name.contains("New")))
        assert(controller.layout.get.layers.isEmpty)
        assertEquals(value(runtime, document.editor.body), "typed café 日本語 😀")
        assert(controller.interaction.state.focused.contains(NodeId(Vector("element", field))))
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
        val importSource = directory.resolve("source")
        val importRepository = new FileDocumentRepository(importSource)
        for index <- 0 until 8 do
          Work.execute(
            importRepository.create(DocumentData(DocumentId(s"import-$index"), s"Import document $index", "body")),
            new Cancellation {
              def cancelled = false
            }
          )
        ok(runtime.transact {
          model.importSource.set(importSource.toString)
          model.showImport.dispatch(())
        })
        ok(runtime.transact(model.prepareImport.dispatch(())))
        driveUntil(value(runtime, model.currentImport).nonEmpty)
        val imported = value(runtime, model.currentImport).get
        ok(runtime.transact(imported.start.dispatch(())))
        driveUntil(value(runtime, imported.records).size == 8 && value(runtime, imported.run.running) == 0)
        frame(1)
        assert(inside(part("Source directory", Role.TextInput)._2.bounds))
        assert(inside(part("Open workbench", Role.Button)._2.bounds))
        controller.handle(HostInput.Wheel(width / 2.0, height / 2.0, 1000))
        frame(1)
        val importField = part("Source directory", Role.TextInput)
        assert(inside(importField._2.bounds), "source remains available after scrolling receipts")
        assert(inside(part("Review import", Role.Button)._2.bounds))
        assert(inside(part("Open workbench", Role.Button)._2.bounds))
        runtime.takeFocusRequests()
        ok(runtime.transact(model.showImport.dispatch(())))
        assert(runtime.takeFocusRequests().exists(controller.focusReference), "Import can focus the existing window")
        assert(controller.interaction.state.focused.contains(NodeId(Vector("element", importField._1))))
        assert(replayChecked)
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
        if name.endsWith(".drawing") then
          val pixels = FrozenDrawing.replay(FrozenDrawing.read(Path.of(name)))
          FrameWorker.save(
            pixels,
            Path.of("target/public-workbench/replay").resolve(Path.of(name).getFileName.toString + ".png")
          )
        else
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
