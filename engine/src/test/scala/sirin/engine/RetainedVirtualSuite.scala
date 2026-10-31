package sirin.engine

import sirin.*
import scala.collection.mutable

object RetainedVirtualSuite:
  final class Coarse(val version: Int):
    override def equals(other: Any): Boolean = other.isInstanceOf[Coarse]
    override def hashCode(): Int = 1

class RetainedVirtualSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def read[A](r: HeadlessRuntime, signal: Signal[A]): A = r.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  private def visit(value: MountedSnapshot): Vector[MountedSnapshot] = value +: (value match
    case MountedSnapshot.Referenced(_,child) => visit(child)
    case MountedSnapshot.Styled(child,_) => visit(child)
    case MountedSnapshot.CustomLayout(_, children) => children.flatMap((_, child) => visit(child))
    case MountedSnapshot.Component(_, _, child) => visit(child)
    case MountedSnapshot.Rows(rows) => rows.flatMap((_, child) => visit(child))
    case MountedSnapshot.Branch(_, _, child) => visit(child)
    case MountedSnapshot.Outlet(_, child) => visit(child)
    case MountedSnapshot.Reparented(_, _, child) => visit(child)
    case MountedSnapshot.VirtualRows(_, _, _, _, _, _, _, child, _) => visit(child)
    case _ => Vector.empty)
  private def panels(r: HeadlessRuntime, m: MountedView): Vector[Long] = visit(read(r, m.snapshot)).collect {
    case MountedSnapshot.Reparented(_, instance, _) => instance
  }
  private def components(r: HeadlessRuntime, m: MountedView): Vector[(Int, Long)] = visit(read(r, m.snapshot)).collect {
    case MountedSnapshot.Component(id, value: Int, _) => value -> id.instance
  }

  test("moving, hiding and detaching a retained panel preserves its draft and subscription") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("model")
      val left = owner.ref(); val right = owner.ref(); val absent = owner.ref()
      val target = owner.cell[NodeRef](left)
      val events = owner.events[Int]
      var builds = 0
      var draft: Cell[Int] = null
      val definition = Component[Unit]("editor") { (scope, _) =>
        builds += 1; draft = scope.cell(0)
        scope.on(events)(n => draft.update(_ + n))
        View.empty
      }
      val panel = owner.reparent(GlobalKey("editor"), definition(()))
      val mounted = r.mount(owner, Column(DockOutlet(left), DockOutlet(right), panel.at(target)))
      val identity = panels(r, mounted)
      assertEquals(identity.size, 1)
      ok(r.transact { draft.set(7); target.set(right) })
      assertEquals(panels(r, mounted), identity)
      ok(r.transact { target.set(absent); events.emit(3) })
      assertEquals(panels(r, mounted), Vector.empty)
      r.drainEvents()
      assertEquals(read(r, draft), 10)
      ok(r.transact { mounted.update(panel.at(target)); target.set(left) })
      assertEquals(panels(r, mounted), Vector.empty)
      ok(r.transact { mounted.update(Column(DockOutlet(left), panel.at(target))) })
      assertEquals(panels(r, mounted), identity)
      assertEquals(builds, 1)
      assertEquals(r.transact { target.set(absent); UiTxn.refuse("keep") }, CommitResult.Refused("keep"))
      assertEquals(panels(r, mounted), identity)
      ok(r.transact { owner.dispose() })
      assertEquals(r.transact { draft.set(0) }, CommitResult.Failed(KernelError.DisposedScope))
    finally r.close()
  }

  test("duplicate references and placements reject atomically without retaining candidates") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("owner")
      val ref = owner.ref()
      val panel = owner.reparent(GlobalKey("one"), View.empty)
      val mounted = r.mount(owner, Column(DockOutlet(ref), panel.at(Signal.const(ref))))
      val before = read(r, mounted.snapshot); val attachments = r.attachments
      assertEquals(r.transact { mounted.update(Column(DockOutlet(ref), DockOutlet(ref))) }, CommitResult.Failed(KernelError.DuplicateReference))
      assertEquals(read(r, mounted.snapshot), before)
      assertEquals(r.attachments, attachments)
      assertEquals(r.docking.candidateCount, 0)
      assertEquals(r.transact { mounted.update(Column(DockOutlet(ref), panel.at(Signal.const(ref)), panel.at(Signal.const(ref)))) }, CommitResult.Failed(KernelError.DuplicatePlacement))
      val another = owner.reparent(GlobalKey("two"), View.empty)
      assertEquals(r.transact { mounted.update(Column(DockOutlet(ref), panel.at(Signal.const(ref)), another.at(Signal.const(ref)))) }, CommitResult.Failed(KernelError.DuplicatePlacement))
      assertEquals(r.transact { owner.reparent(GlobalKey("one"), View.empty) }, CommitResult.Failed(KernelError.DuplicateGlobalKey))
      assertEquals(r.docking.candidateCount, 0)
      assertEquals(read(r, mounted.snapshot), before)
    finally r.close()
  }

  test("escaped registrations from an aborted attempt are permanently invalid") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("owner")
      val live = owner.ref(); val absent = owner.ref()
      var ref: NodeRef = null; var panel: Reparentable = null
      ok(r.transact {
        UiTxn.attempt { ref = owner.ref(); panel = owner.reparent(GlobalKey("rolled"), View.empty); UiTxn.refuse("abort") }
      })
      assertEquals(r.transact { r.mount(owner, View.empty.ref(ref)) }, CommitResult.Failed(KernelError.DisposedScope))
      assertEquals(r.transact { r.mount(owner, DockOutlet(ref)) }, CommitResult.Failed(KernelError.DisposedScope))
      for target <- Vector(live, absent) do
        assertEquals(r.transact { r.mount(owner, Column(DockOutlet(live), panel.at(Signal.const(target)))) }, CommitResult.Failed(KernelError.DisposedScope))
      owner.reparent(GlobalKey("rolled"), View.empty)
      assertEquals(r.docking.candidateCount, 0)
    finally r.close()
  }

  test("self and mutual panel containment cycles reject while missing targets are harmless") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("owner")
      val a = owner.ref(); val b = owner.ref(); val absent = owner.ref()
      val p = owner.reparent(GlobalKey("a"), DockOutlet(a))
      val q = owner.reparent(GlobalKey("b"), DockOutlet(b))
      val m = r.mount(owner, p.at(Signal.const(absent)))
      assertEquals(r.transact { m.update(p.at(Signal.const(a))) }, CommitResult.Failed(KernelError.CycleDetected))
      assertEquals(r.transact { m.update(Column(p.at(Signal.const(b)), q.at(Signal.const(a)))) }, CommitResult.Failed(KernelError.CycleDetected))
      assertEquals(r.docking.candidateCount, 0)
      assertEquals(panels(r, m), Vector.empty)
    finally r.close()
  }

  test("foreign references fail and disposed outlets hide without retiring the panel") {
    val r = new HeadlessRuntime; val foreign = new HeadlessRuntime
    try
      val owner = r.scope("owner"); val outletOwner = owner.child("outlets")
      val ref = outletOwner.ref(); val target = owner.cell[NodeRef](ref)
      val panel = owner.reparent(GlobalKey("editor"), View.empty)
      val m = r.mount(owner, Column(DockOutlet(ref), panel.at(target)))
      ok(r.transact { outletOwner.dispose() })
      assertEquals(panels(r, m), Vector.empty)
      val other = foreign.scope("foreign").ref()
      assertEquals(r.transact { target.set(other) }, CommitResult.Failed(KernelError.ForeignNodeRef))
      assertEquals(r.transact { m.update(View.empty.ref(other)) }, CommitResult.Failed(KernelError.ForeignNodeRef))
    finally { r.close(); foreign.close() }
  }

  test("filtered staged reads, revisions, chains and nested rollback follow the current handler") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("source")
      val source = owner.keyed(Seq(1, 2, 3, 4))(identity)
      val query = owner.cell(2)
      val filtered = source.filterBy(query)((q, a) => a >= q)
      val chained = filtered.filterBy(Signal.const(()))((_, a) => a % 2 == 0)
      val before = read(r, filtered.revision)
      ok(r.transact {
        query.set(3)
        assertEquals(filtered.snapshot.get, Vector(3, 4))
        assertEquals(filtered.size, 2)
        assertEquals(filtered.window(Signal.const(IndexRange(0, 1))).get.entries, Vector(3 -> 3))
        assertEquals(filtered.revision.get, before.next)
        assertEquals(chained.snapshot.get, Vector(4))
        UiTxn.attempt { query.set(1); assertEquals(filtered.size, 4); UiTxn.refuse("undo") }
        assertEquals(filtered.size, 2)
        source.append(6)
        assertEquals(chained.snapshot.get, Vector(4, 6))
      })
      assertEquals(read(r, chained.snapshot), Vector(4, 6))
      assertEquals(read(r, filtered.revision), before.next)
      ok(r.transact { query.set(0); query.set(3) })
      assertEquals(read(r, filtered.revision), before.next)
    finally r.close()
  }

  test("filtered failure and recovery expose revisions and successful change batches") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("source")
      val source = owner.keyed(Seq(1, 2, 3))(identity)
      val bad = owner.cell(false); val error = new IllegalStateException("query")
      val query = bad.map(v => if v then throw error else 2)
      val filtered = source.filterBy(query)((q, a) => a >= q)
      val seen = mutable.ArrayBuffer.empty[Changes[Int]]
      owner.on(filtered.changes)(seen += _)
      val first = read(r, filtered.revision)
      ok(r.transact { bad.set(true) })
      assertEquals(r.read(filtered.snapshot), Value.Failed(error))
      ok(r.transact { bad.set(false); assertEquals(filtered.snapshot.get, Vector(2, 3)); assertEquals(filtered.revision.get, first.next.next) })
      r.drainEvents()
      assertEquals(seen.size, 1)
      assertEquals(seen.head.from, first)
      assertEquals(seen.head.to, first.next.next)
    finally r.close()
  }

  test("fixed-height viewport bounds, overscan, clamping and zero height") {
    assertEquals(RowViewport.bounds(RowViewport(Lp(15), Lp(20)), Lp(10), 1), IndexRange(0, 5))
    assertEquals(RowViewport.bounds(RowViewport(Lp(15), Lp(0)), Lp(10), 9), IndexRange(0, 0))
    intercept[IllegalArgumentException](RowViewport(Lp(-1), Lp(1)))
    intercept[IllegalArgumentException](RowViewport(Lp(0), Lp(Double.NaN)))
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val source = owner.keyed(0 until 20)(identity)
      val row = Component[Int]("row")((_, _) => View.empty)
      val m = r.mount(owner, VirtualRows(source, Lp(10), 1)(input => row(input)))
      assertEquals(components(r, m), Vector.empty)
      ok(r.transact { m.setViewport(RowViewport(Lp(15), Lp(20))) })
      assertEquals(components(r, m).map(_._1), Vector(0, 1, 2, 3, 4))
      ok(r.transact { m.setViewport(RowViewport(Lp(1000), Lp(20))) })
      assertEquals(components(r, m), Vector.empty)
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(0))) })
      assertEquals(components(r, m), Vector.empty)
      intercept[IllegalArgumentException](VirtualRows(source, Lp(0), 0)(_ => View.empty))
      intercept[IllegalArgumentException](VirtualRows(source, Lp(10), -1)(_ => View.empty))
    finally r.close()
  }

  test("overlapping virtual rows retain identity and draft; unrealized rows retire") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val source = owner.keyed(0 until 50)(identity)
      val drafts = mutable.Map.empty[Int, Cell[Int]]
      val row = Component[Int]("row") { (scope, input) =>
        scope.effect(input)(_ => ())
        // Inputs cannot be read at construction; retain the public signal for lookup.
        val draft = scope.cell(0)
        scope.effect(input)(v => drafts(v) = draft)
        View.empty
      }
      val m = r.mount(owner, VirtualRows(source, Lp(10), 0)(input => row(input)))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(30))) }); r.flushEffects()
      val before = components(r, m).toMap; val old = drafts(0)
      ok(r.transact { drafts(1).set(7); m.setViewport(RowViewport(Lp(10), Lp(30))) }); r.flushEffects()
      val after = components(r, m).toMap
      assertEquals(after(1), before(1)); assertEquals(after(2), before(2))
      assertEquals(read(r, drafts(1)), 7)
      assertEquals(r.transact { old.set(1) }, CommitResult.Failed(KernelError.DisposedScope))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(30))) }); r.flushEffects()
      assert(components(r, m).toMap.apply(0) != before(0))
      assertEquals(read(r, drafts(0)), 0)
    finally r.close()
  }

  test("viewport-only scrolling does not rerun a large filter or compare its whole projection") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list")
      var comparisons = 0; var predicates = 0
      val equality: Equality[Int] = (a, b) => { comparisons += 1; a == b }
      val source = owner.keyed(0 until 20000)(identity)(using equality)
      val filtered = source.filterBy(Signal.const(())) { (_, a) => predicates += 1; a % 2 == 0 }
      val row = Component[Int]("row")((_, _) => View.empty)(using equality)
      val m = r.mount(owner, VirtualRows(filtered, Lp(10), 1)(input => row(input))(using equality, summon[SchemaId[Int]], summon[SchemaId[Int]]))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(50))) })
      predicates = 0; comparisons = 0
      for n <- 1 to 5 do ok(r.transact { m.setViewport(RowViewport(Lp(n * 10), Lp(50))) })
      assertEquals(predicates, 0)
      assert(comparisons < 5000, s"scroll compared $comparisons values")
    finally r.close()
  }

  test("retained virtual Inputs preserve signed-zero equality") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val source = owner.keyed(Seq(0.0))(_ => 1)
      var input: Inputs[Double] = null
      val row = Component[Double]("row") { (scope, in) => input = in; scope.effect(in.map(v => 1.0 / v))(_ => ()); View.empty }
      val m = r.mount(owner, VirtualRows(source, Lp(10), 0)(in => row(in)))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(10))) })
      val original = input
      ok(r.transact { source.replace(-0.0) })
      assert(input eq original)
      assertEquals(read(r, input.map(v => 1.0 / v)), Double.NegativeInfinity)
    finally r.close()
  }

  test("aborted filtered sources and chained lookups cannot escape their registration lifetime") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("source"); val source = owner.keyed(Seq(1, 2, 3))(identity)
      var filtered: CollectionSource[Int, Int] = null
      var chained: CollectionSource[Int, Int] = null
      ok(r.transact { UiTxn.attempt {
        filtered = source.filterBy(Signal.const(2))((q, a) => a >= q)
        chained = filtered.filterBy(Signal.const(()))((_, _) => true)
        UiTxn.refuse("abort")
      } })
      for invalid <- Vector(filtered, chained) do
        assertEquals(r.transact { invalid.snapshot.get }, CommitResult.Failed(KernelError.DisposedScope))
        assertEquals(r.transact { invalid.get(2) }, CommitResult.Failed(KernelError.DisposedScope))
        assertEquals(r.transact { invalid.at(2).get }, CommitResult.Failed(KernelError.DisposedScope))
        assertEquals(r.transact { invalid.window(Signal.const(IndexRange(0, 1))).get }, CommitResult.Failed(KernelError.DisposedScope))
      val live = source.filterBy(Signal.const(2))((q, a) => a >= q)
      ok(r.transact { owner.dispose() })
      assertEquals(r.transact { live.size }, CommitResult.Failed(KernelError.DisposedScope))
    finally r.close()
  }

  test("simultaneous query and viewport edits retain rows in the final overlapping range") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val source = owner.keyed(0 until 30)(identity); val query = owner.cell(0)
      val filtered = source.filterBy(query)((q, a) => a >= q)
      val row = Component[Int]("row")((_, _) => View.empty)
      val m = r.mount(owner, VirtualRows(filtered, Lp(10), 1)(in => row(in)))
      ok(r.transact { m.setViewport(RowViewport(Lp(106), Lp(40))) })
      val before = components(r, m).toMap
      ok(r.transact { m.setViewport(RowViewport(Lp(73), Lp(10))); query.set(4) })
      components(r, m).foreach((key, id) => assertEquals(id, before(key)))
    finally r.close()
  }

  test("per-node viewports, source rebinding and stale node controls") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val source = owner.keyed(0 until 20)(identity)
      val other = owner.keyed(Seq(1, 2, 3, 20))(identity)
      val row = Component[Int]("row")((_, _) => View.empty)
      def view(s: CollectionSource[Int, Int]): View = VirtualRows(s, Lp(10), 0)(in => row(in))
      val m = r.mount(owner, Column(view(source), view(source)))
      val ids = visit(read(r, m.snapshot)).collect { case MountedSnapshot.VirtualRows(id, _, _, _, _, _, _, _, _) => id }
      ok(r.transact { m.setViewport(ids(0), RowViewport(Lp(10), Lp(20))); m.setViewport(ids(1), RowViewport(Lp(50), Lp(10))) })
      assertEquals(components(r, m).map(_._1), Vector(1, 2, 5))
      val before = components(r, m).toMap
      ok(r.transact { m.update(Column(view(other), view(source))) })
      assertEquals(components(r, m).map(_._1), Vector(2, 3, 5))
      assertEquals(components(r, m).toMap.apply(2), before(2))
      ok(r.transact { m.update(View.empty) })
      assertEquals(r.transact { m.setViewport(ids.head, RowViewport.empty) }, CommitResult.Refused("virtual node is no longer mounted"))
    finally r.close()
  }

  test("a failed virtual source retires row Inputs and recovery creates fresh rows") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val source = owner.keyed(0 until 10)(identity); val bad = owner.cell(false)
      val error = new IllegalStateException("filter")
      val filtered = source.filterBy(bad.map(v => if v then throw error else 0))((q, a) => a >= q)
      var input: Inputs[Int] = null
      val row = Component[Int]("row") { (_, in) => input = in; View.empty }
      val m = r.mount(owner, VirtualRows(filtered, Lp(10), 0)(in => row(in)))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(10))) })
      val old = input
      ok(r.transact { bad.set(true) })
      assertEquals(read(r, m.snapshot), MountedSnapshot.Unavailable(error))
      assertEquals(r.transact { old.get }, CommitResult.Failed(KernelError.DisposedScope))
      ok(r.transact { bad.set(false) })
      assert(!(input eq old))
      assertEquals(components(r, m).map(_._1), Vector(0))
    finally r.close()
  }

  test("filtered changes capacity preflight rolls back query, revisions and visible rows") {
    val r = new HeadlessRuntime(eventCapacity = 1)
    try
      val owner = r.scope("source"); val source = owner.keyed(0 until 10)(identity); val query = owner.cell(0)
      val filtered = source.filterBy(query)((q, a) => a >= q)
      owner.on(filtered.changes)(_ => ()); owner.on(filtered.changes)(_ => ())
      val before = read(r, filtered.revision)
      assertEquals(r.transact { query.set(5) }, CommitResult.Refused("event delivery capacity full"))
      assertEquals(read(r, query), 0)
      assertEquals(read(r, filtered.revision), before)
      assertEquals(read(r, filtered.snapshot), (0 until 10).toVector)
    finally r.close()
  }

  test("hidden retained content keeps work alive; owner disposal rejects its completion") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("model"); val left = owner.ref(); val absent = owner.ref(); val target = owner.cell[NodeRef](left)
      var command: Command[Unit, Int] = null
      var result: Cell[Int] = null
      val editor = Component[Unit]("editor") { (scope, _) =>
        result = scope.cell(0)
        val work = Work(42)
        command = scope.command[Unit, Unit, Int]("load", "Load", WorkPolicy.download)(_ => Job((), work))((_, value) => result.set(value.outcome match
          case Outcome.Succeeded(n) => n
          case _ => -1))
        View.empty
      }
      val panel = owner.reparent(GlobalKey("editor"), editor(()))
      r.mount(owner, Column(DockOutlet(left), panel.at(target)))
      ok(r.transact { command.dispatch(()); target.set(absent) })
      val first = r.scheduler.tickets.head.id
      r.scheduler.run(first); ok(r.scheduler.deliver(first))
      assertEquals(read(r, result), 42)
      ok(r.transact { command.dispatch(()) })
      val late = r.scheduler.tickets.last.id
      r.scheduler.run(late)
      ok(r.transact { owner.dispose() })
      assert(r.scheduler.deliver(late).isInstanceOf[CommitResult.Refused[?]])
      assertEquals(r.transact { result.set(0) }, CommitResult.Failed(KernelError.DisposedScope))
    finally r.close()
  }

  test("virtual rows carry caller equality when Scala equality is coarser") {
    import RetainedVirtualSuite.Coarse
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list")
      given Equality[Coarse] = (a, b) => a.version == b.version
      val source = owner.keyed(Seq(new Coarse(1)))(_ => 1)
      var input: Inputs[Coarse] = null
      val row = Component[Coarse]("row") { (scope, in) => input = in; scope.effect(in.map(_.version))(_ => ()); View.empty }
      val m = r.mount(owner, VirtualRows(source, Lp(10), 0)(in => row(in)))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(10))) })
      val original = input
      ok(r.transact { source.replace(new Coarse(2)) })
      assert(input eq original)
      assertEquals(read(r, input.map(_.version)), 2)
    finally r.close()
  }

  test("virtual range protocol never requests snapshots and rejects stale windows atomically") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val original = owner.keyed(0 until 100)(identity); val stale = owner.cell(false)
      val source = new CollectionSource[Int, Int]:
        def revision = original.revision
        def changes = original.changes
        def window(bounds: Signal[IndexRange]) = original.window(bounds).zip(stale).map((window, bad) =>
          if bad then window.copy(revision = Revision(999)) else window)
        def size(using UiTxn) = original.size
        def keyAt(index: Int)(using UiTxn) = original.keyAt(index)
        def indexOf(key: Int)(using UiTxn) = original.indexOf(key)
        def get(key: Int)(using UiTxn) = original.get(key)
        def range(from: Int, until: Int)(using UiTxn) = original.range(from, until)
        def at(key: Int) = original.at(key)
        def snapshot = throw new IllegalStateException("snapshot forbidden")
        def keyedSnapshot = throw new IllegalStateException("keyed snapshot forbidden")
        def filterBy[Q](query: Signal[Q])(keep: (Q, Int) => Boolean) = original.filterBy(query)(keep)
      val row = Component[Int]("row")((_, _) => View.empty)
      val m = r.mount(owner, VirtualRows(source, Lp(10), 0)(in => row(in)))
      ok(r.transact { m.setViewport(RowViewport(Lp(10), Lp(20))) })
      val before = components(r, m)
      assertEquals(r.transact { stale.set(true); m.setViewport(RowViewport(Lp(20), Lp(20))) }, CommitResult.Failed(KernelError.StaleWindow))
      assertEquals(components(r, m), before)
      assertEquals(read(r, stale), false)
    finally r.close()
  }

  test("query delivered through retained component Inputs settles before descendant row lifetimes") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val query = owner.cell(0)
      val row = Component[Int]("row")((_, _) => View.empty)
      val list = Component[Int]("list") { (scope, input) =>
        val source = scope.keyed(0 until 30)(identity)
        val filtered = source.filterBy(input)((q, a) => a >= q)
        VirtualRows(filtered, Lp(10), 1)(in => row(in))
      }
      val m = r.mount(owner, list(query))
      ok(r.transact { m.setViewport(RowViewport(Lp(106), Lp(40))) })
      val before = components(r, m).filter(_._1 >= 9).toMap
      ok(r.transact { m.setViewport(RowViewport(Lp(73), Lp(10))); query.set(4) })
      assertEquals(components(r, m).map(_._1), Vector(4, 10, 11, 12, 13))
      components(r, m).filter(_._1 >= 10).foreach((key, id) => assertEquals(id, before(key)))
    finally r.close()
  }

  test("requested query Inputs settle across both sibling orders and separate roots") {
    for reverse <- Vector(false, true); separate <- Vector(false, true) do
      val r = new HeadlessRuntime
      try
        val owner = r.scope("list"); val query = owner.cell(0)
        var input: Inputs[Int] = null
        val earlyRoot = if separate && reverse then Some(r.mount(owner, View.empty)) else None
        val capture = Component[Int]("capture") { (_, in) => input = in; View.empty }
        val a = r.mount(owner, capture(query))
        val source = owner.keyed(0 until 30)(identity)
        val filtered = source.filterBy(input)((q, value) => value >= q)
        val row = Component[Int]("row")((_, _) => View.empty)
        val virtual = VirtualRows(filtered, Lp(10), 1)(in => row(in))
        val b = if separate then earlyRoot match
          case Some(root) => ok(r.transact { root.update(virtual) }); root
          case None => r.mount(owner, virtual)
        else
          ok(r.transact { a.update(if reverse then Column(virtual, capture(query)) else Column(capture(query), virtual)) })
          // Retargeting the old capture changes its owner; capture the new stable Inputs.
          val rebound = source.filterBy(input)((q, value) => value >= q)
          val newVirtual = VirtualRows(rebound, Lp(10), 1)(in => row(in))
          ok(r.transact { a.update(if reverse then Column(newVirtual, capture(query)) else Column(capture(query), newVirtual)) })
          a
        ok(r.transact { b.setViewport(RowViewport(Lp(106), Lp(40))) })
        val before = components(r, b).filter(_._1 >= 9).toMap
        ok(r.transact { b.setViewport(RowViewport(Lp(73), Lp(10))); query.set(4) })
        assertEquals(components(r, b).filter(_._1 >= 10).map(_._1), Vector(10, 11, 12, 13))
        components(r, b).filter(_._1 >= 10).foreach((key, id) => assertEquals(id, before(key)))
      finally r.close()
  }

  test("requested query predicate failure settles once and recovers across roots") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val query = owner.cell(0)
      var input: Inputs[Int] = null
      val capture = Component[Int]("capture") { (_, in) => input = in; View.empty }
      r.mount(owner, capture(query))
      val source = owner.keyed(0 until 30)(identity)
      val filtered = source.filterBy(input)((q, _) => if q == 1 then throw new IllegalStateException("filter") else true)
      val row = Component[Int]("row")((_, _) => View.empty)
      val m = r.mount(owner, VirtualRows(filtered, Lp(10), 0)(in => row(in)))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(20))) })
      ok(r.transact { query.set(1) })
      read(r, m.snapshot) match
        case MountedSnapshot.Unavailable(error: IllegalStateException) => assertEquals(error.getMessage, "filter")
        case other => fail(s"$other")
      ok(r.transact { query.set(0) })
      assertEquals(components(r, m).map(_._1), Vector(0, 1))
    finally r.close()
  }

  test("fresh requested query failures remain stable across unrelated materialization writes") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("list"); val query = owner.cell(0)
      var input: Inputs[Int] = null
      val capture = Component[Int]("capture") { (_, in) => input = in; View.empty }
      r.mount(owner, capture(query))
      val source = owner.keyed(0 until 30)(identity)
      val filtered = source.filterBy(input.map(q => if q == 1 then throw new IllegalStateException("query") else q))((q, a) => a >= q)
      val row = Component[Int]("row")((_, _) => View.empty)
      val m = r.mount(owner, VirtualRows(filtered, Lp(10), 0)(in => row(in)))
      ok(r.transact { m.setViewport(RowViewport(Lp(0), Lp(20))) })
      ok(r.transact { query.set(1) })
      read(r, m.snapshot) match
        case MountedSnapshot.Unavailable(error: IllegalStateException) => assertEquals(error.getMessage, "query")
        case other => fail(s"$other")
      ok(r.transact { query.set(2) })
      assertEquals(components(r, m).map(_._1), Vector(2, 3))
    finally r.close()
  }

  test("staged failures survive unrelated writes, retry on actual writes and follow branch switches") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("memo"); val bad = owner.cell(true); val unrelated = owner.cell(0); val selected = owner.cell(true)
      val failure = bad.map(v => if v then throw new IllegalStateException("failure") else 7)
      val switched = selected.flatMap(v => if v then failure else Signal.const(9))
      def error(value: Value[Int]): Throwable = value match
        case Value.Failed(e) => e
        case other => fail(s"$other")
      ok(r.transact {
        val first = error(switched.attempt.get)
        unrelated.set(1)
        assert(error(switched.attempt.get) eq first)
        bad.set(true)
        assert(!(error(switched.attempt.get) eq first))
        selected.set(false)
        assertEquals(switched.get, 9)
        bad.set(false)
        assertEquals(switched.get, 9)
        selected.set(true)
        assertEquals(switched.get, 7)
        UiTxn.attempt { bad.set(true); error(switched.attempt.get); UiTxn.refuse("undo") }
        assertEquals(switched.get, 7)
      })
    finally r.close()
  }

  test("revision-only speculative dependencies invalidate and scope retirement clears staged reads") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("memo"); val child = owner.child("child"); val value = child.cell(0)
      val revision = new Signal.Derived(Vector(value), reader => Value.Ok(reader.revision(value)), Equality.universal[Revision])
      ok(r.transact {
        assertEquals(revision.get, Revision.initial)
        value.set(1)
        assertEquals(revision.get, Revision.initial.next)
        UiTxn.attempt { value.set(0); assertEquals(revision.get, Revision.initial); UiTxn.refuse("undo") }
        assertEquals(revision.get, Revision.initial.next)
      })
      assertEquals(r.transact { value.get; child.dispose(); value.get }, CommitResult.Failed(KernelError.DisposedScope))
      assertEquals(read(r, value), 1)
    finally r.close()
  }
