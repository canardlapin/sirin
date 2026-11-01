package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*

class DeveloperInspectorSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  for (width, height, retained) <- Vector((640, 400, false), (1000, 720, false), (640, 400, true)) do
    test(s"inspector ${width}x$height retained=$retained renders public controls and bounded cache receipts") {
      val runtime = new HeadlessRuntime
      val owner = runtime.scope("application")
      val field = owner.cell("")
      val save = owner.action[Unit]("document.save", "Save", field.map(_.nonEmpty))(_ => ())
      val inspector = new sirin.tooling.DeveloperInspector(runtime)(owner)
      val application = Windows.single("main")(_ => Window("Document", MenuBar.empty)(
        Column(TextField.bind(field), Button(save))))
      val mounted = runtime.mountWindows(owner, inspector.attach(application))
      owner.observe(mounted.snapshot)(_ => ())
      ok(runtime.transact {
        save.dispatch(())
        inspector.open.dispatch(())
      })
      assert(inspector.drain(Vector.empty))
      ok(runtime.transact {
        inspector.query.set("document.save")
        inspector.selected.set(Some(inspector.rows.get.find(_.label.contains("document.save")).get.key))
      })
      val output = Path.of(s"target/inspector/$width-$height-$retained")
      val renderer = new RenderSession(Path.of("../graphics/src/test/resources/fonts"), output,
        batch = Option.when(retained)(sirin.render.BatchGpu.open()),
        editorSystemFonts = true, diagnostics = true)
      var viewports = Map.empty[Long, Viewport]
      var generation = 0L
      def captured(): CapturedWindow = runtime.withCommitted(reader => reader(mounted.snapshot) match
        case Value.Ok(value) => WindowCapture.captureApplication(value, reader).windows
          .find(_.title == Value.Ok("Sirin Inspector")).get
        case Value.Failed(error) => throw error)
      def render(name: String): Unit =
        var settled = false
        val limit = generation + 10
        while !settled && generation < limit do
          generation += 1
          val request = FrameRequest(generation,
            FramePayload(captured().content.tree, width, height, width, height, viewports = viewports))
          val frame = renderer.render(request, () => false)
          try
            val updates = frame.layout.viewports.filter(v => !viewports.get(v.instance).contains(v.viewport))
            if updates.nonEmpty then
              ok(runtime.transact { updates.foreach(v => mounted.reportViewport(v.instance, v.viewport)) })
              viewports ++= updates.map(v => v.instance -> v.viewport)
            else frame match
              case rendered: RenderedFrame =>
                assert(rendered.cacheSummary.nonEmpty)
                assert(rendered.cacheSummary.length <= 1024)
                assert(rendered.cacheSummary.contains(if retained then "tiles=" else "baseCache="))
                assertEquals(rendered.gpuFrame.nonEmpty, retained)
                if retained then assertEquals(rendered.readbackBytes, 0L)
                val nodes = rendered.layout.semanticParts.map(_._2)
                assert(nodes.exists(_.semantics.name.contains("Refresh")))
                assert(nodes.exists(_.semantics.name.contains("Search captured records")))
                val pixels = rendered.gpuFrame.fold(rendered.pixels)(_.readback())
                FrameWorker.save(pixels, output.resolve(name + ".png"))
                settled = true
              case _ => fail("settled inspector omitted rendered pixels")
          finally frame.close()
        assert(settled)
      try
        render("inspector")
        if retained then
          owner.cell(new String(Array(0xd800.toChar, 0.toChar)))
          ok(runtime.transact {
            inspector.includeValues.set(true)
            inspector.budget.set(8192)
            inspector.refresh.dispatch(())
          })
          assert(inspector.drain(Vector.empty))
          ok(runtime.transact {
            inspector.choose.dispatch("Signals")
            inspector.query.set("ud800")
            inspector.selected.set(Some(inspector.filtered.get.head.key))
          })
          render("malformed")
      finally
        renderer.close()
        runtime.close()
    }
