package sirin.tooling

import munit.FunSuite
import sirin.*
import sirin.engine.*

class DeveloperInspectorSuite extends FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def value[A](runtime: HeadlessRuntime, signal: Signal[A]): A = runtime.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  private class Fixture extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val owner = runtime.scope("application")
    val field = owner.cell("")
    val save = owner.action[Unit]("document.save", "Save", field.map(_.nonEmpty))(_ => ())
    val inspector = new DeveloperInspector(runtime)(owner)
    val application = Windows.single("main")(_ =>
      Window("Document", MenuBar.empty)(Column(TextField.bind(field), Button(save))))
      .onQuitRequest { CloseDecision.KeepOpen }
    val mounted = runtime.mountWindows(owner, inspector.attach(application))
    owner.observe(mounted.snapshot)(_ => ())
    def capture(): Unit =
      ok(runtime.transact { inspector.open.dispatch(()) })
      assert(inspector.drain(Vector.empty))
    def snapshot: InspectorSnapshot = value(runtime, inspector.data).get
    def close(): Unit = runtime.close()

  test("tool capture coalesces after publication, respects rollback and preserves the application quit handler") {
    val f = new Fixture
    try
      assertEquals(f.runtime.transact {
        f.inspector.open.dispatch(())
        UiTxn.refuse("cancel")
      },
        CommitResult.Refused("cancel"))
      var captures = 0
      assert(!f.inspector.drain {
        captures += 1
        Vector.empty
      })
      assertEquals(captures, 0)
      f.capture()
      val first = f.snapshot.serial
      ok(f.runtime.transact {
        f.inspector.refresh.dispatch(())
        f.inspector.refresh.dispatch(())
      })
      assert(f.inspector.drain(Vector.empty))
      assertEquals(f.snapshot.serial, first + 1)
      assert(!f.inspector.drain(Vector.empty))
      assertEquals(ok(f.runtime.transact { f.mounted.requestApplicationQuit() }), CloseDecision.KeepOpen)
      assertEquals(value(f.runtime, f.field), "")
    finally f.close()
  }

  test("search and dependency navigation diagnose a refusal and report missing captured records") {
    val f = new Fixture
    try
      ok(f.runtime.transact { f.save.dispatch(()) })
      f.capture()
      ok(f.runtime.transact { f.inspector.query.set("document.save") })
      val row = value(f.runtime, f.inspector.visibleRows).head
      assert(row.detail.contains("command disabled"))
      val link = row.links.find(_.section == "Signals").get
      ok(f.runtime.transact { f.inspector.navigate.dispatch(link) })
      assertEquals(value(f.runtime, f.inspector.section), "Signals")
      assertEquals(value(f.runtime, f.inspector.query), "")
      val detail = value(f.runtime, f.inspector.detail)
      assert(detail.contains("Value:") || detail.contains("outside this snapshot"))
      ok(f.runtime.transact { f.inspector.navigate.dispatch(InspectorLink("Signals", "missing", "Missing")) })
      assert(value(f.runtime, f.inspector.detail).contains("outside this snapshot"))
    finally f.close()
  }

  test("close cancels pending capture, releases the snapshot and permits a fresh keyed reopening") {
    val f = new Fixture
    try
      f.capture()
      val old = f.snapshot
      ok(f.runtime.transact {
        f.inspector.refresh.dispatch(())
        f.inspector.close.dispatch(())
      })
      assertEquals(value(f.runtime, f.inspector.data), None)
      assert(!f.inspector.drain(throw new AssertionError("closed tool requested frame data")))
      f.capture()
      assert(f.snapshot.serial > old.serial)
      assertEquals(old.serial, 1L)
      ok(f.runtime.transact { f.owner.dispose() })
      assert(!f.inspector.drain(Vector.empty))
    finally f.close()
  }

  test("native-style close removes only the inspector window and clears its retained sample") {
    val f = new Fixture
    def windows(snapshot: MountedSnapshot): Vector[(Long, Value[String])] = snapshot match
      case MountedSnapshot.Window(id, title, _, _, _) => Vector(id -> title)
      case MountedSnapshot.Component(_, _, child) => windows(child)
      case MountedSnapshot.CustomLayout(_, children) => children.flatMap((_, child) => windows(child))
      case MountedSnapshot.Rows(rows) => rows.flatMap((_, child) => windows(child))
      case MountedSnapshot.Styled(child, _) => windows(child)
      case MountedSnapshot.Referenced(_, child) => windows(child)
      case _ => Vector.empty
    try
      f.capture()
      val before = windows(value(f.runtime, f.mounted.snapshot))
      assertEquals(before.size, 2)
      val tool = before.find(_._2 == Value.Ok("Sirin Inspector")).get._1
      ok(f.runtime.transact { f.mounted.requestWindowClose(tool) })
      assertEquals(windows(value(f.runtime, f.mounted.snapshot)).map(_._2), Vector(Value.Ok("Document")))
      assertEquals(value(f.runtime, f.inspector.data), None)
      assert(!f.inspector.drain(Vector.empty))
    finally f.close()
  }

  test("paging clamps after filtering, capture budgets stop growing and scalar preview is explicit") {
    val f = new Fixture
    try
      (1 to 15).foreach(i => f.owner.action[Unit](s"example.$i", s"Example $i")(_ => ()))
      f.capture()
      assert(f.snapshot.runtime.nodes.rows.forall(_.preview.isEmpty))
      assertEquals(value(f.runtime, f.inspector.visibleRows).size, 5)
      ok(f.runtime.transact { f.inspector.next.dispatch(()) })
      assertEquals(value(f.runtime, f.inspector.currentPage), 1)
      ok(f.runtime.transact { f.inspector.query.set("example.15") })
      assertEquals(value(f.runtime, f.inspector.currentPage), 0)
      assertEquals(value(f.runtime, f.inspector.visibleRows).size, 1)
      (1 to 6).foreach(_ => ok(f.runtime.transact { f.inspector.more.dispatch(()) }))
      assertEquals(value(f.runtime, f.inspector.budget), 8192)
      ok(f.runtime.transact {
        f.inspector.includeValues.set(true)
        f.inspector.refresh.dispatch(())
      })
      assert(f.inspector.drain(Vector.empty))
      assert(f.snapshot.runtime.valuesIncluded)
      assert(f.snapshot.runtime.nodes.rows.size <= 8192)
    finally f.close()
  }

  test("repeated signal inputs produce unique navigation keys") {
    val f = new Fixture
    try
      f.runtime.read(f.field.zip(f.field))
      f.capture()
      val rows = InspectorData.rows(f.snapshot, "Signals")
      assert(rows.exists(_.links.exists(_.section == "Signals")))
      rows.foreach { row =>
        val keys = row.links.map(link => (link.section, link.key))
        assertEquals(keys.distinct, keys, s"duplicate navigation in ${row.key}")
      }
    finally f.close()
  }

  test("malformed scalar text is escaped before it enters the inspector's renderable rows") {
    val f = new Fixture
    try
      f.owner.cell(new String(Array(0xd800.toChar, 0.toChar)))
      ok(f.runtime.transact {
        f.inspector.includeValues.set(true)
        f.inspector.budget.set(8192)
      })
      f.capture()
      val expected = "\\" + "ud800" + "\\" + "u0000"
      val rows = InspectorData.rows(f.snapshot, "Signals")
      assert(rows.exists(_.detail.contains(expected)))
      assert(rows.forall(!_.detail.contains(0.toChar)))
    finally f.close()
  }

  test("frame rows distinguish requested, rendered and presented generations and copied cache state") {
    val f = new Fixture
    try
      ok(f.runtime.transact { f.inspector.open.dispatch(()) })
      val frame = InspectedFrame(3, 640, 400, 2000000, 1000000, 48, 0, 12, "retained GPU", "hits=7 misses=2")
      val window = InspectedWindow(11, "Document", Some(4), Some(2), false, 9, 2, 1, Some(frame), Vector.empty)
      assert(f.inspector.drain(Vector(window)))
      val rows = InspectorData.rows(f.snapshot, "Frames")
      assertEquals(rows.size, 1)
      assert(rows.head.detail.contains("Requested: Some(4) · presented: Some(2)"))
      assert(rows.head.detail.contains("Rendered: 3"))
      assert(rows.head.detail.contains("readback bytes: 0"))
      assert(rows.head.detail.contains("hits=7 misses=2"))
    finally f.close()
  }
