package sirin.comparison

import java.nio.file.Files
import java.util.concurrent.{Callable, ConcurrentHashMap, CountDownLatch, FutureTask, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import scala.jdk.CollectionConverters.*

class PersistentWorkspaceSuite extends munit.FunSuite:
  override def beforeAll(): Unit =
    val ready = new CountDownLatch(1)
    Platform.startup(() => {
      Platform.setImplicitExit(false)
      ready.countDown()
    })
    assert(ready.await(10, TimeUnit.SECONDS), "JavaFX toolkit startup")
  override def afterAll(): Unit = Platform.exit()

  private def fx[A](body: => A): A =
    val task = new FutureTask[A](new Callable[A]:
      def call(): A = body
    )
    Platform.runLater(task)
    task.get(10, TimeUnit.SECONDS)

  private def eventually(body: => Boolean): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var done = body
    while !done && System.nanoTime() < deadline do
      Thread.sleep(10)
      done = body
    assert(done, "condition before deadline")

  private class MemoryStore extends Store:
    val data = new ConcurrentHashMap[String, StoredDocument]
    private val serial = new AtomicInteger
    @volatile var saveHook: Document => Unit = _ => ()
    @volatile var openHook: String => Unit = _ => ()
    @volatile var createHook: Document => Unit = _ => ()
    def put(document: Document): StoredDocument =
      val stored = StoredDocument(document, serial.incrementAndGet().toString)
      data.put(document.id, stored)
      stored
    def list(query: String, cancelled: () => Boolean): Vector[Summary] =
      data.values().asScala.toVector.filter(d => d.data.title.contains(query))
        .map(d => Summary(d.data.id, d.data.title, d.stamp)).sortBy(_.id)
    private def session(stored: StoredDocument): StoreSession = new StoreSession:
      val initial = stored
      private var expected = stored.stamp
      def save(document: Document, cancelled: () => Boolean): StoredDocument = synchronized {
        saveHook(document)
        if cancelled() then throw new InterruptedException
        if data.get(document.id).stamp != expected then throw new StoreConflict
        val written = put(document)
        expected = written.stamp
        written
      }
    def open(id: String, cancelled: () => Boolean): StoreSession =
      openHook(id)
      session(data.get(id))
    def create(document: Document, cancelled: () => Boolean): StoreSession =
      createHook(document)
      if data.containsKey(document.id) then throw new StoreConflict
      session(put(document))

  private def opened(store: MemoryStore): (PersistentWorkspace, Draft) =
    store.put(Document("first", "First", "Initial"))
    val model = fx(new PersistentWorkspace(store))
    fx(model.select("first"))
    eventually(fx(model.selected.value.nonEmpty))
    (model, fx(model.selected.value.get))

  test("filter, reorder and disk refresh retain the exact dirty draft") {
    val store = new MemoryStore
    store.put(Document("second", "Second", "Other"))
    val (model, draft) = opened(store)
    try
      eventually(fx(!model.searching.value && model.rows.size == 2))
      fx {
        draft.title.value = "Draft"
        draft.body.value = "Unsaved"
        model.move("first", 1)
        assertEquals(model.rows.map(_.id).toVector, Vector("second", "first"))
        model.query.value = "Second"
        model.refresh()
      }
      eventually(fx(!model.searching.value && model.rows.size == 1))
      assert(fx(model.selected.value.contains(draft)))
      store.put(Document("first", "External", "Changed on disk"))
      fx {
        model.query.value = ""
        model.refresh()
      }
      eventually(fx(!model.searching.value && model.rows.size == 2))
      fx {
        assert(model.selected.value.contains(draft))
        assertEquals(draft.title.value, "Draft")
        assertEquals(draft.body.value, "Unsaved")
        assert(draft.diskNotice.value.contains("Changed on disk"))
      }
      store.data.put("first", draft.lastSaved)
      fx(model.refresh())
      eventually(fx(!model.searching.value))
      assertEquals(fx(draft.diskNotice.value), "")
      store.data.remove("first")
      fx(model.refresh())
      eventually(fx(!model.searching.value && model.rows.size == 1))
      fx {
        assert(model.selected.value.contains(draft))
        assertEquals(draft.body.value, "Unsaved")
        assert(draft.diskNotice.value.contains("Not found on disk"))
      }
    finally fx(model.close())
  }

  test("actual Task completion of an older save leaves a newer revision dirty") {
    val store = new MemoryStore
    val (model, draft) = opened(store)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    store.saveHook = _ =>
      entered.countDown()
      if !release.await(10, TimeUnit.SECONDS) then throw new java.util.concurrent.TimeoutException("save release")
    try
      fx {
        draft.body.value = "revision 17"
        assert(model.save(draft))
      }
      assert(entered.await(10, TimeUnit.SECONDS))
      fx {
        draft.body.value = "revision 18"
        assert(draft.status.value.contains("newer edits"))
      }
      release.countDown()
      eventually(fx(draft.pending.value == 0))
      assertEquals(store.data.get("first").data.body, "revision 17")
      assert(fx(draft.dirty.value))
      fx(assert(model.save(draft)))
      eventually(fx(draft.pending.value == 0 && !draft.dirty.value))
      assertEquals(store.data.get("first").data.body, "revision 18")
    finally
      release.countDown()
      fx(model.close())
  }

  test("reload rechecks draft authority after background I/O") {
    val store = new MemoryStore
    val (model, draft) = opened(store)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    store.openHook = _ =>
      entered.countDown()
      if !release.await(10, TimeUnit.SECONDS) then throw new java.util.concurrent.TimeoutException("open release")
    try
      fx(model.reload(draft, draft.revision))
      assert(entered.await(10, TimeUnit.SECONDS))
      fx(draft.body.value = "newer edit")
      release.countDown()
      eventually(fx(model.notice.value.contains("changed while reloading")))
      assert(fx(model.selected.value.contains(draft)))
      assertEquals(fx(draft.body.value), "newer edit")
    finally
      release.countDown()
      fx(model.close())
  }

  test("save queue retains captured revisions and refuses excess submissions visibly") {
    val store = new MemoryStore
    val (model, draft) = opened(store)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    store.saveHook = _ =>
      entered.countDown()
      if !release.await(10, TimeUnit.SECONDS) then throw new java.util.concurrent.TimeoutException("save queue")
    try
      fx {
        draft.body.value = "one"
        assert(model.save(draft))
      }
      assert(entered.await(10, TimeUnit.SECONDS))
      fx {
        draft.body.value = "two"
        assert(model.save(draft))
        draft.body.value = "three"
        assert(model.save(draft))
        draft.body.value = "four"
        assert(!model.save(draft))
        assert(draft.notice.value.contains("Save queue full"))
        assertEquals(draft.pending.value, 3)
      }
      release.countDown()
      eventually(fx(draft.pending.value == 0))
      assertEquals(store.data.get("first").data.body, "three")
      assertEquals(fx(draft.body.value), "four")
      assert(fx(draft.dirty.value))
    finally
      release.countDown()
      fx(model.close())
  }

  test("stale close consent is refused and reopening creates a new editor lifetime") {
    val store = new MemoryStore
    val (model, draft) = opened(store)
    try
      fx {
        val oldRevision = draft.revision
        draft.body.value = "new edit"
        assert(!model.closeDocument(draft, oldRevision))
        assert(model.selected.value.contains(draft))
        assert(model.closeDocument(draft, draft.revision))
        assertEquals(model.selected.value, None)
        val before = model.changed.value
        draft.title.value = "retired"
        assertEquals(model.changed.value, before)
        assert(!model.save(draft))
        model.select("first")
      }
      eventually(fx(model.selected.value.nonEmpty))
      fx {
        assert(model.selected.value.get ne draft)
        assertEquals(model.selected.value.get.body.value, "Initial")
      }
    finally fx(model.close())
  }

  test("closing the owner ignores late results even when a service ignores interruption") {
    val store = new MemoryStore
    store.put(Document("first", "First", "Initial"))
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val finished = new CountDownLatch(1)
    store.openHook = _ =>
      entered.countDown()
      var waiting = true
      while waiting do
        try waiting = !release.await(10, TimeUnit.SECONDS)
        catch case _: InterruptedException => ()
      finished.countDown()
    val model = fx(new PersistentWorkspace(store))
    try
      fx(model.select("first"))
      assert(entered.await(10, TimeUnit.SECONDS))
      fx(model.close())
      release.countDown()
      assert(finished.await(10, TimeUnit.SECONDS))
      fx {
        assert(!model.isAlive)
        assertEquals(model.documents.size, 0)
        assertEquals(model.selected.value, None)
      }
    finally
      release.countDown()
      fx(model.close())
  }

  test("import review freezes source stamps and preserves partial failure receipts") {
    val sourcePath = Files.createTempDirectory("scalafx-import-")
    val source = new DocumentStore(sourcePath)
    Vector("one", "two", "three").foreach { id =>
      source.create(Document(id, id, "Original"), () => false)
    }
    val target = new MemoryStore
    target.put(Document("one", "Existing", "Never replace"))
    val model = fx(new PersistentWorkspace(target))
    try
      fx(model.reviewImport(sourcePath))
      eventually(fx(model.importPlan.value.nonEmpty))
      source.open("two", () => false).save(Document("two", "two", "Changed after review"), () => false)
      fx(assert(model.startImport()))
      eventually(fx(!model.importing.value))
      fx {
        assertEquals(model.receipts.size, 3)
        assertEquals(model.receipts.count(_.failure.nonEmpty), 2)
        assertEquals(model.importHistory.last, ImportResult(3, 3, 1, 2, false))
        assert(!model.startImport())
      }
      assertEquals(target.data.get("one").data.body, "Never replace")
      assertEquals(target.data.get("three").data.body, "Original")
      assert(!target.data.containsKey("two"))
    finally fx(model.close())
  }

  test("cancel a producer blocked by the two-receipt FIFO; committed files remain") {
    val sourcePath = Files.createTempDirectory("scalafx-cancel-import-")
    val source = new DocumentStore(sourcePath)
    (1 to 8).foreach(id => source.create(Document("doc-" + id, "Document " + id, ""), () => false))
    val target = new MemoryStore
    val writes = new AtomicInteger
    val third = new CountDownLatch(1)
    target.createHook = _ =>
      if writes.incrementAndGet() == 3 then third.countDown()
    val model = fx(new PersistentWorkspace(target))
    try
      fx(model.reviewImport(sourcePath))
      eventually(fx(model.importPlan.value.nonEmpty))
      fx {
        assert(model.startImport())
        assert(third.await(10, TimeUnit.SECONDS))
        model.cancelImport()
        assert(!model.importing.value)
        assert(model.importHistory.last.cancelled)
        assertEquals(model.receipts.size, 0)
      }
      eventually(target.data.size() == 3)
      assert(fx(model.notice.value.contains("2 queued receipts abandoned")))
      assertEquals(target.data.size(), 3)
    finally fx(model.close())
  }

  test("native text controls refuse whole oversized edits and retain undo across reparenting") {
    val (model, draft) = opened(new MemoryStore)
    try fx {
      val editor = new EditorPane(model, draft)
      try
        editor.body.delegate.appendText(" changed")
        assertEquals(draft.body.value, "Initial changed")
        val left = new javafx.scene.layout.VBox(editor.node.delegate)
        left.getChildren.clear()
        val right = new javafx.scene.layout.VBox(editor.node.delegate)
        assert(editor.node.delegate.getParent eq right)
        editor.body.delegate.undo()
        assertEquals(draft.body.value, "Initial")
        editor.title.delegate.replaceText(0, editor.title.text.value.length, "x" * 257)
        assertEquals(draft.title.value, "First")
        editor.body.delegate.replaceText(0, editor.body.text.value.length, "x" * 16385)
        assertEquals(draft.body.value, "Initial")
      finally editor.close()
    }
    finally fx(model.close())
  }

  test("offscreen workbench layouts fit compact and normal windows") {
    val (model, _) = opened(new MemoryStore)
    try fx {
      val ui = new WorkbenchWindow(model, new scalafx.stage.Stage)
      try
        val root = ui.primary.scene().getRoot match
          case region: javafx.scene.layout.Region => region
          case _ => fail("Expected a resizable root")
        Vector((640, 400, false), (1000, 720, false), (640, 400, true)).foreach { (width, height, invalid) =>
          if invalid then model.selected.value.get.title.value = ""
          root.getScene.setRoot(new javafx.scene.Group)
          val scene = new javafx.scene.Scene(root, width, height)
          scene.getStylesheets.add(getClass.getResource("/workbench.css").toExternalForm)
          root.resize(width, height)
          root.applyCss()
          root.layout()
          val image = root.snapshot(null, new javafx.scene.image.WritableImage(width, height))
          assertEquals(root.getWidth, width.toDouble)
          assertEquals(root.getHeight, height.toDouble)
          val controls = root.lookupAll(".button").asScala ++ root.lookupAll(".text-input").asScala
          assert(controls.size >= 9, "toolbar, editor and collection controls must be present")
          controls.filter(_.isVisible).foreach { control =>
            val bounds = control.localToScene(control.getLayoutBounds)
            assert(bounds.getMinX >= -0.5 && bounds.getMaxX <= width + 0.5, "horizontal control bounds")
            assert(bounds.getMinY >= -0.5 && bounds.getMaxY <= height + 0.5, "vertical control bounds")
          }
          val pixels = new Array[Int](width * height)
          image.getPixelReader.getPixels(0, 0, width, height,
            javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width)
          assert(pixels.distinct.length > 32, "real controls must render")
          sys.props.get("comparison.artifacts").foreach { directory =>
            val target = java.nio.file.Path.of(directory)
            Files.createDirectories(target)
            val output = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            output.setRGB(0, 0, width, height, pixels, 0, width)
            val name = "workbench-" + width + (if invalid then "-invalid" else "") + ".png"
            javax.imageio.ImageIO.write(output, "png", target.resolve(name).toFile)
          }
        }
      finally ui.close()
    }
    finally fx(model.close())
  }
