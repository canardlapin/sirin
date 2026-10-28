package sirin.specimen

import sirin.*
import sirin.engine.*
import RunWorkbench.*
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable
import scala.concurrent.duration.*

class WorkbenchSuite extends munit.FunSuite:
  test("public application runs on real workers with bounded import and saved snapshot") {
    assertEquals(trace(), Vector("search=Result: sirin", "imported=6 rows=8", "dirty=false channel=Drained"))
  }
  test("keyed row editors preserve drafts during refresh and reorder, then retire on removal") {
    val host = new HeadlessRuntime
    try
      val owner = host.scope("workbench")
      val model = new Workbench(owner, DemoServices)
      val editors = mutable.ArrayBuffer.empty[RowEditor]
      val row = Component[Record]("record") { (scope, input) =>
        editors += new RowEditor(scope, input, model)
        View.empty
      }
      host.mount(owner, ForEach(model.rows)(input => row(input)))
      val first = editors.head
      accepted(host.transact { first.begin; first.draft.set("My edit"); model.rows.setAll(Seq(Record(2, "SECOND"), Record(1, "FIRST"))) })
      assertEquals(editors.size, 2)
      assertEquals(read(host, first.draft), "My edit")
      assert(read(host, first.conflict))
      accepted(host.transact { first.draft.set("   "); first.commit })
      assert(read(host, first.editing))
      assertEquals(read(host, first.draft), "   ")
      assertEquals(read(host, first.error), Some("A title is required"))
      assertEquals(read(host, model.rename.lastRefusal), Some("A title is required"))
      assertEquals(read(host, model.rows.snapshot).last.title, "FIRST")
      accepted(host.transact { first.draft.set("My edit"); first.commit })
      assertEquals(read(host, model.rows.snapshot).last.title, "My edit")
      accepted(host.transact { model.rows.remove(1) })
      assertEquals(host.transact { first.begin }, CommitResult.Failed(KernelError.DisposedScope))
    finally host.close()
  }
  test("saving an older revision leaves later edits dirty") {
    val host = new HeadlessRuntime
    val pool = host.scheduler.startWorkers(2)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val savedText = new java.util.concurrent.atomic.AtomicReference[String]()
    val services = new Services:
      def search(query: String): Work[Vector[Record]] = DemoServices.search(query)
      def importRows(output: Progress[Record]): Work[Int] = DemoServices.importRows(output)
      def save(text: String): Work[Unit] = Work { entered.countDown(); release.await(); savedText.set(text) }
    try
      val model = new Workbench(host.scope("workbench"), services)
      accepted(host.transact { model.document.set("one") })
      accepted(host.transact { requireStarted(model.save.dispatch(())) })
      assert(entered.await(5, TimeUnit.SECONDS))
      accepted(host.transact { model.document.set("two") })
      release.countDown()
      settle(host)(read(host, model.save.running) == 0)
      assertEquals(savedText.get(), "one")
      assert(read(host, model.dirty))
    finally
      release.countDown()
      host.close()
      assert(pool.awaitStopped(5000))
  }
  test("cancel import abandons buffered records and wakes the blocked producer") {
    val host = new HeadlessRuntime
    val pool = host.scheduler.startWorkers(2)
    try
      val owner = host.scope("workbench")
      val model = new Workbench(owner, DemoServices)
      val session = model.importing(owner.child("import"))
      val invocation = accepted(host.transact { session.run.dispatch(()) }) match
        case Dispatched.Started(handle) => handle
        case other => fail(s"$other")
      val deadline = System.nanoTime() + 5000000000L
      while host.progressQueues.head.pending < 2 && System.nanoTime() < deadline do host.awaitActivity(100)
      assertEquals(host.progressQueues.head.pending, 2)
      accepted(host.transact { session.cancel(invocation) })
      settle(host)(read(host, session.run.running) == 0)
      assertEquals(read(host, session.channel.state), ChannelState.Cancelled(2))
      assertEquals(read(host, session.received), 0)
      assertEquals(read(host, model.rows.snapshot).size, 2)
    finally
      host.close()
      assert(pool.awaitStopped(5000))
  }

  test("obsolete uncooperative search cannot overwrite the stable current query") {
    val clock = new ManualClock
    val host = new HeadlessRuntime(clock = clock)
    val pool = host.scheduler.startWorkers(2)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val services = new Services:
      def save(text: String): Work[Unit] = DemoServices.save(text)
      def importRows(output: Progress[Record]): Work[Int] = DemoServices.importRows(output)
      def search(query: String): Work[Vector[Record]] = Work {
        if query == "old" then { entered.countDown(); release.await() }
        Vector(Record(1, query))
      }
    try
      val model = new Workbench(host.scope("workbench"), services)
      accepted(host.transact { model.query.set("old") })
      clock.advanceBy(150.millis)
      host.drive()
      assert(entered.await(5, TimeUnit.SECONDS))
      accepted(host.transact { model.query.set("new") })
      clock.advanceBy(150.millis)
      settle(host)(read(host, model.search).shown.exists(_.head.title == "new") && !read(host, model.search).pending)
      release.countDown()
      settle(host)(host.scheduler.tickets.isEmpty)
      assertEquals(read(host, model.search).shown.get.head.title, "new")
    finally
      release.countDown()
      host.close()
      assert(pool.awaitStopped(5000))
  }

  test("rapid typing launches only the settled query and disposal cancels its deadline") {
    val clock = new ManualClock
    val host = new HeadlessRuntime(clock = clock)
    val observed = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val services = new Services:
      def save(text: String): Work[Unit] = DemoServices.save(text)
      def importRows(output: Progress[Record]): Work[Int] = DemoServices.importRows(output)
      def search(query: String): Work[Vector[Record]] = Work { observed.add(query); Vector(Record(1, query)) }
    val pool = host.scheduler.startWorkers(2)
    try
      val owner = host.scope("typing")
      val model = new Workbench(owner, services)
      settle(host)(!read(host, model.search).pending)
      accepted(host.transact { model.query.set("s") })
      clock.advanceBy(50.millis)
      accepted(host.transact { model.query.set("si") })
      clock.advanceBy(50.millis)
      accepted(host.transact { model.query.set("sirin") })
      clock.advanceBy(149.millis)
      host.drive()
      assertEquals(observed.size(), 1)
      clock.advanceBy(1.millis)
      settle(host)(read(host, model.search).shown.exists(_.head.title == "sirin"))
      assertEquals(observed.size(), 2)
      accepted(host.transact { model.query.set("discard"); owner.dispose() })
      clock.advanceBy(150.millis)
      host.drive()
      assertEquals(observed.size(), 2)
      assertEquals(host.driverSnapshot.pendingDeadlines, 0)
    finally
      host.close()
      assert(pool.awaitStopped(5000))
  }
