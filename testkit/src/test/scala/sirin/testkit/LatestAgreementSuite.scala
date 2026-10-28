package sirin.testkit

import sirin.*
import sirin.engine.*
import sirin.testkit.LatestModel.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.collection.mutable

class LatestAgreementSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(150)
  private val input = Gen.choose(0, 5).map(Op.Input(_))
  private val edit = Gen.frequency(7 -> input, 1 -> Gen.const(Op.Refuse), 1 -> Gen.const(Op.Defect))
  private val step = Gen.frequency(
    5 -> Gen.listOfN(3, edit).map(xs => Step.Batch(xs.toVector)),
    2 -> (for xs <- Gen.listOfN(3, edit); refuse <- Gen.oneOf(true, false)
      yield Step.Batch(Vector(Op.Attempt(xs.toVector, refuse)))),
    4 -> Gen.choose(0, 5).map(Step.Run(_)),
    4 -> Gen.choose(0, 5).map(Step.Deliver(_)))
  private val program = Gen.listOfN(40, step).map(_.toVector)
  private def normalize(result: CommitResult[?]): Result = result match
    case CommitResult.Committed(_) => Result.Accepted
    case CommitResult.Refused(_) => Result.Refused
    case CommitResult.Failed(_) => Result.Failed
    case other => fail(s"unexpected $other")
  private def pick[A](xs: Vector[A], index: Int): Option[A] = xs.lift(if xs.isEmpty then 0 else Math.floorMod(index, xs.size))
  private def shown(value: sirin.Completed[Int, Int]): LatestModel.Completed = value.outcome match
    case Outcome.Succeeded(output) => LatestModel.Completed(value.input, output, value.current)
    case other => fail(s"unexpected $other")
  private def display(value: Value[TaskState[Int, Int]]): Display = value match
    case Value.Failed(_: ArithmeticException) => Display.Failed
    case Value.Ok(TaskState.Pending(input, previous)) => Display.Pending(input, previous.map(shown))
    case Value.Ok(TaskState.Done(result)) => Display.Done(shown(result))
    case other => fail(s"unexpected $other")
  private def check(steps: Vector[Step], progressive: Boolean): Unit =
    val r = new HeadlessRuntime(workCapacity = 3)
    val owner = r.scope("owner")
    val divisor = owner.cell(1)
    val executions = mutable.ArrayBuffer.empty[(Int, Boolean)]
    def work(n: Int): Work[Int] = Work {
      executions += n -> summon[Cancellation].cancelled
      n * 10
    }
    val policy = if progressive then LatestPolicy.Progressive else LatestPolicy.LatestOnly
    val state = owner.latest(divisor.map(n => 10 / n), policy)(work)
    var expected = State()
    def ops(edits: Vector[Op])(using UiTxn): Unit = edits.foreach {
      case Op.Input(value) => divisor.set(value)
      case Op.Attempt(inner, refuse) => UiTxn.attempt { ops(inner); if refuse then UiTxn.refuse("attempt") }; ()
      case Op.Refuse => UiTxn.refuse("root")
      case Op.Defect =>
        try divisor.update(_ => throw new IllegalStateException("defect"))
        catch case _: IllegalStateException => ()
      case Op.Dispose => owner.dispose()
    }
    steps.foreach { step =>
      val (next, result) = LatestModel.step(expected, step, progressive)
      val actual = step match
        case Step.Batch(edits) => normalize(r.transact { ops(edits) })
        case Step.Run(index) =>
          pick(r.scheduler.tickets.filter(t => t.ready && !t.completed), index).foreach(t => r.scheduler.run(t.id))
          Result.Accepted
        case Step.Deliver(index) =>
          pick(r.scheduler.tickets.filter(_.completed), index).fold(Result.Accepted)(t => normalize(r.scheduler.deliver(t.id)))
      assertEquals(actual, result, s"$step")
      assertEquals(r.scheduler.tickets.map(t => (t.ready, t.completed, t.cancellationRequested)),
        next.jobs.map(j => (true, j.done, j.requested)))
      if next.alive then assertEquals(display(r.read(state)), next.display, s"$step")
      assertEquals(executions.toVector, next.executed)
      expected = next
    }
    r.close()
  for progressive <- Vector(false, true) do
    property(s"latest state and cancellation agree with immutable model: progressive=$progressive") {
      Prop.forAll(program)(steps => { check(steps, progressive); true })
    }
  test("stable input eventually completes after saturation, failed input and refused recovery") {
    val steps = Vector(
      Step.Batch(Vector(Op.Input(2))), Step.Batch(Vector(Op.Input(3))), Step.Batch(Vector(Op.Input(4))),
      Step.Run(0), Step.Deliver(0), Step.Batch(Vector(Op.Input(0))),
      Step.Batch(Vector(Op.Attempt(Vector(Op.Input(1)), true))), Step.Batch(Vector(Op.Input(5))),
      Step.Run(0), Step.Deliver(0), Step.Run(0), Step.Deliver(0), Step.Run(0), Step.Deliver(0),
      Step.Batch(Vector(Op.Dispose)), Step.Deliver(0))
    check(steps, false)
    check(steps, true)
  }
