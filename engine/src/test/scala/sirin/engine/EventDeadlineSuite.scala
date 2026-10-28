package sirin.engine

import sirin.*
import scala.concurrent.duration.*
import scala.collection.mutable
import java.util.concurrent.{CountDownLatch, TimeUnit}

class EventDeadlineSuite extends munit.FunSuite:
  private def read[A](r: HeadlessRuntime, signal: Signal[A]): A = r.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")

  test("event publication snapshots listeners, preserves occurrence order and handlers read current state") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("events")
      val source = owner.events[Int]
      val current = owner.cell(0)
      val seen = owner.cell(Vector.empty[(String, Int, Int)])
      owner.on(source)(n => seen.update(_ :+ (("a", n, current.get))))
      ok(r.transact {
        source.emit(1)
        owner.on(source.map(_ * 10).filter(_ >= 20))(n => seen.update(_ :+ (("b", n, current.get))))
        source.emit(2)
      })
      owner.on(source)(n => seen.update(_ :+ (("late", n, current.get))))
      ok(r.transact { current.set(9) })
      assertEquals(r.drainEvents().delivered, 4)
      assertEquals(read(r, seen), Vector(("a", 1, 9), ("a", 2, 9), ("b", 20, 9)))
    finally r.close()
  }
  test("emissions, registrations and provisional sources roll back with attempts and root poison") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("events")
      val source = owner.events[Int]
      val seen = owner.cell(Vector.empty[Int])
      owner.on(source)(n => seen.update(_ :+ n))
      var orphan: EventSource[Int] = null
      ok(r.transact {
        source.emit(1)
        UiTxn.attempt {
          orphan = owner.events[Int]
          owner.on(source)(n => seen.update(_ :+ (n * 100)))
          source.emit(2)
          UiTxn.refuse("attempt")
        }
        source.emit(3)
      })
      assertEquals(r.transact { source.emit(4); UiTxn.refuse("root") }, CommitResult.Refused("root"))
      assertEquals(r.transact { source.emit(5); try seen.update(_ => throw new IllegalStateException("defect")) catch case _: IllegalStateException => () }.isInstanceOf[CommitResult.Failed[?]], true)
      assertEquals(r.transact { orphan.emit(1) }, CommitResult.Failed(KernelError.DisposedScope))
      r.drainEvents()
      assertEquals(read(r, seen), Vector(1, 3))
    finally r.close()
  }
  test("fanout and staging overflow refuse before writes, disposal or work launch publish") {
    val r = new HeadlessRuntime(eventCapacity = 2)
    try
      val owner = r.scope("events")
      val child = owner.child("child")
      val source = owner.events[Int]
      val field = owner.cell(0)
      owner.on(source)(_ => ())
      child.on(source)(_ => ())
      val work = Work(1)
      val command = owner.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
      ok(r.transact { source.emit(1) })
      assertEquals(r.transact { field.set(1); command.dispatch(()); source.emit(2) }, CommitResult.Refused("event delivery capacity full"))
      assertEquals(read(r, field), 0)
      assert(r.scheduler.tickets.isEmpty)
      assertEquals(r.transact { source.emit(1); source.emit(2); source.emit(3) }, CommitResult.Refused("event staging capacity full"))
      assertEquals(r.drainEvents().delivered, 2)
      ok(r.transact { child.dispose(); source.emit(3) })
      assertEquals(r.drainEvents().delivered, 1)
    finally r.close()
  }
  test("committed source and listener disposal remove backlog; refused disposal restores it") {
    val r = new HeadlessRuntime
    try
      val sourceOwner = r.scope("source")
      val targetOwner = r.scope("target")
      val source = sourceOwner.events[Int]
      val seen = targetOwner.cell(Vector.empty[Int])
      targetOwner.on(source)(n => seen.update(_ :+ n))
      ok(r.transact { source.emit(1) })
      r.transact { sourceOwner.dispose(); UiTxn.refuse("keep") }
      assertEquals(r.drainEvents().delivered, 1)
      ok(r.transact { source.emit(2); sourceOwner.dispose() })
      assertEquals(r.driverSnapshot.pendingEvents, 0)
      assertEquals(read(r, seen), Vector(1))
      val other = targetOwner.events[Int]
      targetOwner.on(other)(_ => ())
      ok(r.transact { other.emit(1); targetOwner.dispose() })
      assertEquals(r.driverSnapshot.pendingEvents, 0)
    finally r.close()
  }
  test("map/filter failure and refused handlers are consumed once without starving other listeners") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("events")
      val source = owner.events[Int]
      val seen = owner.cell(Vector.empty[Int])
      owner.on(source.map[Int](_ => throw new IllegalStateException("map")))(_ => ())
      owner.on(source) { n => seen.update(_ :+ (n * 10)); UiTxn.refuse("handler") }
      owner.on(source)(n => seen.update(_ :+ n))
      ok(r.transact { source.emit(1) })
      val drain = r.drainEvents()
      assertEquals(drain.delivered, 3)
      assertEquals(drain.failures.size, 2)
      assertEquals(read(r, seen), Vector(1))
      assertEquals(r.drainEvents().delivered, 0)
    finally r.close()
  }
  test("event transformations are read-only and foreign events fail without mutation") {
    val a = new HeadlessRuntime
    val b = new HeadlessRuntime
    try
      val owner = a.scope("a")
      val source = owner.events[Int]
      val field = owner.cell(0)
      var capability: UiTxn = null
      val mapped = source.map { n =>
        try field.set(n)(using capability) catch case _: KernelError => ()
        n
      }
      owner.on(mapped)(field.set)
      ok(a.transact { capability = summon[UiTxn]; source.emit(1) })
      assertEquals(a.drainEvents().failures, Vector(CommitResult.Failed(KernelError.ReadOnlyPhase)))
      assertEquals(read(a, field), 0)
      val foreign = b.scope("b")
      assertEquals(intercept[KernelError](foreign.on(source)(_ => ())), KernelError.ForeignEvent)
    finally { a.close(); b.close() }
  }
  test("event feedback is bounded by driver budget and traced without inferring a cycle") {
    val r = new HeadlessRuntime(traceCapacity = 4)
    try
      val owner = r.scope("events")
      val source = owner.events[Int]
      val field = owner.cell(0)
      owner.on(source) { n => field.set(n); source.emit(n + 1) }
      ok(r.transact { source.emit(1) })
      val parent = r.traces.last.id
      val turn = r.drive(3)
      assertEquals(turn.executed, 3)
      assert(turn.budgetExceeded)
      assert(turn.failures.isEmpty)
      assertEquals(read(r, field), 3)
      val events = r.traces.filter(_.cause == CauseKind.Event)
      assertEquals(events.size, 3)
      assertEquals(events.head.parent, Some(parent))
      assert(events.sliding(2).forall(pair => pair(1).parent.contains(pair(0).id)))
      assert(events.forall(t => t.outcome == TraceOutcome.Committed && t.graphPasses > 0))
      assertEquals(r.traces.size, 4)
      assertEquals(r.traces.last.cause, CauseKind.Driver)
      assertEquals(r.traces.last.outcome, TraceOutcome.BudgetExceeded)
      assertEquals(r.traces.last.units, 3)
    finally r.close()
  }
  test("keyed changes describe final committed revisions and batches join event capacity preflight") {
    val r = new HeadlessRuntime(eventCapacity = 1)
    try
      val owner = r.scope("rows")
      val rows = owner.keyed(Seq(1 -> "one", 2 -> "two"))(_._1)
      val batches = owner.cell(Vector.empty[Changes[Int]])
      owner.on(rows.changes)(batch => batches.update(_ :+ batch))
      ok(r.transact { rows.setAll(Seq(2 -> "TWO", 3 -> "three")); rows.update(3)(_ => 3 -> "THREE") })
      assertEquals(r.transact { rows.remove(2) }, CommitResult.Refused("event delivery capacity full"))
      assertEquals(read(r, rows.revision), Revision(1))
      r.drainEvents()
      val batch = read(r, batches).head
      assertEquals(batch.from, Revision.initial)
      assertEquals(batch.to, Revision(1))
      assertEquals(batch.deltas.toVector, Vector(Delta.Removed(1, 0), Delta.Inserted(3, 1), Delta.Replaced(2)))
      ok(r.transact { val before = rows.snapshot.get; rows.remove(2); rows.setAll(before) })
      assertEquals(r.drainEvents().delivered, 0)
      val initialOwner = owner.child("new")
      ok(r.transact { val fresh = initialOwner.keyed(Seq(1))((n: Int) => n); initialOwner.on(fresh.changes)(_ => fail("initialization is not a change")); fresh.append(2) })
      assertEquals(r.drainEvents().delivered, 0)
    finally r.close()
  }
  test("delta replay reaches every six-key permutation with a minimum number of retained-key moves") {
    val before = (1 to 6).map(n => n -> s"$n").toVector
    before.permutations.foreach { after =>
      var keys = before.map(_._1)
      val deltas = CollectionDelta(before, after, Equality.universal[String])
      deltas.foreach {
        case Delta.Moved(key, from, to) =>
          assertEquals(keys(from), key)
          keys = keys.patch(from, Nil, 1).patch(to, Vector(key), 0)
        case other => fail(s"unexpected $other")
      }
      assertEquals(keys, after.map(_._1))
      val sequence = after.map(_._1)
      val lengths = Array.fill(sequence.size)(1)
      sequence.indices.foreach { i => (0 until i).foreach { j => if sequence(j) < sequence(i) then lengths(i) = lengths(i).max(lengths(j) + 1) } }
      assertEquals(deltas.size, 6 - lengths.max)
    }
  }
  test("debounce initializes immediately, replaces one deadline and rolls back speculative inputs") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("deadline")
      val input = owner.cell(0)
      val output = owner.debounce(input, 10.millis)
      assertEquals(read(r, output), 0)
      ok(r.transact { input.set(1) })
      clock.advanceBy(5.millis)
      r.transact { input.set(2); UiTxn.refuse("rollback") }
      ok(r.transact { input.set(2); input.set(1) })
      clock.advanceBy(4.millis)
      assertEquals(r.drive().executed, 0)
      clock.advanceBy(1.millis)
      assertEquals(r.drive().executed, 1)
      assertEquals(read(r, output), 1)
      ok(r.transact { input.set(2) })
      clock.advanceBy(5.millis)
      ok(r.transact { input.set(3) })
      clock.advanceBy(5.millis)
      assertEquals(r.drive().executed, 0)
      clock.advanceBy(5.millis)
      r.drive()
      assertEquals(read(r, output), 3)
      ok(r.transact { input.set(4) })
      ok(r.transact { input.set(3) })
      assertEquals(r.driverSnapshot.pendingDeadlines, 0)
    finally r.close()
  }
  test("failed input is data and follows the quiet period, with recovery and signed-zero equality") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("deadline")
      val input = owner.cell(2)
      val output = owner.debounce(input.map(10 / _), 1.millis)
      assertEquals(read(r, output), 5)
      ok(r.transact { input.set(0) })
      assertEquals(read(r, output), 5)
      clock.advanceBy(1.millis)
      r.drive()
      assert(r.read(output).isInstanceOf[Value.Failed[?]])
      ok(r.transact { input.set(1) })
      clock.advanceBy(1.millis)
      r.drive()
      assertEquals(read(r, output), 10)
      val zero = owner.cell(0.0)
      val debounced = owner.debounce(zero, 1.millis)
      ok(r.transact { zero.set(-0.0) })
      clock.advanceBy(1.millis)
      r.drive()
      assertEquals(1.0 / read(r, debounced), Double.NegativeInfinity)
    finally r.close()
  }
  test("deadline ties are stable, due-time input supersedes, and disposal/rollback retire deadlines") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("deadline")
      val input = owner.cell(0)
      val a = owner.debounce(input, 1.millis)
      val child = owner.child("child")
      val b = child.debounce(input, 1.millis)
      ok(r.transact { input.set(1) })
      clock.advanceBy(1.millis)
      assertEquals(r.drive(1).executed, 1)
      assertEquals(read(r, a), 1)
      assertEquals(read(r, b), 0)
      ok(r.transact { input.set(2) })
      assertEquals(r.drive().executed, 0)
      clock.advanceBy(1.millis)
      ok(r.transact { child.dispose() })
      r.drive()
      assertEquals(read(r, a), 2)
      val count = r.driverSnapshot.pendingDeadlines
      assertEquals(r.transact { owner.debounce(input, 1.millis); input.set(3); UiTxn.refuse("discard") }, CommitResult.Refused("discard"))
      assertEquals(r.driverSnapshot.pendingDeadlines, count)
      assertEquals(read(r, input), 2)
    finally r.close()
  }
  test("idle turns do not count as active and manual clock wakes a parked owner without running UI") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("deadline")
      val input = owner.cell(0)
      val output = owner.debounce(input, 1.second)
      (1 to 10).foreach(_ => assertEquals(r.drive().executed, 0))
      assertEquals(r.driverSnapshot.activeTurns, 0L)
      assert(!r.awaitActivity(0))
      ok(r.transact { input.set(1) })
      val done = new CountDownLatch(1)
      val thread = new Thread(() => { clock.advanceBy(1.second); done.countDown() })
      thread.start()
      assert(r.awaitActivity(5000))
      assert(done.await(5, TimeUnit.SECONDS))
      assertEquals(read(r, output), 0)
      r.drive()
      assertEquals(read(r, output), 1)
      assertEquals(r.driverSnapshot.activeTurns, 1L)
    finally r.close()
  }
  test("real-clock parking ends at a deadline without a background UI callback") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("deadline")
      val input = owner.cell(0)
      val output = owner.debounce(input, 5.millis)
      ok(r.transact { input.set(1) })
      assert(r.awaitActivity(5000))
      assertEquals(read(r, output), 0)
      r.drive()
      assertEquals(read(r, output), 1)
    finally r.close()
  }
  test("driver services effects, reports receiver failure once per turn and preserves fairness") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("driver")
      val input = owner.cell(0)
      val output = owner.debounce(input, 1.millis)
      val effects = mutable.ArrayBuffer.empty[Int]
      owner.effect(output)(effects += _)
      val source = owner.events[Int]
      owner.on(source)(_ => ())
      var attempts = 0
      val channel = owner.progress[Int](Delivery.Ordered, Capacity(1)) { _ => attempts += 1; UiTxn.refuse("receiver") }
      channel.producer.tryEmit(1)
      ok(r.transact { input.set(1); source.emit(1) })
      clock.advanceBy(1.millis)
      val result = r.drive(20)
      assertEquals(attempts, 1)
      assertEquals(result.failures, Vector(CommitResult.Refused("receiver")))
      assert(!result.budgetExceeded)
      assertEquals(effects.toVector, Vector(0, 1))
      assertEquals(read(r, output), 1)
    finally r.close()
  }

  test("failed completion bookkeeping is attempted once per turn and cannot starve another command") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("driver")
      val work = Work(1)
      val first = owner.command[Unit, Unit, Int]("first", "First", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
      val second = owner.command[Unit, Unit, Int]("second", "Second", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
      var reject = false
      val bad = Component[Unit]("bad") { (_, _) => if reject then throw new IllegalStateException("mount"); View.empty }
      r.mount(owner, Switch(first.running)(n => if n == 0 then bad.view else View.empty))
      ok(r.transact { first.dispatch(()); second.dispatch(()) })
      r.scheduler.tickets.foreach(t => r.scheduler.run(t.id))
      reject = true
      val turn = r.drive(20)
      assertEquals(turn.executed, 2)
      assertEquals(turn.failures.size, 1)
      assert(!turn.budgetExceeded)
      assertEquals(read(r, second.running), 0)
      assertEquals(read(r, first.running), 1)
      reject = false
      assertEquals(r.drive(20).executed, 1)
      assert(r.scheduler.tickets.isEmpty)
    finally r.close()
  }

  test("deadline disposal and provisional rollback clear pending input metadata") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("deadline")
      val input = owner.cell("initial")
      owner.debounce(input, 1.second)
      ok(r.transact { input.set("pending payload") })
      val retained = r.deadlines.head
      assert(retained.retainedInputs > 0)
      ok(r.transact { owner.dispose() })
      assertEquals(retained.retainedInputs, 0)
      val other = r.scope("provisional")
      var provisional: DeadlineBinding = null
      r.transact {
        other.debounce(other.cell("provisional payload"), 1.second)
        provisional = r.deadlines.head
        UiTxn.refuse("discard")
      }
      assertEquals(provisional.retainedInputs, 0)
      assert(r.deadlines.isEmpty)
    finally r.close()
  }

  test("far-future deadline ceiling cannot overflow the bounded parking timeout") {
    val clock = new MonotonicClock { def nowNanos: Long = 0L }
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("far future")
      val input = owner.cell(0)
      owner.debounce(input, Long.MaxValue.nanos)
      ok(r.transact { input.set(1) })
      assertEquals(r.driverSnapshot.nextDeadlineNanos, Some(Long.MaxValue))
      assertEquals(r.boundedWaitMillis(1000, 0), 1000L)
    finally r.close()
  }

  test("transaction traces distinguish dependency cycles from finite attachment budget exhaustion") {
    val cyclic = new HeadlessRuntime
    val bounded = new HeadlessRuntime(settleBudget = 2)
    try
      val mode = cyclic.cell(false)
      lazy val self: Signal[Int] = mode.flatMap(if _ then self else Signal.const(1))
      cyclic.observe(self)(_ => ())
      assertEquals(cyclic.transact { mode.set(true) }, CommitResult.Failed(KernelError.CycleDetected))
      assertEquals(cyclic.traces.last.outcome, TraceOutcome.CycleDetected)
      assert(cyclic.traces.last.graphPasses > 0)
      val switch = bounded.cell(false)
      val input = bounded.cell(1)
      val deep = input.flatMap(v => Signal.const(v).flatMap(w => Signal.const(w).flatMap(Signal.const(_))))
      val chosen = switch.flatMap(if _ then deep else input)
      bounded.observe(chosen)(_ => ())
      assertEquals(bounded.transact { switch.set(true) }, CommitResult.Failed(KernelError.BudgetExceeded))
      assertEquals(bounded.traces.last.outcome, TraceOutcome.BudgetExceeded)
      assertEquals(bounded.traces.last.graphPasses, 2)
      assertEquals(read(bounded, chosen), 1)
    finally { cyclic.close(); bounded.close() }
  }

  test("mixed keyed batches replay indexes, values and revisions across randomized replacements") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("deltas")
      val rows = owner.keyed(Vector.empty[(Int, String)])(_._1)
      val batches = mutable.ArrayBuffer.empty[Changes[Int]]
      owner.on(rows.changes)(batches += _)
      val random = new scala.util.Random(6153L)
      var before = Vector.empty[(Int, String)]
      var revision = Revision.initial
      (1 to 500).foreach { iteration =>
        val after = random.shuffle((0 to 8).filter(_ => random.nextBoolean()).map(id => id -> s"$id:${random.nextInt(3)}")).toVector
        ok(r.transact { rows.setAll(after) })
        val delivery = r.drainEvents()
        if after == before then assertEquals(delivery.delivered, 0)
        else
          assertEquals(delivery.delivered, 1)
          val batch = batches.last
          assertEquals(batch.from, revision)
          revision = revision.next
          assertEquals(batch.to, revision)
          val values = after.toMap
          var replayed = before
          batch.deltas.foreach {
            case Delta.Removed(key, at) =>
              assertEquals(replayed(at)._1, key)
              replayed = replayed.patch(at, Nil, 1)
            case Delta.Inserted(key, at) => replayed = replayed.patch(at, Vector(key -> values(key)), 0)
            case Delta.Moved(key, from, to) =>
              val item = replayed(from)
              assertEquals(item._1, key)
              replayed = replayed.patch(from, Nil, 1).patch(to, Vector(item), 0)
            case Delta.Replaced(key) => replayed = replayed.map((id, value) => id -> (if id == key then values(key) else value))
          }
          assertEquals(replayed, after, s"iteration=$iteration batch=$batch")
        before = after
      }
    finally r.close()
  }
  test("invalid quiet periods, deadline overflow and backward clocks fail explicitly") {
    val clock = new ManualClock(Long.MaxValue - 5)
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("overflow")
      val input = owner.cell(0)
      intercept[IllegalArgumentException](owner.debounce(input, Duration.Zero))
      intercept[IllegalArgumentException](owner.debounce(input, (-1).nanos))
      owner.debounce(input, 10.nanos)
      r.transact { input.set(1) } match
        case CommitResult.Failed(_: ArithmeticException) => ()
        case other => fail(s"$other")
      assertEquals(read(r, input), 0)
      assertEquals(r.driverSnapshot.pendingDeadlines, 0)
    finally r.close()
    var now = 10L
    val backwards = new HeadlessRuntime(clock = new MonotonicClock { def nowNanos: Long = now })
    try
      backwards.drive()
      now = 9L
      intercept[IllegalArgumentException](backwards.drive())
    finally backwards.close()
  }
  test("manual clock advancement cannot escape transaction or read-only guards") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      def advance(): Unit = clock.advanceBy(1.millis)
      val input = r.cell(0)
      assertEquals(r.transact { try advance() catch case _: KernelError => () }, CommitResult.Failed(KernelError.ReadOnlyPhase))
      val bad = input.map { n => try advance() catch case _: KernelError => (); n }
      assertEquals(r.read(bad), Value.Failed(KernelError.ReadOnlyPhase))
      assertEquals(clock.nowNanos, 0L)
    finally r.close()
  }
