package sirin.engine

import sirin.*
import munit.FunSuite
import scala.collection.mutable

class WorkCommandSuite extends FunSuite:
  private def value[A](r: HeadlessRuntime, s: Signal[A]): A = r.read(s) match
    case Value.Ok(a) => a
    case Value.Failed(error) => throw error
  private def started[A](result: CommitResult[Dispatched[A]]): Invocation[A] = result match
    case CommitResult.Committed(Dispatched.Started(handle)) => handle
    case other => fail(s"expected started, got $other")
  private def finish(r: HeadlessRuntime, id: Long): CommitResult[Unit] = { r.scheduler.run(id); r.scheduler.deliver(id) }

  test("synchronous dispatch uses an implicit attempt and publishes terminal handles with outer commit") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val field = scope.cell(0)
    val error = scope.cell(false)
    val action = scope.action[Int]("edit", "Edit") { n => field.set(n); UiTxn.require(n >= 0, "negative") }
    assert(value(r, action.enabled))
    assertEquals(r.transact { error.set(true); action.dispatch(-1) }, CommitResult.Committed(Dispatched.Refused("negative")))
    assertEquals(value(r, field), 0)
    assert(value(r, error))
    assertEquals(value(r, action.lastRefusal), Some("negative"))
    val handle = started(r.transact { action.dispatch(3) })
    assertEquals(value(r, field), 3)
    assertEquals(value(r, handle.observe(scope)).map(_.outcome), Some(Outcome.Succeeded(())))
    assertEquals(value(r, action.lastRefusal), None)
    var refused: Invocation[Unit] = null
    assertEquals(r.transact { refused = action.dispatch(5).asInstanceOf[Dispatched.Started[Unit]].invocation; UiTxn.refuse("outer") }, CommitResult.Refused("outer"))
    intercept[KernelError](refused.observe(scope))
    assertEquals(value(r, field), 3)
    assert(r.scheduler.tickets.isEmpty)
  }

  test("failed enablement disables and reports; argument predicates and withArg recheck staged state") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val allow = scope.cell(true)
    val arg = scope.cell(1)
    val field = scope.cell(0)
    val action = scope.action[Int]("set", "Set", allow, Signal.const((n: Int) => n > 0))(n => field.set(n))
    val bound = action.withArg(arg)
    assert(value(r, bound.enabled))
    assertEquals(r.transact { arg.set(-1); bound.dispatch(()) }, CommitResult.Committed(Dispatched.Refused("command disabled")))
    assertEquals(r.transact { arg.set(2); allow.set(false); bound.dispatch(()) }, CommitResult.Committed(Dispatched.Refused("command disabled")))
    val broken = scope.action[Unit]("broken", "Broken", Signal.const(0).map(n => 1 / n > 0))(_ => field.set(9))
    assert(!value(r, broken.enabled))
    assertEquals(r.transact { broken.dispatch(()) }, CommitResult.Committed(Dispatched.Refused("command disabled")))
    assert(r.diagnostics.exists(_.isInstanceOf[ArithmeticException]))
    assertEquals(value(r, field), 0)
  }

  test("starts and reservations roll back on attempts and outer refusal; work never executes speculatively") {
    val r = new HeadlessRuntime(workCapacity = 1)
    val scope = r.scope("owner")
    var executions = 0
    val work = Work { executions += 1; 42 }
    val field = scope.cell(0)
    val cmd = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => { field.set(1); Job((), work) })((_, _) => ())
    assertEquals(r.transact { UiTxn.attempt { cmd.dispatch(()); UiTxn.refuse("attempt") }; () }, CommitResult.Committed(()))
    assertEquals(value(r, field), 0)
    assert(r.scheduler.tickets.isEmpty)
    r.transact { cmd.dispatch(()); UiTxn.refuse("root") }
    assert(r.scheduler.tickets.isEmpty)
    val handle = started(r.transact { cmd.dispatch(()) })
    val observed = handle.observe(scope)
    assertEquals(executions, 0)
    assertEquals(value(r, cmd.running), 1)
    assertEquals(value(r, observed), None)
    assertEquals(finish(r, r.scheduler.tickets.head.id), CommitResult.Committed(()))
    assertEquals(executions, 1)
    assertEquals(value(r, observed).map(_.outcome), Some(Outcome.Succeeded(42)))
    assertEquals(value(r, cmd.running), 0)
    assert(r.scheduler.tickets.isEmpty)
  }

  test("serial snapshots are captured before queuing and saving revision 17 leaves revision 18 dirty") {
    val r = new HeadlessRuntime
    val scope = r.scope("workspace")
    val text = scope.cell("zero")
    val saved = scope.cell(Revision.initial)
    val writes = mutable.ArrayBuffer.empty[String]
    def work(s: String): Work[Unit] = Work { writes += s; () }
    val save = scope.command[Unit, Versioned[String], Unit]("save", "Save", WorkPolicy.save(Capacity(1)))(_ => {
      val snapshot = text.snapshot
      Job(snapshot, work(snapshot.value))
    })((snapshot, result) => if result.outcome == Outcome.Succeeded(()) then saved.update(_.max(snapshot.revision)))
    (1 to 17).foreach(n => r.transact { text.set(n.toString) })
    started(r.transact { save.dispatch(()) })
    r.transact { text.set("18") }
    started(r.transact { save.dispatch(()) })
    assertEquals(r.transact { save.dispatch(()) }, CommitResult.Committed(Dispatched.Refused("command queue full")))
    val tickets = r.scheduler.tickets
    assertEquals(tickets.map(_.ready), Vector(true, false))
    intercept[IllegalArgumentException](r.scheduler.run(tickets(1).id))
    finish(r, tickets.head.id)
    assertEquals(value(r, saved).value, 17L)
    assertEquals(value(r, text.versioned).revision.value, 18L)
    assert(r.scheduler.tickets.head.ready)
    finish(r, tickets(1).id)
    assertEquals(value(r, saved).value, 18L)
    assertEquals(writes.toVector, Vector("17", "18"))
    assert(r.scheduler.tickets.isEmpty)
  }

  test("executing cancellation never releases a serial slot before noncooperative work terminates") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val applied = scope.cell(Vector.empty[Int])
    val work = Work(1) // deliberately ignores cancellation
    val cmd = scope.command[Int, Int, Int]("serial", "Serial", WorkPolicy.save(Capacity(2)))(n => Job(n, work))((n, _) => applied.update(_ :+ n))
    val first = started(r.transact { cmd.dispatch(1) })
    val second = started(r.transact { cmd.dispatch(2) })
    val third = started(r.transact { cmd.dispatch(3) })
    val tickets = r.scheduler.tickets
    r.transact { first.cancel(); second.cancel() }
    assertEquals(r.scheduler.tickets.map(_.ready), Vector(true, false, false))
    assert(r.scheduler.tickets(1).completed)
    // A queued cancellation can be delivered out of queue order without running work.
    r.scheduler.deliver(tickets(1).id)
    assert(!r.scheduler.tickets.last.ready)
    r.scheduler.run(tickets.head.id)
    assert(!r.scheduler.tickets.last.ready)
    r.scheduler.deliver(tickets.head.id)
    assert(r.scheduler.tickets.head.ready)
    finish(r, tickets(2).id)
    assertEquals(value(r, applied), Vector(3))
    assertEquals(value(r, third.observe(scope)).map(_.outcome), Some(Outcome.Succeeded(1)))
  }

  test("supersede cancellation is separate from acceptance and every invocation gets terminal data") {
    for acceptance <- Acceptance.values do
      val r = new HeadlessRuntime
      val scope = r.scope("owner")
      val applied = scope.cell(Vector.empty[(Int, Boolean)])
      val work = Work(9)
      val cmd = scope.command[Int, Int, Int]("run", "Run", WorkPolicy(Concurrency.Supersede, acceptance))(n => Job(n, work))((n, result) => applied.update(_ :+ (n -> result.current)))
      val first = started(r.transact { cmd.dispatch(1) })
      val old = first.observe(scope)
      started(r.transact { cmd.dispatch(2) })
      val tickets = r.scheduler.tickets
      assert(tickets.head.cancellationRequested)
      finish(r, tickets.head.id)
      assertEquals(value(r, old).map(_.current), Some(false))
      val expected = if acceptance == Acceptance.LatestOnly then Vector.empty else Vector(1 -> false)
      assertEquals(value(r, applied), expected)
      finish(r, tickets(1).id)
      assertEquals(value(r, applied), expected :+ (2 -> true))
      assertEquals(value(r, cmd.running), 0)
      r.close()
  }

  test("completion refusal and caught defects retire terminal resources and unblock serial work") {
    for defect <- Vector(false, true) do
      val r = new HeadlessRuntime
      val scope = r.scope("owner")
      val field = scope.cell(0)
      val work = Work(1)
      val cmd = scope.command[Int, Int, Int]("run", "Run", WorkPolicy.save(Capacity(1)))(n => Job(n, work))((n, _) => {
        field.set(n)
        if n == 1 then
          if defect then
            try field.update(_ => throw new IllegalStateException("complete"))
            catch case _: IllegalStateException => ()
          else UiTxn.refuse("complete")
      })
      val first = started(r.transact { cmd.dispatch(1) })
      val observed = first.observe(scope)
      started(r.transact { cmd.dispatch(2) })
      val tickets = r.scheduler.tickets
      val result = finish(r, tickets.head.id)
      assert(if defect then result.isInstanceOf[CommitResult.Failed[?]] else result == CommitResult.Refused("complete"))
      assertEquals(value(r, field), 0)
      assertEquals(value(r, observed).map(_.outcome), Some(Outcome.Succeeded(1)))
      assertEquals(value(r, cmd.running), 1)
      assertEquals(r.scheduler.tickets.map(_.id), Vector(tickets(1).id))
      assert(r.scheduler.tickets.head.ready)
      finish(r, tickets(1).id)
      assertEquals(value(r, field), 2)
      assert(r.scheduler.tickets.isEmpty)
      assert(r.diagnostics.nonEmpty)
      r.close()
  }

  test("worker guard blocks captured transactions, UI mutation and foreign runtimes") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val foreign = new HeadlessRuntime
    val field = foreign.cell(0)
    var captured: UiTxn = null
    val work = Work { field.set(9)(using captured); 1 }
    val cmd = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
    val handle = started(r.transact { captured = summon[UiTxn]; cmd.dispatch(()) })
    finish(r, r.scheduler.tickets.head.id)
    value(r, handle.observe(scope)).get.outcome match
      case Outcome.Failed(KernelError.ReadOnlyPhase) => ()
      case other => fail(s"unexpected $other")
    assertEquals(value(foreign, field), 0)
    foreign.close()
  }

  test("observing panel disposal leaves workspace jobs running; job owner disposal prevents delivery") {
    val r = new HeadlessRuntime
    val workspace = r.scope("workspace")
    val panel = workspace.child("panel")
    val field = workspace.cell(0)
    val work = Work(5)
    val cmd = workspace.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, result) => result.outcome match
      case Outcome.Succeeded(n) => field.set(n)
      case _ => ())
    val handle = started(r.transact { cmd.dispatch(()) })
    handle.observe(panel)
    r.transact { panel.dispose() }
    finish(r, r.scheduler.tickets.head.id)
    assertEquals(value(r, field), 5)
    assertEquals(value(r, handle.observe(workspace)).map(_.outcome), Some(Outcome.Succeeded(5)))
    val pending = started(r.transact { cmd.dispatch(()) })
    val id = r.scheduler.tickets.head.id
    r.scheduler.run(id)
    r.transact { workspace.dispose() }
    assert(r.scheduler.tickets.isEmpty)
    assertEquals(r.scheduler.deliver(id), CommitResult.Refused("retired work ticket"))
    val other = r.scope("other")
    intercept[KernelError](pending.observe(other))
  }

  test("latest Pending settles atomically, suppresses equal/change-restore inputs and rejects obsolete results") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val input = scope.cell(1)
    val state = scope.latest(input)(n => Work(n * 10))
    assertEquals(value(r, state), TaskState.Pending(1, None))
    val old = r.scheduler.tickets.head.id
    r.transact { input.set(2); input.set(1) }
    assertEquals(r.scheduler.tickets.map(_.id), Vector(old))
    r.transact { input.set(2) }
    val fresh = r.scheduler.tickets.last.id
    assertEquals(value(r, state), TaskState.Pending(2, None))
    finish(r, old)
    assertEquals(value(r, state), TaskState.Pending(2, None))
    finish(r, fresh)
    assertEquals(value(r, state), TaskState.Done(Completed(2, Outcome.Succeeded(20), true)))
  }

  test("Progressive latest keeps newer input pending and never replaces a newer result with an older generation") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val input = scope.cell(1)
    val state = scope.latest(input, LatestPolicy.Progressive)(n => Work(n))
    val first = r.scheduler.tickets.head.id
    r.transact { input.set(2) }
    val second = r.scheduler.tickets.last.id
    finish(r, first)
    assertEquals(value(r, state), TaskState.Pending(2, Some(Completed(1, Outcome.Succeeded(1), false))))
    r.transact { input.set(3) }
    val third = r.scheduler.tickets.last.id
    finish(r, third)
    finish(r, second)
    assertEquals(value(r, state), TaskState.Done(Completed(3, Outcome.Succeeded(3), true)))
  }

  test("latest source failure invalidates work and recovery mounts current generation; capacity waiting eventually starts") {
    val r = new HeadlessRuntime(workCapacity = 1)
    val scope = r.scope("owner")
    val input = scope.cell(1)
    val state = scope.latest(input.map(n => 10 / n))(n => Work(n))
    val first = r.scheduler.tickets.head.id
    r.transact { input.set(0) }
    assert(r.read(state).isInstanceOf[Value.Failed[?]])
    r.transact { input.set(2) }
    assertEquals(value(r, state), TaskState.Pending(5, None))
    assertEquals(r.scheduler.tickets.size, 1)
    finish(r, first)
    assertEquals(r.scheduler.tickets.size, 1)
    finish(r, r.scheduler.tickets.head.id)
    assertEquals(value(r, state), TaskState.Done(Completed(5, Outcome.Succeeded(5), true)))
  }

  test("cancellation and scope disposal requests roll back with refusal attempts") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val work = Work(summon[Cancellation].cancelled)
    val cmd = scope.command[Unit, Unit, Boolean]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
    val handle = started(r.transact { cmd.dispatch(()) })
    val id = r.scheduler.tickets.head.id
    r.transact { UiTxn.attempt { handle.cancel(); scope.dispose(); UiTxn.refuse("keep") }; () }
    assert(!r.scheduler.tickets.head.cancellationRequested)
    finish(r, id)
    assertEquals(value(r, handle.observe(scope)).map(_.outcome), Some(Outcome.Succeeded(false)))
  }

  test("bounded terminal resources retire through churn despite retained handles") {
    val r = new HeadlessRuntime(workCapacity = 2)
    val scope = r.scope("owner")
    val work = Work(1)
    val cmd = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
    val baseline = r.lifetimes
    val handles = (1 to 50).map { _ =>
      val handle = started(r.transact { cmd.dispatch(()) })
      val observer = scope.child("observer")
      handle.observe(observer)
      finish(r, r.scheduler.tickets.head.id)
      r.transact { observer.dispose() }
      assert(r.scheduler.tickets.isEmpty)
      handle
    }
    assertEquals(handles.size, 50)
    assertEquals(r.lifetimes.cells, baseline.cells)
    assertEquals(r.lifetimes.scopes, baseline.scopes)
    assertEquals(value(r, cmd.running), 0)
  }

  test("same-command reentrant dispatch refuses before start and cannot overfill admission") {
    for concurrency <- Vector(Concurrency.RefuseWhileRunning, Concurrency.Serial(Capacity(1))) do
      val r = new HeadlessRuntime
      val scope = r.scope("owner")
      val work = Work(1)
      var nested = Option.empty[Dispatched[Int]]
      var cmd: Command[Int, Int] = null
      cmd = scope.command[Int, Int, Int]("run", "Run", WorkPolicy(concurrency, Acceptance.EveryInvocation))(n => {
        if n == 1 then nested = Some(cmd.dispatch(2))
        Job(n, work)
      })((_, _) => ())
      started(r.transact { cmd.dispatch(1) })
      assertEquals(nested, Some(Dispatched.Refused("command dispatch already in progress")))
      assertEquals(value(r, cmd.running), 1)
      assertEquals(r.scheduler.tickets.size, 1)
      r.close()
  }

  test("synchronous terminal current describes its immediate execution point") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val action = scope.action[Unit]("act", "Act")(_ => ())
    val handles = r.transact { Vector(action.dispatch(()), action.dispatch(())) } match
      case CommitResult.Committed(xs) => xs.map(_.asInstanceOf[Dispatched.Started[Unit]].invocation)
      case other => fail(s"unexpected $other")
    assertEquals(handles.map(h => value(r, h.observe(scope)).get.current), Vector(true, true))
  }

  test("failed terminal bookkeeping retains completed work for delivery retry without rerunning it") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    var executions = 0
    val work = Work { executions += 1; 1 }
    val cmd = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
    var reject = false
    val bad = Component[Unit]("bad") { (_, _) => if reject then throw new IllegalStateException("mount"); View.empty }
    val mounted = r.mount(scope, Switch(cmd.running)(n => if n == 0 then bad.view else View.empty))
    started(r.transact { cmd.dispatch(()) })
    reject = true
    val id = r.scheduler.tickets.head.id
    r.scheduler.run(id)
    assert(r.scheduler.deliver(id).isInstanceOf[CommitResult.Failed[?]])
    assertEquals(value(r, cmd.running), 1)
    assert(r.scheduler.tickets.head.completed)
    reject = false
    assertEquals(r.scheduler.deliver(id), CommitResult.Committed(()))
    assertEquals(executions, 1)
    assert(r.scheduler.tickets.isEmpty)
    r.transact { mounted.dispose() }
  }

  test("latest launches only the final source-plus-Inputs diamond value after private structural passes") {
    val r = new HeadlessRuntime(workCapacity = 1)
    val scope = r.scope("owner")
    val source = scope.cell(1)
    var state: Signal[TaskState[Int, Int]] = null
    val component = Component[Int]("diamond") { (scope, in) =>
      state = scope.latest(in.zip(source).map((a, b) => a + b))(n => Work(n))
      View.empty
    }
    r.mount(scope, component(source))
    finish(r, r.scheduler.tickets.head.id)
    r.transact { source.set(2) }
    assertEquals(value(r, state), TaskState.Pending(4, Some(Completed(2, Outcome.Succeeded(2), false))))
    assertEquals(r.scheduler.tickets.size, 1)
    finish(r, r.scheduler.tickets.head.id)
    assertEquals(value(r, state), TaskState.Done(Completed(4, Outcome.Succeeded(4), true)))
  }

  test("unrequested worker interruption terminalizes once as failure") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    var executions = 0
    val work = Work { executions += 1; throw new InterruptedException("worker") }
    val cmd = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
    val handle = started(r.transact { cmd.dispatch(()) })
    val id = r.scheduler.tickets.head.id
    r.scheduler.run(id)
    intercept[IllegalArgumentException](r.scheduler.run(id))
    r.scheduler.deliver(id)
    assertEquals(executions, 1)
    assert(value(r, handle.observe(scope)).get.outcome.isInstanceOf[Outcome.Failed[?]])
  }

  test("structural intermediate change-and-restore neither launches nor cancels latest work") {
    val r = new HeadlessRuntime(workCapacity = 1)
    val scope = r.scope("owner")
    val source = scope.cell(1)
    var factories = 0
    var state: Signal[TaskState[Int, Int]] = null
    val component = Component[Int]("diamond") { (scope, in) =>
      state = scope.latest(in.zip(source).map((a, b) => a - b)) { n => factories += 1; Work(n) }
      View.empty
    }
    r.mount(scope, component(source))
    val first = r.scheduler.tickets.head
    r.transact { source.set(2) }
    assertEquals(factories, 1)
    assertEquals(r.scheduler.tickets, Vector(first))
    finish(r, first.id)
    val before = value(r, state)
    r.transact { source.set(3) }
    assertEquals(value(r, state), before)
    assertEquals(factories, 1)
    assert(r.scheduler.tickets.isEmpty)
  }

  test("command definitions and latest bindings can be declared in a mounted component construction scope") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    var action: Command[Unit, Unit] = null
    var state: Signal[TaskState[Int, Int]] = null
    val component = Component[Unit]("tasks") { (scope, _) =>
      val cell = scope.cell(1)
      action = scope.action[Unit]("add", "Add")(_ => cell.update(_ + 1))
      state = scope.latest(cell)(n => Work(n))
      View.empty
    }
    val mounted = r.mount(owner, component.view)
    started(r.transact { action.dispatch(()) })
    assertEquals(value(r, state), TaskState.Pending(2, None))
    r.transact { mounted.dispose() }
    assert(r.scheduler.tickets.isEmpty)
  }

  test("latest worker failure and cooperative cancellation preserve pending, shown and error metadata") {
    for policy <- LatestPolicy.values do
      val r = new HeadlessRuntime
      val scope = r.scope("owner")
      val input = scope.cell(1)
      val state = scope.latest(input, policy)(n => Work {
        if summon[Cancellation].cancelled then throw new InterruptedException("cancelled")
        if n == 2 then throw new IllegalStateException("worker failure")
        n
      })
      val old = r.scheduler.tickets.head.id
      r.transact { input.set(2) }
      finish(r, old)
      val pending = value(r, state)
      assert(pending.pending)
      assertEquals(pending.shown, None)
      assertEquals(pending.error, None)
      if policy == LatestPolicy.Progressive then pending match
        case TaskState.Pending(2, Some(Completed(1, Outcome.Cancelled, false))) => ()
        case other => fail(s"unexpected $other")
      finish(r, r.scheduler.tickets.head.id)
      val failed = value(r, state)
      assert(!failed.pending)
      assert(failed.error.exists(_.getMessage == "worker failure"))
      assertEquals(failed.shown, None)
      r.transact { input.set(3) }
      assert(value(r, state).pending)
      assert(value(r, state).stale)
      finish(r, r.scheduler.tickets.head.id)
      assertEquals(value(r, state).shown, Some(3))
      assertEquals(value(r, state).error, None)
      r.close()
  }
