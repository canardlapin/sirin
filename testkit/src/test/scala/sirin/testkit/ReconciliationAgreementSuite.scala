package sirin.testkit

import sirin.*
import sirin.engine.*
import sirin.testkit.ReconciliationModel.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.collection.mutable

class ReconciliationAgreementSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(300)
  private val item = for key <- Gen.choose(0, 4); label <- Gen.choose(-3, 3) yield Item(key, label)
  private val items = Gen.frequency(8 -> Gen.listOf(item).map(_.take(5).distinctBy(_.key).toVector), 2 -> Gen.listOfN(3, item).map(_.toVector))
  private val simple: Gen[Op] = Gen.frequency(
    6 -> items.map(Op.Assign(_)),
    3 -> (for key <- Gen.choose(0, 4); value <- Gen.choose(-3, 3) yield Op.Draft(key, value)),
    2 -> Gen.choose(0, 4).map(Op.Read(_)),
    2 -> Gen.const(Op.Rebuild), 1 -> Gen.const(Op.Refuse), 1 -> Gen.const(Op.CaughtDefect))
  private val op = Gen.frequency(7 -> simple, 3 -> (for inner <- Gen.listOfN(3, simple); refuse <- Gen.oneOf(true, false) yield Op.Attempt(inner.toVector, refuse)))
  private val step = Gen.frequency(
    7 -> Gen.listOfN(3, op).map(ops => Step.Batch(ops.toVector)),
    2 -> Gen.choose(1, 3).map(Step.Drain(_)),
    2 -> Gen.choose(0, 40).map(Step.Deliver(_)))
  private val program = Gen.listOfN(40, step).map(_.toVector)

  private def componentRows(snapshot: MountedSnapshot): Vector[(MountedIdentity, Item)] = snapshot match
    case MountedSnapshot.Component(identity, props, child) => Vector(identity -> props.asInstanceOf[Item]) ++ componentRows(child)
    case MountedSnapshot.Rows(entries) => entries.flatMap((_, child) => componentRows(child))
    case MountedSnapshot.Group(children, _, _, _) => children.flatMap(componentRows)
    case MountedSnapshot.Branch(_, _, child) => componentRows(child)
    case _ => Vector.empty

  private def checkProgram(steps: Vector[Step]): Unit =
    val r = new HeadlessRuntime
    val owner = r.scope("workspace")
    val source = owner.keyed(Vector.empty[Item])(_.key)
    val trace = mutable.ArrayBuffer.empty[Trace]
    final case class Record(generation: Int, scope: r.OwnedScope, input: Inputs[Item], draft: Cell[Int], deliver: () => CommitResult[Unit])
    val records = mutable.ArrayBuffer.empty[Record]
    val row = Component[Item]("row") { (scope, input) =>
      val owned = scope.asInstanceOf[r.OwnedScope]
      val generation = records.size + 1
      val draft = scope.cell(0)
      val shown = input.zip(draft)
      owned.observe(shown) {
        case Value.Ok((item, d)) => trace += Trace.Observed(generation, item.key, item.label, d)
        case Value.Failed(error) => throw error
      }
      scope.effect(shown)((item, d) => trace += Trace.Effect(generation, item.key, item.label, d))
      scope.onDispose(() => trace += Trace.Released(generation))
      records += Record(generation, owned, input, draft, owned.callback(draft.update(_ + 1)))
      View.empty
    }
    def description: View = ForEach(source)(input => row(input))
    val mounted = r.mount(owner, description)
    r.observe(mounted.snapshot) {
      case Value.Ok(snapshot) =>
        trace += Trace.Order(componentRows(snapshot).map { (identity, item) =>
          val generation = records.find(_.scope.id == identity.instance).get.generation
          (item.key, item.label, generation)
        })
      case Value.Failed(error) => throw error
    }
    var byKey = Map.empty[Int, Record]
    var reads = Vector.empty[Int]
    def applyOps(ops: Vector[Op])(using UiTxn): Unit = ops.foreach {
      case Op.Assign(items) => source.setAll(items)
      case Op.Draft(key, value) => byKey.getOrElse(key, throw KernelError.DisposedScope).draft.set(value)
      case Op.Read(key) => reads :+= byKey.getOrElse(key, throw KernelError.DisposedScope).input.get.label
      case Op.Attempt(inner, refuse) =>
        UiTxn.attempt { applyOps(inner); if refuse then UiTxn.refuse("scoped") }
        ()
      case Op.Rebuild => mounted.update(description)
      case Op.Refuse => UiTxn.refuse("root")
      case Op.CaughtDefect =>
        try UiTxn.attempt { throw new IllegalArgumentException("poison") }
        catch case _: IllegalArgumentException => ()
    }
    def status(result: CommitResult[?]): Result = result match
      case CommitResult.Committed(_) => Result.Accepted
      case CommitResult.Refused(_) => Result.Refused
      case CommitResult.Failed(_) => Result.Failed
      case CommitResult.Joined(_) => throw new IllegalStateException("unexpected joined transaction")
    var model = ReconciliationModel.initial
    steps.zipWithIndex.foreach { (step, index) =>
      reads = Vector.empty
      step match
        case Step.Batch(ops) =>
          val (next, receipt) = ReconciliationModel.transact(model, ops)
          assertEquals(status(r.transact { applyOps(ops) }), receipt.result, s"step $index: $step")
          assertEquals(reads, receipt.reads)
          model = next
        case Step.Drain(budget) =>
          val (next, count) = ReconciliationModel.drain(model, budget)
          val result = r.flushEffects(budget)
          assertEquals(result.executed, count)
          assertEquals(result.pending, next.pending.size)
          assertEquals(result.failures, Vector.empty)
          model = next
        case Step.Deliver(generation) =>
          val (next, receipt) = ReconciliationModel.deliver(model, generation)
          val actual = records.find(_.generation == generation).fold[CommitResult[Unit]](CommitResult.Refused("missing"))(_.deliver())
          assertEquals(status(actual), receipt.result)
          model = next
      assertEquals(trace.toVector, model.trace, s"trace at step $index: $step")
      val live = records.filterNot(_.scope.isDisposed)
      byKey = live.map { record =>
        val item = record.scope.read(record.input) match
          case Value.Ok(v) => v
          case Value.Failed(error) => throw error
        (item.key, record)
      }.toMap
      assertEquals(live.map(_.generation).toSet, model.live.map(_.generation).toSet)
      assertEquals(r.lifetimes.pendingEffects, model.pending.size)
      assertEquals(r.lifetimes.mounts, 1)
      assertEquals(owner.read(source.snapshot), Value.Ok(model.source: IndexedSeq[Item]))
      assertEquals(owner.read(source.revision), Value.Ok(Revision(model.revision)))
      model.live.foreach { expected =>
        val record = byKey(expected.item.key)
        assertEquals(record.generation, expected.generation)
        assert(r.lifetimes.scopes(record.scope.id))
        assertEquals(record.scope.read(record.input), Value.Ok(expected.item))
        assertEquals(record.scope.read(record.draft), Value.Ok(expected.draft))
      }
      assertEquals(r.diagnostics, Vector.empty)
    }
    r.close()

  property("keyed reconciliation agrees on order, live props, drafts, generations, observations, effects and releases") {
    Prop.forAll(program) { steps => checkProgram(steps); true }
  }

  test("reference cases include same-key replacement, staged reads, reorder, rebuilt lambdas and stale delivery") {
    checkProgram(Vector(
      Step.Batch(Vector(Op.Assign(Vector(Item(1, 1), Item(2, 2))))),
      Step.Batch(Vector(Op.Draft(1, 9), Op.Assign(Vector(Item(2, 20), Item(1, 10))), Op.Read(1), Op.Rebuild)),
      Step.Batch(Vector(Op.Attempt(Vector(Op.Assign(Vector(Item(1, 100))), Op.Read(1)), true))),
      Step.Drain(1), Step.Batch(Vector(Op.Assign(Vector(Item(2, 20))))), Step.Deliver(1),
      Step.Batch(Vector(Op.Assign(Vector(Item(1, 10), Item(2, 20))))), Step.Deliver(1), Step.Deliver(3), Step.Drain(100)))
  }
