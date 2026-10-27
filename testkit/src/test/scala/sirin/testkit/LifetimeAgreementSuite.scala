package sirin.testkit

import sirin.*
import sirin.engine.*
import sirin.testkit.LifetimeModel.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.collection.mutable

class LifetimeAgreementSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(300)
  private val small = Gen.choose(-3, 3)
  private def step(n: Int): Gen[Step] =
    val token = Gen.choose(1, n)
    val parent = if n == 1 then Gen.const(0) else Gen.frequency(7 -> Gen.const(0), 3 -> Gen.choose(1, n - 1))
    val mount = for p <- parent; v <- small yield Op.Mount(n, p, v)
    val global = for i <- Gen.choose(0, 2); v <- small yield Op.Global(i, v)
    Gen.frequency(
      3 -> (for m <- mount; g <- global yield Step.Batch(Vector(g, m))),
      3 -> (for m <- mount; v <- small yield Step.Batch(Vector(m, Op.Draft(n, v)))),
      2 -> (for p <- parent; initial <- small; v <- small yield Step.Batch(Vector(Op.Mount(n, p, initial), Op.Draft(n, v), Op.Draft(n, initial)))),
      2 -> (for m <- mount; v <- small; refuse <- Gen.oneOf(true, false) yield Step.Batch(Vector(Op.Attempt(Vector(m, Op.Draft(n, v)), refuse)))),
      3 -> (for m <- mount; g <- global; refuse <- Gen.oneOf(true, false) yield Step.Batch(Vector(g, Op.Attempt(Vector(m, g), refuse)))),
      2 -> (for m <- mount; g <- global yield Step.Batch(Vector(m, g, Op.Refuse))),
      2 -> (for m <- mount; g <- global yield Step.Batch(Vector(m, Op.CaughtDefect, g))),
      4 -> (for t <- token; g <- global; reject <- Gen.oneOf(true, false) yield Step.Batch(Vector(g, Op.Attempt(Vector(Op.Dispose(t)), reject)))),
      2 -> (for t <- token; v <- small; g <- global yield Step.Batch(Vector(g, Op.Draft(t, v)))),
      3 -> global.map(g => Step.Batch(Vector(g))),
      3 -> Gen.choose(1, 3).map(Step.Drain(_)),
      3 -> token.map(Step.Deliver(_)))
  private val program = (1 to 50).foldLeft(Gen.const(Vector.empty[Step]))((acc, n) => for a <- acc; s <- step(n) yield a :+ s)

  private def checkProgram(steps: Vector[Step]): Unit =
    val r = new HeadlessRuntime
    val root = r.scope("workspace")
    val globals = LifetimeModel.initial.globals.map(root.cell(_))
    val globalIds = r.lifetimes.cells
    val scopes = mutable.Map.empty[Int, r.OwnedScope]
    val drafts = mutable.Map.empty[Int, Cell[Int]]
    val cellIds = mutable.Map.empty[Int, Long]
    val callbacks = mutable.Map.empty[Int, () => CommitResult[Unit]]
    val trace = mutable.ArrayBuffer.empty[Trace]
    def applyOps(ops: Vector[Op])(using UiTxn): Unit = ops.foreach {
      case Op.Global(i, v) => globals(i).set(v)
      case Op.Draft(t, v) => drafts.get(t).fold(throw KernelError.DisposedScope)(_.set(v))
      case Op.Mount(t, p, v) =>
        val parent = if p == 0 then root else scopes.getOrElse(p, throw KernelError.DisposedScope)
        val s = parent.child("row")
        scopes(t) = s
        val before = r.lifetimes.cells
        val draft = s.cell(v)
        drafts(t) = draft
        cellIds(t) = (r.lifetimes.cells -- before).head
        s.onDispose(() => trace += Trace.Released(t))
        val selected = globals(0).flatMap(n => if n % 2 == 0 then globals(1).map(identity) else globals(2).map(identity))
        val value = selected.zip(draft).map(_ + _)
        s.observe(value) {
          case Value.Ok(v) => trace += Trace.Observed(t, v)
          case Value.Failed(e) => throw e
        }
        s.effect(value)(v => trace += Trace.EffectRan(t, v))
        callbacks(t) = s.callback(draft.update(_ + 1))
      case Op.Dispose(t) => scopes.get(t).foreach(_.dispose())
      case Op.Attempt(inner, refuse) =>
        UiTxn.attempt { applyOps(inner); if refuse then UiTxn.refuse("scoped") }
        ()
      case Op.Refuse => UiTxn.refuse("root")
      case Op.CaughtDefect =>
        try UiTxn.attempt { throw new IllegalArgumentException("poison") }
        catch case _: IllegalArgumentException => ()
    }
    def status(result: CommitResult[?]): Result = result match
      case CommitResult.Committed(_) => Result.Accepted
      case CommitResult.Refused(_) => Result.Refused
      case CommitResult.Failed(_) => Result.Failed
      case CommitResult.Joined(_) => throw new IllegalStateException("unexpected nested delivery")
    var model = LifetimeModel.initial
    steps.zipWithIndex.foreach { (step, index) =>
      step match
        case Step.Batch(ops) =>
          val (next, expected) = LifetimeModel.transact(model, ops)
          assertEquals(status(r.transact { applyOps(ops) }), expected, s"step $index: $step")
          model = next
        case Step.Drain(budget) =>
          val (next, executed) = LifetimeModel.drain(model, budget)
          val result = r.flushEffects(budget)
          assertEquals(result.executed, executed)
          assertEquals(result.pending, next.pending.size)
          assertEquals(result.failures, Vector.empty)
          model = next
        case Step.Deliver(t) =>
          val (next, expected) = LifetimeModel.deliver(model, t)
          val result = callbacks.get(t).fold[CommitResult[Unit]](CommitResult.Refused("missing"))(_())
          assertEquals(status(result), expected)
          model = next
      assertEquals(trace.toVector, model.trace, s"trace at step $index: $step")
      assertEquals(r.lifetimes.scopes, model.owners.keySet.map(t => scopes(t).id) + root.id)
      assertEquals(r.lifetimes.cells, globalIds ++ model.owners.keySet.map(cellIds))
      assertEquals(r.lifetimes.subscriptions, model.owners.size * 2)
      assertEquals(r.lifetimes.pendingEffects, model.pending.size)
      globals.zipWithIndex.foreach { (cell, i) =>
        assertEquals(root.read(cell.versioned), Value.Ok(Versioned(model.globals(i), Revision(model.revisions(i)))))
      }
      model.owners.foreach { (t, o) =>
        assertEquals(scopes(t).read(drafts(t).versioned), Value.Ok(Versioned(o.draft, Revision(o.revision))))
      }
      assertEquals(r.diagnostics, Vector.empty)
    }
    r.close()

  property("dynamic branches and transactional lifetimes match independent state, traces and live resources") {
    Prop.forAll(program) { steps => checkProgram(steps); true }
  }

  test("oracle covers first publication, change-restore, descendant mounts and refused initialization") {
    checkProgram(Vector(
      Step.Batch(Vector(Op.Mount(1, 0, 1), Op.Draft(1, 2))),
      Step.Batch(Vector(Op.Mount(2, 1, 1), Op.Draft(2, 2), Op.Draft(2, 1))),
      Step.Batch(Vector(Op.Attempt(Vector(Op.Mount(3, 2, 1), Op.Draft(3, 2)), true))),
      Step.Batch(Vector(Op.Mount(4, 2, 1), Op.Draft(4, 2), Op.Refuse)),
      Step.Batch(Vector(Op.Attempt(Vector(Op.Mount(5, 2, 1), Op.Draft(5, 2)), false))),
      Step.Drain(2), Step.Deliver(3), Step.Deliver(4), Step.Deliver(5),
      Step.Batch(Vector(Op.Dispose(1))), Step.Drain(100), Step.Deliver(5)))
  }
