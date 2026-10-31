package sirin.engine

import sirin.*
import scala.collection.mutable
import scala.compiletime.testing.typeChecks

object ReconciliationSuite:
  final case class Item(id: Int, label: String)
  final class MutationKey(val id: Int, attack: () => Unit):
    override def hashCode(): Int = id
    override def equals(other: Any): Boolean =
      attack()
      other match
        case key: MutationKey => key.id == id
        case _ => false

class ReconciliationSuite extends munit.FunSuite:
  import ReconciliationSuite.Item
  final case class RowState(input: Inputs[Item], draft: Cell[String], scope: Scope, shown: Signal[String])
  private def ok[A](r: HeadlessRuntime, s: Signal[A]): A = r.read(s) match
    case Value.Ok(v) => v
    case Value.Failed(e) => throw e
  private def components(snapshot: MountedSnapshot): Vector[(MountedIdentity, Any)] = snapshot match
    case MountedSnapshot.Component(id, props, child) => Vector((id, props)) ++ components(child)
    case MountedSnapshot.CustomLayout(_, children) => children.flatMap((_, child) => components(child))
    case MountedSnapshot.Rows(rows) => rows.flatMap((_, child) => components(child))
    case MountedSnapshot.Branch(_, _, child) => components(child)
    case _ => Vector.empty

  test("keyed replacement, reorder and insertion preserve Inputs, draft and instance; builder runs once") {
    val r = new HeadlessRuntime
    val owner = r.scope("workspace")
    val source = owner.keyed(Seq(Item(1, "one"), Item(2, "two")))(_.id)
    val built = mutable.ArrayBuffer.empty[RowState]
    val row = Component[Item]("row") { (scope, input) =>
      val draft = scope.cell("")
      val shown = input.zip(draft).map((item, d) => s"${item.label}:$d")
      built += RowState(input, draft, scope, shown)
      scope.effect(shown)(_ => ())
      View.empty
    }
    val mounted = r.mount(owner, ForEach(source)(input => row(input)))
    assertEquals(built.size, 2)
    val identities = components(ok(r, mounted.snapshot)).map((id, p) => p.asInstanceOf[Item].id -> id.instance).toMap
    r.transact { built.head.draft.set("local"); source.setAll(Seq(Item(2, "TWO"), Item(1, "ONE"), Item(3, "three"))) }
    assertEquals(built.size, 3)
    val after = components(ok(r, mounted.snapshot))
    assertEquals(after.map(_._2.asInstanceOf[Item].id), Vector(2, 1, 3))
    after.take(2).foreach((id, p) => assertEquals(id.instance, identities(p.asInstanceOf[Item].id)))
    assertEquals(r.transact { built.head.input.get.label }, CommitResult.Committed("ONE"))
    assertEquals(ok(r, built.head.shown), "ONE:local")
    assertEquals(r.diagnostics, Vector.empty)
  }

  test("Inputs is a real read-only facade and retires with its component") {
    assert(typeChecks("import sirin.*; def read(x: Inputs[Int])(using UiTxn) = x.get"))
    assert(!typeChecks("import sirin.*; def write(x: Inputs[Int])(using UiTxn) = x.set(1)"))
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    var input: Inputs[Int] = null
    val component = Component[Int]("component") { (_, in) => input = in; View.empty }
    val mounted = r.mount(owner, component(1))
    assert(!input.isInstanceOf[Cell[?]])
    r.transact { mounted.dispose() }
    assertEquals(r.transact { input.get }, CommitResult.Failed(KernelError.DisposedScope))
  }

  test("reallocated placements preserve definitions and stable Inputs while rebinding prop sources") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val a = owner.cell(1)
    val b = owner.cell(10)
    var mounts = 0
    var input: Inputs[Int] = null
    var draft: Cell[Int] = null
    val component = Component[Int]("component") { (scope, in) =>
      mounts += 1; input = in; draft = scope.cell(0); View.empty
    }
    val mounted = r.mount(owner, component(a))
    val oldInput = input
    val id = components(ok(r, mounted.snapshot)).head._1
    r.transact { draft.set(5); mounted.update(component(b)) }
    assert(input.eq(oldInput))
    assertEquals(mounts, 1)
    assertEquals(components(ok(r, mounted.snapshot)).head._1, id)
    assertEquals(r.transact { input.get }, CommitResult.Committed(10))
    r.transact { a.set(2) }
    assertEquals(r.transact { input.get }, CommitResult.Committed(10))
    r.transact { b.set(20) }
    assertEquals(r.transact { input.get }, CommitResult.Committed(20))
    assertEquals(ok(r, draft), 5)
  }

  test("same dependency reference retains state; equal distinct dependency or definition remounts") {
    final case class Dependency(label: String)
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val first = Dependency("same")
    val second = Dependency("same")
    val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
    val component = Component.withDeps[Dependency, Int]("dependent") { (_, scope, _) => drafts += scope.cell(0); View.empty }
    val mounted = r.mount(owner, component.bind(first)(1))
    val original = components(ok(r, mounted.snapshot)).head._1
    r.transact { drafts.head.set(9); mounted.update(component.bind(first)(2)) }
    assertEquals(drafts.size, 1)
    assertEquals(components(ok(r, mounted.snapshot)).head._1, original)
    r.transact { mounted.update(component.bind(second)(2)) }
    val replacement = components(ok(r, mounted.snapshot)).head._1
    assert(original.instance != replacement.instance)
    assert(original.definition.eq(replacement.definition))
    assertEquals(r.transact { drafts.head.set(1) }, CommitResult.Failed(KernelError.DisposedScope))
    val other = Component[Int]("other") { (scope, _) => drafts += scope.cell(0); View.empty }
    r.transact { mounted.update(other(2)) }
    assertEquals(drafts.size, 3)
    assert(!components(ok(r, mounted.snapshot)).head._1.definition.eq(original.definition))
  }

  test("key boundaries and ordinary owner changes keep independent local state") {
    val r = new HeadlessRuntime
    val a = r.scope("a")
    val b = r.scope("b")
    val items = a.cell[Seq[Item]](Seq(Item(1, "one")))
    val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
    val row = Component[Item]("row") { (s, _) => drafts += s.cell(0); View.empty }
    val builder: Inputs[Item] => View = input => row(input)
    val view = ForEach(items, (i: Item) => i.id)(builder)
    val left = r.mount(a, Column(view, view))
    assertEquals(drafts.size, 2)
    r.transact { drafts.head.set(7) }
    assertEquals(ok(r, drafts(1)), 0)
    val right = r.mount(b, view)
    assertEquals(drafts.size, 3)
    r.transact { left.dispose() }
    assertEquals(ok(r, drafts.last), 0)
    assertEquals(components(ok(r, right.snapshot)).size, 1)
  }

  test("removal invalidates old cells and readdition creates a fresh row; change-restore does not remount") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.keyed(Seq(Item(1, "one")))(_.id)
    val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
    val row = Component[Item]("row") { (s, _) => drafts += s.cell(0); View.empty }
    val mounted = r.mount(owner, ForEach(items)(input => row(input)))
    val first = components(ok(r, mounted.snapshot)).head._1.instance
    r.transact { items.setAll(Nil); items.setAll(Seq(Item(1, "one"))) }
    assertEquals(drafts.size, 1)
    assertEquals(components(ok(r, mounted.snapshot)).head._1.instance, first)
    r.transact { items.remove(1) }
    assertEquals(r.transact { drafts.head.set(1) }, CommitResult.Failed(KernelError.DisposedScope))
    r.transact { items.append(Item(1, "new")) }
    assertEquals(drafts.size, 2)
    assert(components(ok(r, mounted.snapshot)).head._1.instance != first)
  }

  test("keyed Switch keeps its branch and live Inputs; unkeyed changes remount") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val item = owner.cell(Item(1, "one"))
    val inputs = mutable.ArrayBuffer.empty[Inputs[Item]]
    val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
    val component = Component[Item]("branch") { (s, input) => inputs += input; drafts += s.cell(0); View.empty }
    var keyedBuilds = 0
    val keyed = r.mount(owner, Switch.keyed(item)(_.id) { input => keyedBuilds += 1; component(input) })
    val first = components(ok(r, keyed.snapshot)).head._1.instance
    r.transact { drafts.head.set(9); item.set(Item(1, "ONE")) }
    assertEquals(keyedBuilds, 1)
    assertEquals(r.transact { inputs.head.get.label }, CommitResult.Committed("ONE"))
    assertEquals(components(ok(r, keyed.snapshot)).head._1.instance, first)
    r.transact { item.set(Item(2, "two")) }
    assertEquals(keyedBuilds, 2)
    val plain = r.mount(owner, Switch(item)(value => component(value)))
    val plainFirst = components(ok(r, plain.snapshot)).head._1.instance
    r.transact { item.set(Item(2, "TWO")) }
    assertEquals(keyedBuilds, 2)
    assert(components(ok(r, plain.snapshot)).head._1.instance != plainFirst)
  }

  test("static positional and explicit keyed child identity survives description replacement") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
    val component = Component[Int]("child") { (s, _) => drafts += s.cell(0); View.empty }
    val root = r.mount(owner, Column(component(1).keyed("a"), component(2).keyed("b")))
    val before = components(ok(r, root.snapshot)).map((id, p) => p.asInstanceOf[Int] -> id.instance).toMap
    r.transact { drafts.head.set(8); root.update(Column(component(2).keyed("b"), component(1).keyed("a"))) }
    assertEquals(drafts.size, 2)
    assertEquals(components(ok(r, root.snapshot)).map(_._1.instance), Vector(before(2), before(1)))
    assertEquals(ok(r, drafts.head), 8)
  }

  test("duplicate keys are rejected before row builders and restore source, rows and lifetimes") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.cell[Seq[Item]](Seq(Item(1, "one")))
    var builds = 0
    val mounted = r.mount(owner, ForEach(items, (i: Item) => i.id) { _ => builds += 1; View.empty })
    val snapshot = ok(r, mounted.snapshot)
    val before = r.lifetimes
    assertEquals(r.transact { items.set(Seq(Item(2, "two"), Item(2, "again"))) }, CommitResult.Failed(KernelError.DuplicateKey))
    assertEquals(builds, 1)
    assertEquals(ok(r, mounted.snapshot), snapshot)
    assertEquals(r.lifetimes, before)
    assertEquals(ok(r, items), Seq(Item(1, "one")))
  }

  test("scope refusal restores root replacement and row Inputs without tentative rebuilding") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.keyed(Seq(Item(1, "one")))(_.id)
    var builds = 0
    val component = Component[Item]("row") { (_, _) => builds += 1; View.empty }
    val mounted = r.mount(owner, ForEach(items)(in => component(in)))
    val before = ok(r, mounted.snapshot)
    val lifetime = r.lifetimes
    r.transact {
      UiTxn.attempt { items.setAll(Seq(Item(2, "two"))); mounted.update(View.empty); UiTxn.refuse("restore") }
    }
    assertEquals(ok(r, mounted.snapshot), before)
    assertEquals(r.lifetimes, lifetime)
    assertEquals(builds, 1)
  }

  test("builder defect rolls back prior candidate edits and compensates only new leases") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.cell[Seq[Item]](Seq(Item(1, "one")))
    val boom = new RuntimeException("builder")
    val releases = mutable.ArrayBuffer.empty[String]
    val component = Component[Item]("row") { (s, input) =>
      s.onDispose(() => releases += "old")
      s.effect(input)(_ => ())
      View.empty
    }
    val builder: Inputs[Item] => View = in => component(in)
    val mounted = r.mount(owner, ForEach(items, (i: Item) => i.id)(builder))
    val before = ok(r, mounted.snapshot)
    val lifetime = r.lifetimes
    val broken = Component[Int]("broken") { (s, _) => s.onDispose(() => releases += "new"); throw boom }
    assertEquals(r.transact { items.set(Nil); mounted.update(Column(component(Item(3, "tentative")), broken(1))) }, CommitResult.Failed(boom))
    assertEquals(ok(r, mounted.snapshot), before)
    assertEquals(r.lifetimes, lifetime)
    assertEquals(releases.count(_ == "new"), 1)
    // One tentative successful sibling is compensated; the original stays live.
    assertEquals(releases.count(_ == "old"), 1)
    r.close()
    assertEquals(releases.count(_ == "old"), 2)
  }

  test("construction permits owned declarations but caught captured writes and foreign owners fail") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val model = owner.cell(0)
    var capability: UiTxn = null
    val bad = Component[Int]("bad") { (s, _) =>
      s.cell(1)
      try model.set(9)(using capability) catch case _: KernelError => ()
      View.empty
    }
    val before = r.lifetimes
    assertEquals(r.transact { capability = summon[UiTxn]; r.mount(owner, bad(1)); () }, CommitResult.Failed(KernelError.ReadOnlyPhase))
    assertEquals(r.lifetimes, before)
    assertEquals(ok(r, model), 0)
    val wrongOwner = Component[Int]("bad-owner") { (_, _) =>
      try owner.cell(9) catch case _: KernelError => ()
      View.empty
    }
    assertEquals(r.transact { r.mount(owner, wrongOwner(1)); () }, CommitResult.Failed(KernelError.ReadOnlyPhase))
  }

  test("mixed list changes publish source, order, Inputs and derived values coherently") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.keyed(Seq(Item(1, "one"), Item(2, "two")))(_.id)
    val component = Component[Item]("row") { (s, input) => s.effect(input)(_ => ()); View.empty }
    val mounted = r.mount(owner, ForEach(items)(in => component(in)))
    val events = mutable.ArrayBuffer.empty[Vector[Item]]
    r.read(items.snapshot)
    r.observe(mounted.snapshot) {
      case Value.Ok(snapshot) =>
        val props = components(snapshot).map(_._2.asInstanceOf[Item])
        assertEquals(ok(r, items.snapshot).toVector, props)
        events += props
      case Value.Failed(e) => throw e
    }
    r.transact { items.setAll(Seq(Item(2, "TWO"), Item(3, "three"))) }
    assertEquals(events.toVector, Vector(Vector(Item(1, "one"), Item(2, "two")), Vector(Item(2, "TWO"), Item(3, "three"))))
    assertEquals(r.diagnostics, Vector.empty)
  }

  test("failed props retire content, expose Unavailable and recover with a fresh lifetime") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val divisor = owner.cell(1)
    val props = divisor.map(10 / _)
    val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
    val component = Component[Int]("row") { (s, _) => drafts += s.cell(0); View.empty }
    val mounted = r.mount(owner, component(props))
    val first = components(ok(r, mounted.snapshot)).head._1.instance
    r.transact { divisor.set(0) }
    assert(ok(r, mounted.snapshot).isInstanceOf[MountedSnapshot.Unavailable])
    assertEquals(r.transact { drafts.head.set(1) }, CommitResult.Failed(KernelError.DisposedScope))
    r.transact { divisor.set(2) }
    assertEquals(drafts.size, 2)
    assert(components(ok(r, mounted.snapshot)).head._1.instance != first)
    assertEquals(components(ok(r, mounted.snapshot)).head._2, 5)
  }

  test("custom source equality cutoff is honored by structural reconciliation") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val source = owner.cell(Item(1, "one"))(using (a, b) => a.id == b.id)
    var input: Inputs[Item] = null
    val component = Component[Item]("row") { (_, in) => input = in; View.empty }
    val root = r.mount(owner, component(source))
    r.transact { source.set(Item(1, "suppressed")) }
    assertEquals(r.transact { input.get }, CommitResult.Committed(Item(1, "one")))
    assertEquals(components(ok(r, root.snapshot)).head._2, Item(1, "one"))
  }

  test("keyed collection operations have transactional validation and committed change events") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val source = owner.keyed(Seq(Item(1, "one"), Item(2, "two")))(_.id)
    r.transact { source.insert(1, Item(3, "three")); source.move(1, 2); source.replace(Item(2, "TWO")); source.update(3)(_.copy(label = "THREE")) }
    assertEquals(ok(r, source.snapshot), IndexedSeq(Item(3, "THREE"), Item(2, "TWO"), Item(1, "one")))
    val before = ok(r, source.snapshot)
    assertEquals(r.transact {
      try source.update(3)(_.copy(id = 8)) catch case _: KernelError => ()
      source.remove(1)
    }, CommitResult.Failed(KernelError.InvalidKeyChange))
    assertEquals(ok(r, source.snapshot), before)
    assertEquals(r.transact { source.replace(Item(99, "missing")) }, CommitResult.Refused("missing collection key"))
    val batches = mutable.ArrayBuffer.empty[Changes[Int]]
    owner.on(source.changes)(batches += _)
    assertEquals(r.transact { source.replace(Item(2, "updated")) }, CommitResult.Committed(()))
    r.drainEvents()
    assertEquals(batches.toVector.map(_.deltas.toVector), Vector(Vector(Delta.Replaced(2))))
    val query = owner.cell("TH")
    val filtered = source.filterBy(query)((q, item) => item.label.startsWith(q))
    assertEquals(ok(r, filtered.snapshot), IndexedSeq(Item(3, "THREE")))
  }

  test("structural depth is budgeted and failed tentative trees retire cleanly") {
    val r = new HeadlessRuntime(settleBudget = 5)
    val owner = r.scope("owner")
    lazy val recursive: Component[Unit] = Component[Unit]("recursive") { (_, _) => recursive(()) }
    val before = r.lifetimes
    assertEquals(r.transact { r.mount(owner, recursive(())); () }, CommitResult.Failed(KernelError.BudgetExceeded))
    assertEquals(r.lifetimes, before)
  }

  test("repeated row churn and root disposal retire structural ownership") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.keyed(Seq(Item(1, "one")))(_.id)
    val baseline = r.lifetimes
    val component = Component[Item]("row") { (s, input) => s.cell(0); s.effect(input)(_ => ()); View.empty }
    val root = r.mount(owner, ForEach(items)(in => component(in)))
    val count = r.lifetimes
    (1 to 50).foreach { n =>
      r.transact { items.setAll(Seq(Item(n, s"row$n"))) }
      r.flushEffects(1000)
      assertEquals(r.lifetimes.scopes.size, count.scopes.size)
      assertEquals(r.lifetimes.cells.size, count.cells.size)
      assertEquals(r.lifetimes.nodes, count.nodes)
      assertEquals(r.lifetimes.mounts, 1)
    }
    r.transact { root.dispose() }
    r.flushEffects()
    // r.mount's output is not globally pinned in this test.
    assertEquals(r.lifetimes, baseline)
  }

  test("Inputs and derived reads follow staged keyed replacements and attempt restoration") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.keyed(Seq(Item(1, "one")))(_.id)
    var input: Inputs[Item] = null
    var label: Signal[String] = null
    val row = Component[Item]("row") { (_, in) => input = in; label = in.map(_.label); View.empty }
    r.mount(owner, ForEach(items)(in => row(in)))
    assertEquals(r.transact {
      assertEquals(label.get, "one")
      items.replace(Item(1, "ONE"))
      assertEquals(input.get.label, "ONE")
      assertEquals(label.get, "ONE")
      UiTxn.attempt {
        items.replace(Item(1, "temporary"))
        assertEquals(label.get, "temporary")
        UiTxn.refuse("restore")
      }
      assertEquals(label.get, "ONE")
    }, CommitResult.Committed(()))
  }

  test("memoized Inputs follow direct and nested description rebinding before commit") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val a = owner.cell(1)
    val b = owner.cell(2)
    val inputs = mutable.ArrayBuffer.empty[Inputs[Int]]
    val row = Component[Int]("row") { (_, in) => inputs += in; View.empty }
    val direct = r.mount(owner, row(a))
    val nested = r.mount(owner, Column(row(a).keyed("keep")))
    assertEquals(r.transact {
      assertEquals(inputs(0).get, 1)
      assertEquals(inputs(1).get, 1)
      UiTxn.attempt {
        direct.update(row(b))
        nested.update(Column(row(b).keyed("keep")))
        assertEquals(inputs(0).get, 2)
        assertEquals(inputs(1).get, 2)
        UiTxn.refuse("restore")
      }
      assertEquals(inputs(0).get, 1)
      assertEquals(inputs(1).get, 1)
      direct.update(row(b))
      nested.update(Column(row(b).keyed("keep")))
      assertEquals(inputs(0).get, 2)
      assertEquals(inputs(1).get, 2)
    }, CommitResult.Committed(()))
    assertEquals(inputs.size, 2)
  }

  test("ancestor rekey, removal and definition replacement invalidate descendant staged Inputs") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    var input: Inputs[Int] = null
    val child = Component[Int]("child") { (_, in) => input = in; View.empty }
    val parent = Component[Int]("parent") { (_, in) => child(in) }
    val other = Component[Int]("other") { (_, in) => child(in) }
    val mounted = r.mount(owner, Column(parent(1).keyed("a")))
    val before = ok(r, mounted.snapshot)
    val replacements = Vector[View](Column(parent(1).keyed("b")), Column(), Column(other(1).keyed("a")))
    replacements.foreach { next =>
      assertEquals(r.transact {
        mounted.update(next)
        input.get
      }, CommitResult.Failed(KernelError.DisposedScope))
      assertEquals(ok(r, mounted.snapshot), before)
    }
  }

  test("row and keyed branch Inputs reject staged boundary rekeys and removed keys") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.cell[Seq[Item]](Seq(Item(1, "one")))
    val value = owner.cell(Item(1, "one"))
    val captured = mutable.ArrayBuffer.empty[Inputs[Item]]
    val component = Component[Item]("child") { (_, in) => captured += in; View.empty }
    val rows = r.mount(owner, ForEach(items, (i: Item) => i.id)(in => component(in)).keyed("a"))
    val branch = r.mount(owner, Switch.keyed(value)(_.id)(in => component(in)).keyed("a"))
    assertEquals(r.transact { rows.update(ForEach(items, (i: Item) => i.id)(in => component(in)).keyed("b")); captured(0).get }, CommitResult.Failed(KernelError.DisposedScope))
    assertEquals(r.transact { branch.update(Switch.keyed(value)(_.id)(in => component(in)).keyed("b")); captured(1).get }, CommitResult.Failed(KernelError.DisposedScope))
    assertEquals(r.transact { items.set(Nil); captured(0).get }, CommitResult.Failed(KernelError.DisposedScope))
    assertEquals(r.transact { value.set(Item(2, "two")); captured(1).get }, CommitResult.Failed(KernelError.DisposedScope))
  }

  test("recreated structural lambdas retain rows and keyed branches at stable placements") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val items = owner.cell[Seq[Item]](Seq(Item(1, "one")))
    val value = owner.cell(Item(1, "one"))
    val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
    val component = Component[Item]("row") { (s, _) => drafts += s.cell(0); View.empty }
    def list: View = ForEach(items, (i: Item) => i.id)(in => component(in))
    def switch: View = Switch.keyed(value)(_.id)(in => component(in))
    val a = r.mount(owner, list)
    val b = r.mount(owner, switch)
    val first = components(ok(r, a.snapshot)).head._1.instance
    val second = components(ok(r, b.snapshot)).head._1.instance
    r.transact { drafts.head.set(9); a.update(list); b.update(switch) }
    assertEquals(drafts.size, 2)
    assertEquals(components(ok(r, a.snapshot)).head._1.instance, first)
    assertEquals(components(ok(r, b.snapshot)).head._1.instance, second)
    assertEquals(ok(r, drafts.head), 9)
  }

  test("filtered collections and structural snapshots preserve signed zero equality") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val source = owner.keyed(Seq(0.0))(_ => 1)
    val filtered = source.filterBy(Signal.const(true))((keep, _) => keep)
    var input: Inputs[Double] = null
    val row = Component[Double]("double") { (_, in) => input = in; View.empty }
    val mounted = r.mount(owner, ForEach(filtered)(in => row(in)))
    r.transact { source.replace(-0.0) }
    assertEquals(1.0 / ok(r, filtered.snapshot).head, Double.NegativeInfinity)
    assertEquals(r.transact { 1.0 / input.get }, CommitResult.Committed(Double.NegativeInfinity))
    assertEquals(1.0 / components(ok(r, mounted.snapshot)).head._2.asInstanceOf[Double], Double.NegativeInfinity)
    val value = owner.cell(0.0)
    val plain = r.mount(owner, Switch(value)(_ => View.empty))
    r.transact { value.set(-0.0) }
    ok(r, plain.snapshot) match
      case MountedSnapshot.Branch(key, _, _) => assertEquals(1.0 / key.asInstanceOf[Double], Double.NegativeInfinity)
      case _ => fail("missing branch")
  }

  test("component snapshot equality preserves declared custom and signed zero semantics") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val value = owner.cell(0.0)
    val row = Component[Double]("double") { (_, _) => View.empty }
    val root = r.mount(owner, row(value))
    r.transact { value.set(-0.0) }
    assertEquals(1.0 / components(ok(r, root.snapshot)).head._2.asInstanceOf[Double], Double.NegativeInfinity)
  }

  test("user key equality cannot mutate captured transactions even if the rejection is caught") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val target = owner.cell(0)
    var txn: UiTxn = null
    var attack = false
    def key(): ReconciliationSuite.MutationKey = new ReconciliationSuite.MutationKey(1, () =>
      if attack then
        try target.set(99)(using txn) catch case _: KernelError => ())
    final case class Value(key: ReconciliationSuite.MutationKey, label: String)
    val original = key()
    val source = owner.keyed(Seq(Value(original, "one")))(_.key)
    val attempts: Vector[UiTxn ?=> Unit] = Vector(
      { source.get(key()); () }, { source.indexOf(key()); () },
      source.remove(key()), source.move(key(), 0), source.replace(Value(key(), "new")),
      source.update(key())(_.copy(label = "new")))
    attempts.foreach { action =>
      val result = r.transact { txn = summon[UiTxn]; attack = true; action }
      attack = false
      assertEquals(result, CommitResult.Failed(KernelError.ReadOnlyPhase))
      assertEquals(ok(r, target), 0)
    }
  }

  test("structural schemas remount incompatible row and branch input types without unsafe delivery") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    var integers: Inputs[Int] = null
    var strings: Inputs[String] = null
    val intRow = Component[Int]("int") { (_, in) => integers = in; View.empty }
    val stringRow = Component[String]("string") { (_, in) => strings = in; View.empty }
    val rows = r.mount(owner, ForEach(Signal.const[Seq[Int]](Seq(1)), (_: Int) => 1)(in => intRow(in)))
    r.transact { rows.update(ForEach(Signal.const[Seq[String]](Seq("one")), (_: String) => 1)(in => stringRow(in))) }
    assertEquals(r.transact { strings.get }, CommitResult.Committed("one"))
    assertEquals(r.transact { integers.get }, CommitResult.Failed(KernelError.DisposedScope))
    val branch = r.mount(owner, Switch.keyed(Signal.const(1))(_ => 1)(in => intRow(in)))
    r.transact { branch.update(Switch.keyed(Signal.const("one"))(_ => 1)(in => stringRow(in))) }
    assertEquals(r.transact { strings.get }, CommitResult.Committed("one"))
    assertEquals(r.transact { integers.get }, CommitResult.Failed(KernelError.DisposedScope))
    assert(summon[SchemaId[Vector[Int]]].value != summon[SchemaId[Vector[String]]].value)
  }

  test("Inputs.attempt exposes staged structural source failures as data without poisoning") {
    val r = new HeadlessRuntime
    val owner = r.scope("owner")
    val divisor = owner.cell(1)
    val rows = divisor.map(n => Seq(Item(1, (10 / n).toString)))
    val inputs = mutable.ArrayBuffer.empty[Inputs[Item]]
    val row = Component[Item]("row") { (_, in) => inputs += in; View.empty }
    r.mount(owner, ForEach(rows, (item: Item) => item.id)(in => row(in)))
    val parent = Component[Item]("parent") { (_, in) => row(in) }
    r.mount(owner, parent(divisor.map(n => Item(1, (10 / n).toString))))
    assertEquals(r.transact {
      divisor.set(0)
      assert(inputs(0).attempt.get.isInstanceOf[Value.Failed[?]])
      assert(inputs(1).attempt.get.isInstanceOf[Value.Failed[?]])
      divisor.set(2)
      assertEquals(inputs(0).get.label, "5")
      assertEquals(inputs(1).get.label, "5")
    }, CommitResult.Committed(()))
  }

  test("schema derivation rejects unresolved nested generics and abstract members with positive controls") {
    assert(!typeChecks("import sirin.*; def render[A](s: Signal[Vector[A]]): View = Switch(s)(_ => View.empty)"))
    assert(typeChecks("import sirin.*; def render[A](s: Signal[Vector[A]])(using SchemaId[Vector[A]]): View = Switch(s)(_ => View.empty)"))
    assert(!typeChecks("import sirin.*; def render[A](s: Signal[A | String]): View = Switch(s)(_ => View.empty)"))
    assert(typeChecks("import sirin.*; def render[A](s: Signal[A | String])(using SchemaId[A | String]): View = Switch(s)(_ => View.empty)"))
    assert(!typeChecks("import sirin.*; trait Abstract { type A }; def render(o: Abstract)(s: Signal[o.A]): View = Switch(s)(_ => View.empty)"))
    assert(typeChecks("import sirin.*; trait Abstract { type A }; def render(o: Abstract)(s: Signal[o.A])(using SchemaId[o.A]): View = Switch(s)(_ => View.empty)"))
    assert(!typeChecks("import sirin.*; class Owner { class Row }; def render(o: Owner)(s: Signal[o.Row]): View = Switch(s)(_ => View.empty)"))
    assert(typeChecks("import sirin.*; class Owner { class Row }; def render(o: Owner)(s: Signal[o.Row])(using SchemaId[o.Row]): View = Switch(s)(_ => View.empty)"))
    assert(!typeChecks("import sirin.*; class Owner { class Row }; def render(): View = { val o = new Owner; Switch(Signal.const(new o.Row))(_ => View.empty) }"))
    assert(typeChecks("import sirin.*; def render(s: Signal[sirin.engine.ReconciliationSuite.Item]): View = Switch(s)(_ => View.empty)"))
    assert(!typeChecks("import sirin.*; def render(): View = { object Local { class Row }; Switch(Signal.const(new Local.Row))(_ => View.empty) }"))
  }
