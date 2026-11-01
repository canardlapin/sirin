package sirin.engine

import sirin.*
import munit.FunSuite

class InspectionSuite extends FunSuite:
  test("inspection preserves graph, evaluations and kernel identities without invoking user formatters") {
    val runtime = new HeadlessRuntime
    val scope = runtime.scope("editor")
    var evaluations = 0
    val source = scope.cell(3)
    val derived = source.map { value =>
      evaluations += 1
      value * 2
    }
    runtime.read(derived)
    val poison = new Object:
      override def toString: String = throw new AssertionError("application formatter invoked")
    val payload = scope.cell(poison)
    runtime.read(payload)
    val before = runtime.lifetimes
    val runs = evaluations
    val hidden = runtime.inspect()
    assert(hidden.nodes.rows.forall(_.preview.isEmpty))
    val shown = runtime.inspect(includeValues = true)
    assert(shown.nodes.rows.exists(_.preview.contains("6")))
    assert(shown.nodes.rows.exists(n => n.kind == "cell" && n.preview.isEmpty))
    assertEquals(runtime.lifetimes, before)
    assertEquals(evaluations, runs)
    assertEquals(runtime.inspect(), hidden)
    assertEquals(hidden.nodes.rows.map(_.id).distinct.size, hidden.nodes.rows.size)
    assert(hidden.edges.rows.nonEmpty)
    assert(hidden.edges.rows.forall(edge => hidden.nodes.rows.exists(_.id == edge.input)))
    val following = runtime.scope("next")
    val control = new HeadlessRuntime
    val controlScope = control.scope("editor")
    val controlSource = controlScope.cell(3)
    control.read(controlSource.map(_ * 2))
    control.read(controlScope.cell(poison))
    assertEquals(following.id, control.scope("next").id)
    runtime.close()
    control.close()
    assert(shown.text.contains("Dependencies of selected signals"))
  }

  test("committed revisions and change causes survive rollback and follow dependency changes") {
    val runtime = new HeadlessRuntime
    val scope = runtime.scope("editor")
    val field = scope.cell(1)
    val derived = field.map(_ + 1)
    runtime.read(derived)
    runtime.transact { field.set(7) }
    val committed = runtime.inspect(includeValues = true)
    val changed = committed.nodes.rows.find(_.preview.contains("8")).get
    val cell = committed.nodes.rows.find(_.preview.contains("7")).get
    assertEquals(cell.revision, Some(1L))
    assertEquals(changed.changedBy, cell.changedBy)
    assert(cell.changedBy.nonEmpty)
    assert(committed.traces.rows.exists(t => cell.changedBy.contains(t.id) && t.outcome == TraceOutcome.Committed))
    assertEquals(runtime.transact {
      field.set(99)
      UiTxn.refuse("invalid edit")
    }, CommitResult.Refused("invalid edit"))
    val refused = runtime.inspect(includeValues = true)
    assertEquals(refused.nodes.rows, committed.nodes.rows)
    assertEquals(refused.failures.rows.last.detail, "invalid edit")
    assertEquals(refused.failures.rows.last.outcome, TraceOutcome.Refused)
    runtime.close()
  }

  test("command queue refusal and work state can be diagnosed without executing or collecting work") {
    val runtime = new HeadlessRuntime
    val scope = runtime.scope("workspace")
    var ran = 0
    val work = Work {
      ran += 1
      42
    }
    val save = scope.command[Unit, Unit, Int]("document.save", "Save", WorkPolicy.save(Capacity(1)))(_ =>
      Job((), work))((_, _) => ())
    runtime.transact { save.dispatch(()) }
    runtime.transact { save.dispatch(()) }
    assertEquals(runtime.transact { save.dispatch(()) },
      CommitResult.Committed(Dispatched.Refused("command queue full")))
    val snapshot = runtime.inspect()
    val command = snapshot.commands.rows.find(_.name == "document.save").get
    assertEquals(command.running, Some(2))
    assertEquals(command.refusal, Some("command queue full"))
    assertEquals(snapshot.work.rows.map(_.ready), Vector(true, false))
    assert(snapshot.work.rows.forall(!_.started))
    assertEquals(ran, 0)
    assertEquals(runtime.inspect(), snapshot)
    assert(snapshot.text.contains("command queue full"))
    runtime.scheduler.run(snapshot.work.rows.head.id)
    assertEquals(runtime.inspect().work.rows.map(_.completed), Vector(true, false))
    assertEquals(ran, 1)
    runtime.close()
    assertEquals(snapshot.commands.rows.head.running, Some(2))
  }

  test("worker result inspection does not collect completion or deliver application changes") {
    val runtime = new HeadlessRuntime
    val scope = runtime.scope("worker owner")
    val received = scope.cell(0)
    val work = Work(42)
    val command = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ =>
      Job((), work))((_, _) => received.set(42))
    val pool = runtime.scheduler.startWorkers(1)
    try
      runtime.transact { command.dispatch(()) }
      val deadline = System.nanoTime() + 5000000000L
      var snapshot = runtime.inspect()
      while snapshot.workers.forall(_.completed == 0) && System.nanoTime() < deadline do
        Thread.sleep(1)
        snapshot = runtime.inspect()
      assertEquals(snapshot.workers.map(_.completed), Some(1))
      assertEquals(snapshot.work.rows.map(_.completed), Vector(false))
      assertEquals(runtime.inspect().workers.map(_.completed), Some(1))
      assertEquals(runtime.read(received), Value.Ok(0))
      runtime.drive(64)
      assertEquals(runtime.read(received), Value.Ok(42))
      assertEquals(runtime.inspect().work.total, 0)
    finally
      runtime.close()
      assert(pool.awaitStopped(5000))
  }

  test("output budgets and strings are bounded and retained snapshots contain no application objects") {
    val runtime = new HeadlessRuntime(traceCapacity = 3)
    val root = runtime.scope("root" * 100)
    (1 to 20).foreach { i =>
      val scope = root.child("child" * 100)
      runtime.read(scope.cell(i).map(_ + 100))
      scope.action[Unit]("action" * 100, "label" * 100)(_ => ())
      runtime.transact { UiTxn.refuse("reason" * 100) }
    }
    val limits = InspectionLimits(scopes = 2, nodes = 5, edges = 1, commands = 2, work = 0, traces = 2, text = 7)
    val snapshot = runtime.inspect(limits, includeValues = true)
    assertEquals(snapshot.scopes.rows.size, 2)
    assertEquals(snapshot.nodes.rows.size, 5)
    assert(snapshot.edges.rows.size <= 1)
    assertEquals(snapshot.commands.rows.size, 2)
    assertEquals(snapshot.traces.rows.size, 2)
    assertEquals(snapshot.failures.rows.size, 2)
    assertEquals(snapshot.failures.total, 3)
    assert(snapshot.scopes.omitted > 0 && snapshot.nodes.omitted > 0 && snapshot.commands.omitted > 0)
    assert(snapshot.scopes.rows.forall(_.name.length <= 7))
    assert(snapshot.failures.rows.forall(_.detail.length <= 7))
    assertEquals(InspectionText.clip("a😀b", 2), "a")
    assertEquals(runtime.inspect(InspectionLimits(0, 0, 0, 0, 0, 0, 0)).nodes.rows, Vector.empty)
    runtime.close()
    def check(value: Any): Unit = value match
      case null | _: String | _: Int | _: Long | _: Boolean => ()
      case _: CauseKind | _: TraceOutcome => ()
      case values: Iterable[?] => values.foreach(check)
      case value: Product => value.productIterator.foreach(check)
      case other => fail("snapshot retained unexpected type " + other.getClass.getName)
    check(snapshot)
    assert(snapshot.text.contains("omitted="))
    intercept[IllegalArgumentException](InspectionLimits(nodes = 8193))
  }

  test("speculative registrations roll back and committed retirement releases command metadata") {
    val runtime = new HeadlessRuntime
    val scope = runtime.scope("workspace")
    val baseline = runtime.inspect()
    runtime.transact {
      scope.action[Unit]("rolled-back", "Temporary")(_ => ())
      UiTxn.refuse("cancel construction")
    }
    assertEquals(runtime.inspect().commands, baseline.commands)
    assertEquals(runtime.inspect().nodes, baseline.nodes)
    scope.action[Unit]("live", "Live")(_ => ())
    val snapshot = runtime.inspect()
    assertEquals(snapshot.commands.total, 1)
    runtime.transact { scope.dispose() }
    val after = runtime.inspect()
    assertEquals(after.commands.total, 0)
    assertEquals(after.scopes.total, 0)
    assertEquals(after.nodes.total, 0)
    runtime.close()
    assertEquals(snapshot.commands.total, 1)
    intercept[KernelError](runtime.inspect())
  }

  test("inspection refuses speculative and callback access") {
    val runtime = new HeadlessRuntime
    assertEquals(runtime.transact { runtime.inspect() }, CommitResult.Failed(KernelError.ReadOnlyPhase))
    val scope = runtime.scope("editor")
    val source = scope.cell(0)
    val broken = source.map(_ => runtime.inspect().nodes.total)
    assertEquals(runtime.read(broken), Value.Failed(KernelError.ReadOnlyPhase))
    runtime.close()
  }
