package sirin.engine

import sirin.*
import scala.collection.mutable.ArrayBuffer

class TopologyLifetimeSuite extends munit.FunSuite:
  private def ok[A](r: HeadlessRuntime, s: Signal[A]): A = r.read(s) match
    case Value.Ok(v) => v
    case Value.Failed(e) => throw e

  test("equal branch replacement changes edges and retires exclusive derivations") {
    val r = new HeadlessRuntime
    val choose = r.cell(false)
    val left = r.cell(7)
    val right = r.cell(7)
    val l = left.map(_ * 2)
    val rr = right.map(_ * 2)
    val selected = choose.flatMap(if _ then rr else l)
    val seen = ArrayBuffer.empty[Value[Int]]
    r.observe(selected)(seen += _)
    val size = r.lifetimes.nodes
    r.transact { choose.set(true) }
    assertEquals(seen.toVector, Vector(Value.Ok(14)))
    assertEquals(r.evaluations(l), 0L)
    assertEquals(r.lifetimes.nodes, size)
    r.transact { left.set(8) }
    assertEquals(seen.size, 1)
    r.transact { right.set(9) }
    assertEquals(seen.toVector, Vector(Value.Ok(14), Value.Ok(18)))
  }

  test("nested flatMaps and diamond expose only a coherent settled snapshot") {
    val r = new HeadlessRuntime
    val choose = r.cell(false)
    val a = r.cell(1)
    val b = r.cell(10)
    val selected = choose.flatMap(if _ then b.map(_ * 2) else a.map(_ + 1))
    val nested = selected.flatMap(v => selected.map(_ + v))
    val diamond = nested.zip(selected).map((x, y) => x + y)
    val seen = ArrayBuffer.empty[(Int, Int)]
    r.observe(diamond) {
      case Value.Ok(v) => seen += ((ok(r, selected), v))
      case Value.Failed(e) => throw e
    }
    assertEquals(r.transact { choose.set(true); b.set(20) }, CommitResult.Committed(()))
    assertEquals(seen.toVector, Vector((2, 6), (40, 120)))
    assertEquals(r.diagnostics, Vector.empty)
  }

  test("allocating selection runs only on selector changes and keeps a bounded graph") {
    val r = new HeadlessRuntime
    val selector = r.cell(0)
    val data = r.cell(1)
    var calls = 0
    val selected = selector.flatMap { n => calls += 1; data.map(_ + n) }
    r.observe(selected)(_ => ())
    val size = r.lifetimes.nodes
    assertEquals(calls, 1)
    r.transact { data.set(2) }
    assertEquals(calls, 1)
    (1 to 100).foreach { n =>
      r.transact { selector.set(n) }
      assertEquals(r.lifetimes.nodes, size)
    }
    assertEquals(calls, 101)
    assertEquals(ok(r, selected), 102)
  }

  test("speculative branch reads invalidate without leaking refused topology") {
    val r = new HeadlessRuntime
    val choose = r.cell(false)
    val left = r.cell(1)
    val right = r.cell(9)
    val selected = choose.flatMap(if _ then right else left)
    r.observe(selected)(_ => ())
    val before = r.lifetimes
    assertEquals(r.transact {
      assertEquals(UiTxn.attempt {
        choose.set(true)
        assertEquals(selected.get, 9)
        right.set(11)
        assertEquals(selected.get, 11)
        UiTxn.refuse("rollback")
      }, Attempt.Refused("rollback"))
      assertEquals(selected.get, 1)
    }, CommitResult.Committed(()))
    assertEquals(r.lifetimes, before)
    assertEquals(ok(r, selected), 1)
  }

  test("selector failures publish failure data then recover") {
    val r = new HeadlessRuntime
    val mode = r.cell(0)
    val data = r.cell(8)
    val boom = new IllegalArgumentException("selector")
    val selected = mode.flatMap(n => if n == 1 then throw boom else data)
    r.observe(selected)(_ => ())
    assertEquals(r.transact { mode.set(1) }, CommitResult.Committed(()))
    assertEquals(r.read(selected), Value.Failed(boom))
    r.transact { mode.set(2) }
    assertEquals(ok(r, selected), 8)
  }

  test("self and indirect cycles reject candidate values, revisions and topology atomically") {
    val r = new HeadlessRuntime
    val mode = r.cell(false)
    val x = r.cell(1)
    lazy val a: Signal[Int] = mode.flatMap(if _ then b else x)
    lazy val b: Signal[Int] = mode.flatMap(if _ then a else x)
    r.observe(a)(_ => ())
    val before = r.lifetimes
    assertEquals(r.transact { mode.set(true); x.set(2) }, CommitResult.Failed(KernelError.CycleDetected))
    assertEquals(ok(r, mode.versioned).revision, Revision.initial)
    assertEquals(ok(r, x), 1)
    // The explicitly pinned revision description adds one node.
    assertEquals(r.lifetimes.nodes, before.nodes + 1)
    r.transact { x.set(3) }
    assertEquals(ok(r, a), 3)
    lazy val self: Signal[Int] = mode.flatMap(if _ then self else x)
    r.observe(self)(_ => ())
    assertEquals(r.transact { mode.set(true) }, CommitResult.Failed(KernelError.CycleDetected))
  }

  test("caught speculative cycle poisons even when failed data is requested") {
    val r = new HeadlessRuntime
    val mode = r.cell(false)
    lazy val self: Signal[Int] = mode.flatMap(if _ then self else Signal.const(1))
    val result = r.transact {
      mode.set(true)
      try self.attempt.get catch case _: KernelError => ()
      mode.set(false)
    }
    assertEquals(result, CommitResult.Failed(KernelError.CycleDetected))
    assertEquals(ok(r, mode), false)
  }

  test("finite deep attachment reports budget exhaustion without mislabelling a cycle") {
    val r = new HeadlessRuntime(settleBudget = 2)
    val mode = r.cell(false)
    val x = r.cell(1)
    val deep = x.flatMap(v => Signal.const(v).flatMap(w => Signal.const(w).flatMap(Signal.const(_))))
    val selected = mode.flatMap(if _ then deep else x)
    r.observe(selected)(_ => ())
    assertEquals(r.transact { mode.set(true); x.set(2) }, CommitResult.Failed(KernelError.BudgetExceeded))
    assertEquals(ok(r, x), 1)
    assertEquals(ok(r, selected), 1)
  }

  test("dynamic equality composes through zip, attempt, signed zero and custom equality") {
    val r = new HeadlessRuntime
    val x = r.cell(0.0)
    val dyn = x.flatMap(Signal.const(_))
    val joined = dyn.zip(Signal.const(())).map(v => 1.0 / v._1)
    val attempted = dyn.attempt.map {
      case Value.Ok(v) => 1.0 / v
      case _ => 0.0
    }
    Vector(joined, attempted).foreach(s => assertEquals(ok(r, s), Double.PositiveInfinity))
    r.transact { x.set(-0.0) }
    Vector(joined, attempted).foreach(s => assertEquals(ok(r, s), Double.NegativeInfinity))
    val input = r.cell(1)
    val parity = input.flatMap(Signal.const(_))(using (a, b) => a % 2 == b % 2)
    val seen = ArrayBuffer.empty[Value[Int]]
    r.observe(parity)(seen += _)
    r.transact { input.set(3) }
    assertEquals(seen.toVector, Vector(Value.Ok(1)))
  }

  test("selectors inherit cross-runtime read-only guards even when caught") {
    val r = new HeadlessRuntime
    val other = new HeadlessRuntime
    val input = r.cell(1)
    val bad = input.flatMap { n =>
      try other.scope("illegal") catch case _: KernelError => ()
      Signal.const(n)
    }
    assertEquals(r.read(bad), Value.Failed(KernelError.ReadOnlyPhase))
    assertEquals(other.lifetimes.scopes.size, 0)
  }

  test("scope cells, watches and initial effects publish together only on acceptance") {
    val r = new HeadlessRuntime
    val workspace = r.scope("workspace")
    val model = workspace.cell(0)
    val seen = ArrayBuffer.empty[Int]
    var mounted: r.OwnedScope = null
    r.transact {
      mounted = workspace.child("child")
      val draft = mounted.cell(1)
      mounted.effect(draft)(seen += _)
      draft.set(2)
      assertEquals(seen.size, 0)
      val refused = UiTxn.attempt {
        val abandoned = mounted.child("abandoned")
        abandoned.effect(model)(seen += _)
        UiTxn.refuse("no")
      }
      assertEquals(refused, Attempt.Refused("no"))
    }
    assertEquals(r.lifetimes.scopes, Set(workspace.id, mounted.id))
    assertEquals(r.flushEffects().executed, 1)
    assertEquals(seen.toVector, Vector(2))
  }

  test("refused and poisoned mounts invalidate escaped cells and callbacks") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val model = owner.cell(0)
    var escaped: Cell[Int] = null
    var delivery: () => CommitResult[Unit] = null
    val before = r.lifetimes
    val boom = new RuntimeException("abort")
    assertEquals(r.transact {
      val child = owner.child("child")
      escaped = child.cell(1)
      delivery = child.callback(model.set(9))
      child.effect(model)(_ => model.set(5))
      throw boom
    }, CommitResult.Failed(boom))
    assertEquals(r.lifetimes, before)
    assertEquals(r.transact { escaped.set(2) }, CommitResult.Failed(KernelError.DisposedScope))
    assert(delivery().isInstanceOf[CommitResult.Refused[?]])
    assertEquals(ok(r, model), 0)
    assertEquals(r.flushEffects().executed, 0)
  }

  test("refused disposal preserves observations, effects, leases and cell capabilities") {
    val r = new HeadlessRuntime
    val s = r.scope("s")
    val x = s.cell(0)
    var released = 0
    val seen = ArrayBuffer.empty[Int]
    s.onDispose(() => released += 1)
    s.effect(x)(seen += _)
    r.flushEffects()
    r.transact {
      UiTxn.attempt { s.dispose(); UiTxn.refuse("keep") }
      x.set(1)
    }
    r.flushEffects()
    assertEquals(seen.toVector, Vector(0, 1))
    assertEquals(released, 0)
    assert(!s.isDisposed)
  }

  test("write then dispose suppresses publication; dispose then write poisons") {
    val r = new HeadlessRuntime
    val a = r.scope("a")
    val x = a.cell(0)
    val seen = ArrayBuffer.empty[Value[Int]]
    a.observe(x)(seen += _)
    r.transact { x.set(1); a.dispose() }
    assertEquals(seen.toVector, Vector(Value.Ok(0)))
    assertEquals(r.transact { x.set(2) }, CommitResult.Failed(KernelError.DisposedScope))
    val b = r.scope("b")
    val y = b.cell(0)
    assertEquals(r.transact {
      b.dispose()
      try y.set(2) catch case _: KernelError => ()
    }, CommitResult.Failed(KernelError.DisposedScope))
    assert(!b.isDisposed)
    assertEquals(ok(r, y), 0)
  }

  test("parent disposal cancels descendant queued deliveries, afterCommit hooks and old generations") {
    val r = new HeadlessRuntime
    val workspace = r.scope("workspace")
    val x = workspace.cell(0)
    val definition = new ScopeDefinition("panel")
    val parent = workspace.child("parent")
    val child = parent.mount(0, definition)
    child.effect(x)(_ => x.set(10))
    val callback = child.callback(x.set(20))
    var after = 0
    r.transact { child.afterCommit(() => after += 1); parent.dispose() }
    val replacement = workspace.child("parent").mount(0, definition)
    assert(!replacement.isDisposed)
    assertEquals(r.flushEffects().executed, 0)
    assert(callback().isInstanceOf[CommitResult.Refused[?]])
    assertEquals(after, 0)
    assertEquals(ok(r, x), 0)
  }

  test("static identity uses owner, position and definition; child names remain diagnostic") {
    val r = new HeadlessRuntime
    val a = r.scope("a")
    val b = r.scope("b")
    a.child("same"); a.child("same")
    val d = new ScopeDefinition("d")
    val x = a.mount(0, d)
    assertEquals(intercept[KernelError](a.mount(0, d)), KernelError.DuplicatePlacement)
    assert(b.mount(0, d).identity != x.identity)
    assert(a.mount(1, d).identity != x.identity)
    assert(a.mount(0, new ScopeDefinition("d")).identity != x.identity)
    r.transact { x.dispose() }
    val y = a.mount(0, d)
    assertEquals(x.identity, y.identity)
    assert(x.id != y.id)
  }

  test("leases release once, compensate aborted acquisition, isolate errors and forbid reentry") {
    val r = new HeadlessRuntime
    val s = r.scope("s")
    val x = r.cell(0)
    val calls = ArrayBuffer.empty[String]
    val boom = new RuntimeException("release")
    s.onDispose(() => calls += "old")
    r.transact {
      UiTxn.attempt {
        val child = s.child("provisional")
        child.onDispose(() => calls += "rollback")
        UiTxn.refuse("abort")
      }
    }
    assertEquals(calls.toVector, Vector("rollback"))
    s.onDispose(() => throw boom)
    s.onDispose(() => { r.transact { x.set(1) }; () })
    r.transact { s.dispose() }
    assertEquals(calls.toVector, Vector("rollback", "old"))
    assert(r.diagnostics.contains(boom))
    assert(r.diagnostics.contains(KernelError.ReadOnlyPhase))
    assertEquals(ok(r, x), 0)
    r.transact { s.dispose() }
    r.close()
    assertEquals(calls.toVector, Vector("rollback", "old"))
  }

  test("mount and dispose within one transaction never publishes initial effects") {
    val r = new HeadlessRuntime
    val parent = r.scope("p")
    var count = 0
    var releases = 0
    r.transact {
      val child = parent.child("new")
      val x = child.cell(0)
      child.effect(x)(_ => count += 1)
      child.onDispose(() => releases += 1)
      child.dispose()
    }
    assertEquals(r.lifetimes.scopes, Set(parent.id))
    assertEquals(r.lifetimes.cells.size, 0)
    assertEquals(r.flushEffects().executed, 0)
    assertEquals(count, 0)
    assertEquals(releases, 1)
  }

  test("churn retires scoped graphs and preserves workspace and shared branches") {
    val r = new HeadlessRuntime
    val workspace = r.scope("workspace")
    val x = workspace.cell(1)
    val choose = workspace.cell(false)
    val shared = x.map(_ * 2)
    val keep = workspace.child("keep")
    keep.observe(shared)(_ => ())
    val baseline = r.lifetimes
    (1 to 100).foreach { n =>
      val child = workspace.child("row")
      val selected = choose.flatMap(if _ then shared.map(_ + n) else shared)
      child.observe(selected)(_ => ())
      child.effect(selected)(_ => ())
      r.transact { choose.set(!choose.get); x.set(n); child.dispose() }
      r.flushEffects()
      assertEquals(r.lifetimes, baseline)
    }
    assertEquals(ok(r, x), 100)
    assertEquals(ok(r, shared), 200)
  }

  test("close disposes descendants and rejects cross-runtime and foreign ownership") {
    val r = new HeadlessRuntime
    val other = new HeadlessRuntime
    val s = r.scope("s")
    val child = s.child("c")
    var releases = 0
    child.onDispose(() => releases += 1)
    assertEquals(intercept[KernelError](s.observe(other.cell(1))(_ => ())), KernelError.ForeignSignal)
    r.close()
    assertEquals(releases, 1)
    assertEquals(intercept[KernelError](child.cell(0)), KernelError.Disposed)
  }

  test("late attachment of a disposed or rolled-back cell never publishes its initial value") {
    val r = new HeadlessRuntime
    val scope = r.scope("old")
    val dead = scope.cell(99)
    val choose = r.cell(false)
    val alive = r.cell(1)
    val selected = choose.flatMap(if _ then dead else alive)
    val seen = ArrayBuffer.empty[Value[Int]]
    r.observe(selected)(seen += _)
    r.transact { scope.dispose() }
    assertEquals(r.transact { choose.set(true) }, CommitResult.Committed(()))
    assertEquals(seen.toVector, Vector(Value.Ok(1), Value.Failed(KernelError.DisposedScope)))
    r.transact { choose.set(false) }
    var escaped: Cell[Int] = null
    r.transact {
      UiTxn.attempt {
        val aborted = r.scope("aborted")
        escaped = aborted.cell(88)
        UiTxn.refuse("no")
      }
    }
    val other = choose.flatMap(if _ then escaped else alive)
    val seenOther = ArrayBuffer.empty[Value[Int]]
    r.observe(other)(seenOther += _)
    r.transact { choose.set(true) }
    assertEquals(seenOther.toVector, Vector(Value.Ok(1), Value.Failed(KernelError.DisposedScope)))
  }

  test("a callback from a scope staged for disposal cannot mutate workspace state") {
    val r = new HeadlessRuntime
    val scope = r.scope("s")
    val model = r.cell(0)
    val callback = scope.callback(model.set(10))
    assertEquals(r.transact {
      scope.dispose()
      assertEquals(callback(), CommitResult.Refused("disposed scope"))
    }, CommitResult.Committed(()))
    assertEquals(ok(r, model), 0)
  }

  test("staged parent disposal and refused disposal preserve callback lifetime boundaries") {
    val r = new HeadlessRuntime
    val parent = r.scope("parent")
    val child = parent.child("child")
    val model = r.cell(0)
    var calls = 0
    val callback = child.callback { calls += 1; model.set(10) }
    r.transact {
      UiTxn.attempt {
        parent.dispose()
        assertEquals(callback(), CommitResult.Refused("disposed scope"))
        UiTxn.refuse("restore")
      }
      assertEquals(callback(), CommitResult.Joined(()))
    }
    assertEquals(calls, 1)
    assertEquals(ok(r, model), 10)
  }

  test("disposing an alternate scope while attaching its derived cell never publishes stale data") {
    val r = new HeadlessRuntime
    val scope = r.scope("old")
    val dead = scope.cell(99).map(_ + 1)
    val choose = r.cell(false)
    val selected = choose.flatMap(if _ then dead else Signal.const(1))
    val seen = ArrayBuffer.empty[Value[Int]]
    r.observe(selected)(seen += _)
    r.transact { scope.dispose(); choose.set(true) }
    assertEquals(seen.toVector, Vector(Value.Ok(1), Value.Failed(KernelError.DisposedScope)))
  }

  test("a provisional cell has no durable committed snapshot") {
    val r = new HeadlessRuntime
    var x: Cell[Int] = null
    r.transact {
      x = r.scope("mount").cell(1)
      assertEquals(UiTxn.attempt(x.snapshot), Attempt.Refused("snapshot requires a committed cell; finish mounting first"))
    }
    assertEquals(r.transact { x.snapshot }, CommitResult.Committed(Versioned(1, Revision.initial)))
  }

  test("a changed definition at the same position resets state and rolls back on refusal") {
    val r = new HeadlessRuntime
    val parent = r.scope("p")
    val firstDefinition = new ScopeDefinition("first")
    val secondDefinition = new ScopeDefinition("second")
    val first = parent.mount(0, firstDefinition)
    val x = first.cell(1)
    var replacement: r.OwnedScope = null
    r.transact {
      UiTxn.attempt {
        replacement = parent.mount(0, secondDefinition)
        replacement.cell(2)
        UiTxn.refuse("restore")
      }
      x.set(3)
    }
    assert(!first.isDisposed)
    assert(replacement.isDisposed)
    val second = parent.mount(0, secondDefinition)
    assert(first.isDisposed)
    assert(!second.isDisposed)
    assertEquals(r.transact { x.set(4) }, CommitResult.Failed(KernelError.DisposedScope))
  }

  test("the public Scope facade executes its supported lifecycle and refuses deferred features") {
    val r = new HeadlessRuntime
    val model: Scope = r.scope("public")
    val input = model.cell(1)
    val child = model.child("child")
    val effects = ArrayBuffer.empty[Int]
    child.effect(input)(effects += _)
    r.flushEffects()
    r.transact { input.set(2) }
    r.flushEffects()
    assertEquals(effects.toVector, Vector(1, 2))
    r.transact { child.dispose(); input.set(3) }
    assertEquals(r.flushEffects().executed, 0)
    assertEquals(intercept[KernelError](model.events[Int]), KernelError.Deferred("events"))
    r.close()
  }

  test("first mounted publication uses final initialization at revision zero even under custom equality") {
    val r = new HeadlessRuntime
    var x: Cell[Int] = null
    var y: Cell[Int] = null
    r.transact {
      val scope = r.scope("mount")
      x = scope.cell(1)
      y = scope.cell(1)(using (a, b) => a % 2 == b % 2)
      x.set(2)
      y.set(3)
      assertEquals(x.versioned.get, Versioned(2, Revision.initial))
      assertEquals(y.versioned.get, Versioned(3, Revision.initial))
    }
    assertEquals(ok(r, x.versioned), Versioned(2, Revision.initial))
    assertEquals(ok(r, y.versioned), Versioned(3, Revision.initial))
    r.transact { x.set(3) }
    assertEquals(ok(r, x.versioned), Versioned(3, Revision(1)))
  }
