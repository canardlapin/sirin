package sirin.engine

import sirin.*
import scala.collection.mutable.ArrayBuffer
import java.util.concurrent.atomic.AtomicReference

class TransactionSuite extends munit.FunSuite:
  private def ok[A](runtime: HeadlessRuntime, signal: Signal[A]): A = runtime.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error

  test("staged derived reads invalidate memo after each write") {
    val r = new HeadlessRuntime
    val x = r.cell(1)
    val y = x.map(_ * 2)
    assertEquals(r.transact {
      x.set(2)
      val first = y.get
      x.set(3)
      (first, y.get)
    }, CommitResult.Committed((4, 6)))
    assertEquals(ok(r, y), 6)
  }

  test("diamond publishes atomically and evaluates once per committed pass") {
    val r = new HeadlessRuntime
    val x = r.cell(1)
    val a = x.map(_ + 1)
    val b = x.map(_ * 3)
    val joined = a.zip(b).map((a, b) => a + b)
    val observations = ArrayBuffer.empty[(Int, Int)]
    r.read(x)
    r.observe(joined)(value => value match
      case Value.Ok(v) => observations += ((ok(r, x), v))
      case _ => fail("unexpected failure"))
    val before = r.evaluations(joined)
    r.transact { x.set(2); x.set(4) }
    assertEquals(observations.toVector, Vector((1, 5), (4, 17)))
    assertEquals(r.evaluations(joined) - before, 1L)
  }

  test("equality stops propagation and change-restore preserves revision") {
    val r = new HeadlessRuntime
    val x = r.cell(2)
    val parity = x.map(_ % 2)
    val downstream = parity.map(_ + 10)
    r.read(downstream)
    val before = r.evaluations(downstream)
    r.transact { x.set(4) }
    assertEquals(r.evaluations(downstream), before)
    assertEquals(ok(r, x.versioned).revision.value, 1L)
    r.transact { x.set(5); x.set(4) }
    assertEquals(ok(r, x.versioned).revision.value, 1L)
  }

  test("scoped refusal restores writes and staged external work, preserving caller state") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val touched = r.cell(false)
    var starts = 0
    val result = r.transact {
      touched.set(true)
      x.set(1)
      val refused = UiTxn.attempt {
        x.set(2)
        r.afterCommit(() => starts += 1)
        UiTxn.refuse("invalid")
      }
      assertEquals(refused, Attempt.Refused("invalid"))
      assertEquals(x.get, 1)
      r.afterCommit(() => starts += 10)
    }
    assert(result.isInstanceOf[CommitResult.Committed[?]])
    assertEquals(ok(r, x), 1)
    assert(ok(r, touched))
    assertEquals(starts, 10)
  }

  test("caught refusal cannot accidentally commit an attempt or outer transaction") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val result = r.transact {
      val a = UiTxn.attempt {
        x.set(1)
        try UiTxn.refuse("no") catch case _: scala.util.control.ControlThrowable => ()
      }
      assertEquals(a, Attempt.Refused("no"))
      x.set(2)
      try UiTxn.refuse("outer") catch case _: scala.util.control.ControlThrowable => ()
    }
    assertEquals(result, CommitResult.Refused("outer"))
    assertEquals(ok(r, x), 0)
  }

  test("caught attempt defect poisons the whole transaction") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val boom = new IllegalArgumentException("defect")
    val result = r.transact {
      x.set(1)
      try UiTxn.attempt { x.set(2); throw boom }
      catch case _: IllegalArgumentException => ()
      x.set(3)
    }
    assertEquals(result, CommitResult.Failed(boom))
    assertEquals(ok(r, x), 0)
  }

  test("caught update callback defect poisons, locally handled application exceptions do not") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val boom = new IllegalStateException("update")
    assertEquals(r.transact {
      try x.update(_ => throw boom) catch case _: IllegalStateException => ()
      x.set(4)
    }, CommitResult.Failed(boom))
    assertEquals(ok(r, x), 0)
    assertEquals(r.transact {
      try throw boom catch case _: IllegalStateException => ()
      x.set(5)
    }, CommitResult.Committed(()))
  }

  test("nested transactions join without intermediate publication and retain poison") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val seen = ArrayBuffer.empty[Value[Int]]
    r.observe(x)(seen += _)
    r.transact {
      x.set(1)
      assertEquals(r.transact { x.set(2) }, CommitResult.Joined(()))
      assertEquals(seen.size, 1)
    }
    assertEquals(seen.toVector, Vector(Value.Ok(0), Value.Ok(2)))
    val boom = new RuntimeException("inner")
    assertEquals(r.transact { r.transact { throw boom }; x.set(3) }, CommitResult.Failed(boom))
    assertEquals(ok(r, x), 2)
  }

  test("failed derivations publish atomically, can be observed as data, and recover") {
    val r = new HeadlessRuntime
    val divisor = r.cell(2)
    val quotient = divisor.map(10 / _)
    r.read(quotient)
    assertEquals(r.transact { divisor.set(0) }, CommitResult.Committed(()))
    assertEquals(ok(r, divisor), 0)
    assert(ok(r, quotient.attempt).isInstanceOf[Value.Failed[?]])
    val result = r.transact {
      try quotient.get catch case _: ArithmeticException => ()
      divisor.set(1)
    }
    assert(result.isInstanceOf[CommitResult.Failed[?]])
    assertEquals(ok(r, divisor), 0)
    r.transact { divisor.set(5) }
    assertEquals(ok(r, quotient), 2)
  }

  test("derivations cannot write through captured transaction capabilities") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    var illegal: Signal[Int] = Signal.const(0)
    r.transact {
      val captured = summon[UiTxn]
      illegal = x.map { v => x.set(100)(using captured); v }
      assert(illegal.attempt.get.isInstanceOf[Value.Failed[?]])
      x.set(1)
    }
    assertEquals(ok(r, x), 1)
    assert(r.read(illegal).isInstanceOf[Value.Failed[?]])
  }

  test("durable snapshot refuses intermediate revision alias and survives subsequent edits") {
    val r = new HeadlessRuntime
    val text = r.cell("original")
    r.transact {
      text.set("A")
      assertEquals(text.versioned.get.revision.value, 1L)
      assert(UiTxn.attempt(text.snapshot).isInstanceOf[Attempt.Refused[?]])
      text.set("B")
    }
    assertEquals(ok(r, text.versioned), Versioned("B", Revision(1)))
    val saved = r.transact {
      val snapshot = text.snapshot
      text.set("C")
      snapshot
    }
    assertEquals(saved, CommitResult.Committed(Versioned("B", Revision(1))))
    assertEquals(ok(r, text.versioned), Versioned("C", Revision(2)))
  }

  test("effects run initially and for unequal commits, each in a fresh transaction") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val mirror = r.cell(-1)
    val seen = ArrayBuffer.empty[Int]
    r.effect(x) { value => seen += value; mirror.set(value) }
    assertEquals(seen.size, 0)
    assertEquals(r.flushEffects().executed, 1)
    r.transact { x.set(1); x.set(2) }
    r.transact { x.set(2) }
    r.transact { x.set(9); x.set(2) }
    assertEquals(r.flushEffects().executed, 1)
    assertEquals(seen.toVector, Vector(0, 2))
    assertEquals(ok(r, mirror), 2)
  }

  test("failed effect retains the prior settled commit and notifications") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val y = r.cell(0)
    val boom = new RuntimeException("effect")
    r.effect(x) { v => if v > 0 then { y.set(v); throw boom } }
    r.flushEffects()
    r.transact { x.set(7) }
    assertEquals(r.flushEffects().failures, Vector(boom))
    assertEquals(ok(r, x), 7)
    assertEquals(ok(r, y), 0)
  }

  test("effect drain has a bounded budget and does not falsely claim a cycle") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    r.effect(x)(value => x.set(value + 1))
    val report = r.flushEffects(4)
    assertEquals(report.executed, 4)
    assert(report.budgetExceeded)
    assertEquals(ok(r, x), 4)
    r.close()
  }

  test("cancelling subscriptions removes queued effects and disposal prevents access") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    var count = 0
    val sub = r.effect(x)(_ => count += 1)
    sub.cancel()
    assertEquals(r.flushEffects().executed, 0)
    assertEquals(count, 0)
    r.close()
    assertEquals(intercept[KernelError](r.read(x)), KernelError.Disposed)
  }

  test("expired, cross-runtime and foreign-thread capabilities are rejected") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    var escaped: UiTxn = null
    r.transact { escaped = summon[UiTxn] }
    assertEquals(intercept[KernelError](x.set(1)(using escaped)), KernelError.ExpiredTransaction)
    val other = new HeadlessRuntime
    assertEquals(intercept[KernelError](other.read(x)), KernelError.ForeignSignal)
    val error = new AtomicReference[Throwable]()
    val worker = new Thread(() => try { r.read(x); () } catch case e: Throwable => error.set(e))
    worker.start()
    worker.join()
    assertEquals(error.get(), KernelError.WrongThread)
  }

  test("observer failure and forbidden mutation cannot retract a published snapshot") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    r.observe(x)(_ => r.transact { x.set(100) })
    assertEquals(r.transact { x.set(1) }, CommitResult.Committed(()))
    assertEquals(ok(r, x), 1)
    assert(r.diagnostics.contains(KernelError.ReadOnlyPhase))
  }

  test("NaN equality is stable and signed zeros follow equals semantics") {
    val r = new HeadlessRuntime
    val value = r.cell(Double.NaN)
    r.transact { value.set(Double.NaN) }
    assertEquals(ok(r, value.versioned).revision.value, 0L)
    r.transact { value.set(0.0) }
    r.transact { value.set(-0.0) }
    assertEquals(ok(r, value.versioned).revision.value, 2L)
  }

  test("read-only phases reject captured second runtimes even if the violation is caught") {
    val a = new HeadlessRuntime
    val b = new HeadlessRuntime
    val source = a.cell(0)
    val other = b.cell(99)
    val bad = source.map { value =>
      try b.transact { other.set(value) } catch case _: KernelError => ()
      value
    }
    assert(a.read(bad).isInstanceOf[Value.Failed[?]])
    assertEquals(ok(b, other), 99)
    val badRead = source.map(_ => b.read(other))
    assert(a.read(badRead).isInstanceOf[Value.Failed[?]])
  }

  test("caught equality mutation aborts before atomic publication") {
    val r = new HeadlessRuntime
    val target = r.cell(0)
    var captured: UiTxn = null
    val source = r.cell(0)(using (a, b) =>
      try target.set(100)(using captured) catch case _: KernelError => ()
      a == b)
    val result = r.transact { captured = summon[UiTxn]; source.set(1) }
    assertEquals(result, CommitResult.Failed(KernelError.ReadOnlyPhase))
    assertEquals(ok(r, source), 0)
    assertEquals(ok(r, target), 0)
  }

  test("poison wins over subsequent refusal") {
    val r = new HeadlessRuntime
    val boom = new RuntimeException("poison")
    val result = r.transact {
      try UiTxn.attempt { throw boom } catch case _: RuntimeException => ()
      UiTxn.refuse("later refusal")
    }
    assertEquals(result, CommitResult.Failed(boom))
  }

  test("caught stale set/update/snapshot errors poison the current transaction") {
    val r = new HeadlessRuntime
    val x = r.cell(0)
    val y = r.cell(0)
    var old: UiTxn = null
    r.transact { old = summon[UiTxn] }
    val bad = Vector[() => Unit](
      () => x.set(1)(using old),
      () => x.update(_ + 1)(using old),
      () => { x.snapshot(using old); () })
    bad.foreach { action =>
      val result = r.transact {
        try action() catch case _: KernelError => ()
        y.set(2)
      }
      assertEquals(result, CommitResult.Failed(KernelError.ExpiredTransaction))
      assertEquals(ok(r, x), 0)
      assertEquals(ok(r, y), 0)
    }
  }

  test("zip, attempt, mapN and projected equality preserve signed-zero and NaN laws") {
    val r = new HeadlessRuntime
    val x = r.cell(0.0)
    val zipped = x.zip(Signal.const(())).map(pair => 1.0 / pair._1)
    val combined = (x, Signal.const(1)).mapN((v, _) => 1.0 / v)
    val attempted = x.attempt.map {
      case Value.Ok(v) => 1.0 / v
      case Value.Failed(_) => 0.0
    }
    Vector(zipped, combined, attempted).foreach(signal => assertEquals(ok(r, signal), Double.PositiveInfinity))
    r.transact { x.set(-0.0) }
    Vector(zipped, combined, attempted).foreach(signal => assertEquals(ok(r, signal), Double.NegativeInfinity))
    r.transact { x.set(Double.NaN) }
    val runs = r.evaluations(zipped)
    r.transact { x.set(Double.NaN) }
    assertEquals(r.evaluations(zipped), runs)
    val equality = Equality.by[Double, Double](identity)
    assert(equality.same(Double.NaN, Double.NaN))
    assert(!equality.same(0.0, -0.0))
  }

  test("read-only phases also forbid graph installation through another runtime") {
    val a = new HeadlessRuntime
    val b = new HeadlessRuntime
    val x = a.cell(0)
    val bad = x.map(value => b.cell(value))
    assert(a.read(bad).isInstanceOf[Value.Failed[?]])
  }
