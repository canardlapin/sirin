package sirin.engine

import sirin.*
import scala.collection.mutable

object WindowLifecycleSuite:
  final case class Item(id: Int, title: String)
  final case class Local(scope: Scope, input: Inputs[Item], draft: Cell[String])

class WindowLifecycleSuite extends munit.FunSuite:
  import WindowLifecycleSuite.*

  private def value[A](runtime: HeadlessRuntime, signal: Signal[A]): A =
    runtime.transact { signal.get } match
      case CommitResult.Committed(v) => v
      case other => fail(s"read did not commit: $other")
  private def frames(snapshot: MountedSnapshot): Vector[MountedSnapshot.Window] = snapshot match
    case frame: MountedSnapshot.Window => Vector(frame)
    case MountedSnapshot.Component(_, _, child) => frames(child)
    case MountedSnapshot.CustomLayout(_, children) => children.flatMap((_, child) => frames(child))
    case MountedSnapshot.Rows(rows) => rows.flatMap((_, child) => frames(child))
    case _ => Vector.empty
  private def windows(runtime: HeadlessRuntime, mounted: MountedView): Vector[MountedSnapshot.Window] =
    frames(value(runtime, mounted.snapshot))
  private def controls(snapshot: MountedSnapshot): Vector[Long] = snapshot match
    case MountedSnapshot.Control(instance, _) => Vector(instance)
    case MountedSnapshot.CustomLayout(_, children) => children.flatMap((_, child) => controls(child))
    case MountedSnapshot.Component(_, _, child) => controls(child)
    case _ => Vector.empty

  test("public keyed windows build once and retain live inputs, drafts and control identity through replacement/reorder") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "one"), Item(2, "two")))(_.id)
      val locals = mutable.ArrayBuffer.empty[Local]
      val root = r.mountWindows(app, Windows(source) { (scope, input) =>
        val draft = scope.cell("")
        locals += Local(scope, input, draft)
        Window(input.map(_.title), MenuBar.empty)(Column(Text(input.map(_.title)), TextField.bind(draft)))
      })
      val before = windows(r, root)
      assertEquals(before.map(_.title), Vector(Value.Ok("one"), Value.Ok("two")))
      assertEquals(r.transact {
        locals.head.draft.set("unfinished")
        source.replace(Item(1, "ONE"))
        source.move(2, 0)
      }, CommitResult.Committed(()))
      val after = windows(r, root)
      assertEquals(locals.size, 2)
      assertEquals(after.map(_.instance), before.reverse.map(_.instance))
      assertEquals(after.map(_.title), Vector(Value.Ok("two"), Value.Ok("ONE")))
      assertEquals(after.map(f => controls(f.child)), before.reverse.map(f => controls(f.child)))
      assertEquals(r.transact { (locals.head.input.get, locals.head.draft.get) },
        CommitResult.Committed((Item(1, "ONE"), "unfinished")))
    finally r.close()
  }

  test("default close retires only that window, leaving source, sibling and workspace save alive") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val document = app.child("document")
      val text = document.cell("shared")
      val saved = document.cell("")
      val work = Work { "written" }
      val save = document.command[Unit, String, String]("save", "Save", WorkPolicy.save(Capacity(1)))(
        _ => Job(text.get, work)
      )((snapshot, result) => if result.outcome == Outcome.Succeeded("written") then saved.set(snapshot))
      val source = app.keyed(Seq(Item(1, "one"), Item(2, "two")))(_.id)
      val drafts = mutable.ArrayBuffer.empty[Cell[String]]
      var released = 0
      val root = r.mountWindows(app, Windows(source) { (scope, input) =>
        val draft = scope.cell("")
        drafts += draft
        scope.onDispose(() => released += 1)
        Window(input.map(_.title), MenuBar.empty)(TextField.bind(draft))
      })
      r.transact { save.dispatch(()) }
      val ticket = r.scheduler.tickets.head.id
      val first = windows(r, root).head.instance
      assertEquals(r.transact { root.requestWindowClose(first) }, CommitResult.Committed(()))
      assertEquals(windows(r, root).map(_.title), Vector(Value.Ok("two")))
      assertEquals(released, 1)
      assertEquals(r.transact { source.size }, CommitResult.Committed(2))
      assertEquals(r.transact { drafts.head.set("stale") }, CommitResult.Failed(KernelError.DisposedScope))
      assertEquals(r.transact { drafts(1).set("still editing"); text.set("next") }, CommitResult.Committed(()))
      assert(!r.scheduler.tickets.head.cancellationRequested)
      r.scheduler.run(ticket)
      assertEquals(r.scheduler.deliver(ticket), CommitResult.Committed(()))
      assertEquals(r.transact { (saved.get, text.get) }, CommitResult.Committed(("shared", "next")))
    finally r.close()
  }

  test("close handler refusal, defect and enclosing attempt roll back dismissal and all writes") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val mode = app.cell(0)
      val counter = app.cell(0)
      val root = r.mountWindows(app, Windows.single("main") { _ =>
        Window("Main", MenuBar.empty)(View.empty).onClose {
          counter.update(_ + 1)
          if mode.get == 0 then UiTxn.refuse("dirty")
          if mode.get == 1 then throw new IllegalStateException("close defect")
        }
      })
      val first = windows(r, root).head.instance
      assertEquals(r.transact { root.requestWindowClose(first) }, CommitResult.Refused("dirty"))
      assertEquals(r.transact { counter.get }, CommitResult.Committed(0))
      r.transact { mode.set(1) }
      assert(r.transact { root.requestWindowClose(first) }.isInstanceOf[CommitResult.Failed[?]])
      assertEquals(r.transact { counter.get }, CommitResult.Committed(0))
      assertEquals(windows(r, root).map(_.instance), Vector(first))
      r.transact { mode.set(2) }
      assertEquals(r.transact {
        UiTxn.attempt { root.requestWindowClose(first); UiTxn.refuse("cancel outer attempt") }
      }, CommitResult.Committed(Attempt.Refused("cancel outer attempt")))
      assertEquals(windows(r, root).map(_.instance), Vector(first))
      assertEquals(r.transact { counter.get }, CommitResult.Committed(0))
      assertEquals(r.transact { root.requestWindowClose(first) }, CommitResult.Committed(()))
      assert(windows(r, root).isEmpty)
      assertEquals(r.transact { counter.get }, CommitResult.Committed(1))
      assertEquals(r.transact { root.requestWindowClose(first) }, CommitResult.Refused("window is no longer mounted"))
    finally r.close()
  }

  test("KeepOpen commits dirty-close prompt state and preserves drafts until explicit source removal") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "document")))(_.id)
      val prompt = app.cell(false)
      var draft: Cell[String] = null
      var closed = 0
      val root = r.mountWindows(app, Windows(source) { (scope, input) =>
        draft = scope.cell("unfinished")
        scope.onDispose(() => closed += 1)
        Window(input.map(_.title), MenuBar.empty)(TextField.bind(draft)).onCloseRequest {
          prompt.set(true)
          CloseDecision.KeepOpen
        }
      })
      val original = windows(r, root).head.instance
      assertEquals(r.transact { root.requestWindowClose(original) }, CommitResult.Committed(()))
      assertEquals(r.transact { (prompt.get, draft.get) }, CommitResult.Committed((true, "unfinished")))
      assertEquals(windows(r, root).map(_.instance), Vector(original))
      assertEquals(closed, 0)
      assertEquals(r.transact { prompt.set(false); source.remove(1) }, CommitResult.Committed(()))
      assert(windows(r, root).isEmpty)
      assertEquals(closed, 1)
    finally r.close()
  }

  test("source removal inside onClose disposes the window without reading retired Inputs") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "one")))(_.id)
      val root = r.mountWindows(app, Windows(source) { (_, input) =>
        Window(input.map(_.title), MenuBar.empty)(View.empty).onClose(source.remove(input.get.id))
      })
      val id = windows(r, root).head.instance
      assertEquals(r.transact { root.requestWindowClose(id) }, CommitResult.Committed(()))
      assert(windows(r, root).isEmpty)
      assertEquals(r.transact { source.size }, CommitResult.Committed(0))
      r.drainEvents()
      assertEquals(r.diagnostics, Vector.empty)
    finally r.close()
  }

  test("dismissed keys stay hidden through replacement; remove/reinsert reopens a fresh instance and rejects old close facts") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "one")))(_.id)
      var builds = 0
      val root = r.mountWindows(app, Windows(source) { (_, input) =>
        builds += 1
        Window(input.map(_.title), MenuBar.empty)(View.empty)
      })
      val old = windows(r, root).head.instance
      r.transact { root.requestWindowClose(old) }
      r.transact { source.replace(Item(1, "replacement")) }
      assert(windows(r, root).isEmpty)
      r.transact { source.remove(1) }
      r.transact { source.append(Item(1, "reopened")) }
      r.drainEvents()
      val fresh = windows(r, root).head
      assertEquals(fresh.title, Value.Ok("reopened"))
      assert(fresh.instance != old)
      assertEquals(builds, 2)
      assertEquals(r.transact { root.requestWindowClose(old) }, CommitResult.Refused("window is no longer mounted"))
      assertEquals(windows(r, root).map(_.instance), Vector(fresh.instance))
    finally r.close()
  }

  test("delayed removal events predating dismissal cannot reopen a newer closed window") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "old")))(_.id)
      val root = r.mountWindows(app, Windows(source)((_, input) => Window(input.map(_.title), MenuBar.empty)(View.empty)))
      r.transact { source.remove(1) }
      r.transact { source.append(Item(1, "new")) }
      val id = windows(r, root).head.instance
      r.transact { root.requestWindowClose(id) }
      r.drainEvents()
      assert(windows(r, root).isEmpty)
    finally r.close()
  }

  test("title failure stays explicit and recovery preserves window and content lifetimes") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val divisor = app.cell(1)
      val root = r.mountWindows(app, Windows.single("main") { scope =>
        Window(divisor.map(n => (10 / n).toString), MenuBar.empty)(TextField.bind(scope.cell("draft")))
      })
      val initial = windows(r, root).head
      r.transact { divisor.set(0) }
      val failed = windows(r, root).head
      assert(failed.title.isInstanceOf[Value.Failed[?]])
      assertEquals(failed.instance, initial.instance)
      assertEquals(controls(failed.child), controls(initial.child))
      r.transact { divisor.set(2) }
      val recovered = windows(r, root).head
      assertEquals(recovered.title, Value.Ok("5"))
      assertEquals(recovered.instance, initial.instance)
      assertEquals(controls(recovered.child), controls(initial.child))
    finally r.close()
  }

  test("window projection preserves the declared equality including signed zero") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(0.0))(_ => 1)
      val root = r.mountWindows(app, Windows(source) { (_, input) =>
        Window(input.map(n => (1.0 / n).toString), MenuBar.empty)(View.empty)
      })
      val id = windows(r, root).head.instance
      assertEquals(windows(r, root).head.title, Value.Ok("Infinity"))
      r.transact { source.replace(-0.0) }
      assertEquals(windows(r, root).head.title, Value.Ok("-Infinity"))
      assertEquals(windows(r, root).head.instance, id)
    finally r.close()
  }

  test("failed window construction rolls back scopes and registry; a successor can mount and close") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "one"), Item(2, "two")))(_.id)
      var builds = 0
      val before = r.lifetimes
      assert(r.transact {
        r.mountWindows(app, Windows(source) { (_, input) =>
          builds += 1
          if builds == 2 then throw new IllegalStateException("second builder failed")
          Window(input.map(_.title), MenuBar.empty)(View.empty)
        })
      }.isInstanceOf[CommitResult.Failed[?]])
      assertEquals(r.lifetimes, before)
      val root = r.mountWindows(app, Windows.single("successor")(_ => Window("Successor", MenuBar.empty)(View.empty)))
      val id = windows(r, root).head.instance
      assertEquals(r.transact { root.requestWindowClose(id) }, CommitResult.Committed(()))
      assert(windows(r, root).isEmpty)
    finally r.close()
  }

  test("a structural failure after close staging restores dismissal, source and close registry") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "one")))(_.id)
      var builds = 0
      var rejectBuild = true
      val root = r.mountWindows(app, Windows(source) { (_, input) =>
        builds += 1
        if builds > 1 && rejectBuild then throw new IllegalStateException("new window rejected")
        Window(input.map(_.title), MenuBar.empty)(View.empty).onClose(source.append(Item(2, "two")))
      })
      val id = windows(r, root).head.instance
      val before = r.lifetimes
      assert(r.transact { root.requestWindowClose(id) }.isInstanceOf[CommitResult.Failed[?]])
      assertEquals(windows(r, root).map(_.instance), Vector(id))
      assertEquals(r.transact { source.size }, CommitResult.Committed(1))
      assertEquals(r.lifetimes, before)
      rejectBuild = false
      assertEquals(r.transact { root.requestWindowClose(id) }, CommitResult.Committed(()))
      assertEquals(windows(r, root).map(_.title), Vector(Value.Ok("two")))
      assertEquals(r.transact { source.size }, CommitResult.Committed(2))
    finally r.close()
  }

  test("a close fact for another window set cannot dispatch by coincident source key") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val first = r.mountWindows(app, Windows.single("same")(_ => Window("First", MenuBar.empty)(View.empty)))
      val second = r.mountWindows(app, Windows.single("same")(_ => Window("Second", MenuBar.empty)(View.empty)))
      val id = windows(r, first).head.instance
      assertEquals(r.transact { second.requestWindowClose(id) }, CommitResult.Refused("window is no longer mounted"))
      assertEquals(windows(r, first).size, 1)
      assertEquals(windows(r, second).size, 1)
    finally r.close()
  }

  test("onClose can dispose the application owner without a stale default-dismissal write") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      var released = 0
      val root = r.mountWindows(app, Windows.single("main") { scope =>
        scope.onDispose(() => released += 1)
        Window("Main", MenuBar.empty)(View.empty).onClose(app.dispose())
      })
      val instance = windows(r, root).head.instance
      assertEquals(r.transact { root.requestWindowClose(instance) }, CommitResult.Committed(()))
      assertEquals(released, 1)
      assert(r.lifetimes.scopes.isEmpty)
      assert(r.lifetimes.cells.isEmpty)
    finally r.close()
  }

  test("repeated window churn and root disposal release all presentation ownership") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val source = app.keyed(Seq(Item(1, "one")))(_.id)
      val before = r.lifetimes
      val root = r.mountWindows(app, Windows(source) { (scope, input) =>
        Window(input.map(_.title), MenuBar.empty)(TextField.bind(scope.cell("")))
      })
      val bounded = r.lifetimes
      (2 to 40).foreach { n =>
        val instance = windows(r, root).head.instance
        r.transact { root.requestWindowClose(instance) }
        r.transact { source.setAll(Seq(Item(n, n.toString))) }
        r.drainEvents()
        assertEquals(r.lifetimes.scopes.size, bounded.scopes.size)
        assertEquals(r.lifetimes.cells.size, bounded.cells.size)
        assertEquals(r.lifetimes.nodes, bounded.nodes)
      }
      r.transact { root.dispose() }
      r.drainEvents()
      assertEquals(r.lifetimes, before)
    finally r.close()
  }

  test("application quit guard remains transactional with zero windows and retires with its root") {
    val r=new HeadlessRuntime
    try
      val app=r.scope("application");val source=app.keyed(Seq.empty[Int])(identity)
      val prompt=app.cell(false);val allow=app.cell(false)
      val root=r.mountWindows(app,Windows(source)((_,_) => Window("unused",MenuBar.empty)(View.empty)).onQuitRequest {
        prompt.set(true)
        if allow.get then CloseDecision.Allow else CloseDecision.KeepOpen
      })
      assertEquals(r.transact {root.requestApplicationQuit()},CommitResult.Committed(CloseDecision.KeepOpen))
      assertEquals(r.transact {prompt.get},CommitResult.Committed(true))
      assertEquals(r.transact {prompt.set(false);allow.set(true);root.requestApplicationQuit();UiTxn.refuse("rollback")},CommitResult.Refused("rollback"))
      assertEquals(r.transact {allow.get},CommitResult.Committed(false))
      assertEquals(r.transact {allow.set(true);root.requestApplicationQuit()},CommitResult.Committed(CloseDecision.Allow))
      r.transact {root.dispose()}
      assert(r.transact {root.requestApplicationQuit()}.isInstanceOf[CommitResult.Failed[?]])
    finally r.close()
  }
