package sirin.testkit

import sirin.*
import sirin.engine.*
import EventDeadlineModel.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.concurrent.duration.*

class EventDeadlineAgreementSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(250)
  private val edit = Gen.frequency(5 -> Gen.choose(1, 15).map(Op.Emit(_)),
    5 -> Gen.choose(0, 4).map(Op.Input(_)), 1 -> Gen.const(Op.Refuse), 1 -> Gen.const(Op.Defect))
  private val baseStep = Gen.frequency(
    5 -> Gen.listOfN(3, edit).map(xs => Step.Batch(xs.toVector)),
    3 -> (for xs <- Gen.listOfN(3, edit); refuse <- Gen.oneOf(true, false)
      yield Step.Batch(Vector(Op.Input(1), Op.Attempt(xs.toVector, refuse), Op.Emit(2)))),
    5 -> Gen.choose(0, 4).map(Step.Advance(_)),
    5 -> Gen.choose(1, 5).map(Step.Turn(_)))
  private val lifetime = Gen.frequency(10 -> baseStep,
    1 -> Gen.oneOf(Op.DisposeSource, Op.DisposeTimer, Op.DisposeListener(0), Op.DisposeListener(1))
      .map(op => Step.Batch(Vector(op))))
  private val program = for
    live <- Gen.listOfN(25, baseStep)
    retiring <- Gen.listOfN(25, lifetime)
  yield (live ++ retiring).toVector
  private def result(value: CommitResult[?]): Result = value match
    case CommitResult.Committed(_) => Result.Accepted
    case CommitResult.Refused(_) => Result.Refused
    case CommitResult.Failed(_) => Result.Failed
    case other => fail(s"$other")
  private def read[A](r: HeadlessRuntime, signal: Signal[A]): A = r.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  private def check(steps: Vector[Step], capacity: Int): Unit =
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock, eventCapacity = capacity)
    try
      val root = r.scope("root")
      val input = root.cell(0)
      val seen = root.cell(Vector.empty[(Int, Int)])
      val sourceOwner = root.child("source")
      val source = sourceOwner.events[Int]
      val timerOwner = root.child("timer")
      val output = timerOwner.debounce(input, 3.millis)
      val listeners = Vector(root.child("a"), root.child("b"))
      def handler(index: Int, value: Int)(using UiTxn): Unit =
        seen.update(_ :+ (index -> value))
        val original = if index == 0 then value else value / 10
        if original % 5 == 0 then UiTxn.refuse("handler")
        else if original % 7 == 0 then
          try seen.update(_ => throw new IllegalStateException("handler"))
          catch case _: IllegalStateException => ()
      listeners(0).on(source)(n => handler(0, n))
      listeners(1).on(source.filter(_ % 2 != 0).map(_ * 10))(n => handler(1, n))
      def ops(edits: Vector[Op])(using UiTxn): Unit = edits.foreach {
        case Op.Emit(n) => source.emit(n)
        case Op.Input(n) => input.set(n)
        case Op.DisposeSource => sourceOwner.dispose()
        case Op.DisposeTimer => timerOwner.dispose()
        case Op.DisposeListener(index) => listeners(index).dispose()
        case Op.Refuse => UiTxn.refuse("root")
        case Op.Defect =>
          try input.update(_ => throw new IllegalStateException("defect"))
          catch case _: IllegalStateException => ()
        case Op.Attempt(inner, refuse) => UiTxn.attempt { ops(inner); if refuse then UiTxn.refuse("attempt") }; ()
      }
      var expected = State()
      steps.zipWithIndex.foreach { (step, index) =>
        val (next, receipt) = EventDeadlineModel.step(expected, step, capacity)
        val actual = step match
          case Step.Batch(edits) => Receipt(result(r.transact { ops(edits) }))
          case Step.Advance(n) => clock.advanceBy(n.millis); Receipt()
          case Step.Turn(n) =>
            val turn = r.drive(n)
            Receipt(executed = turn.executed, failures = turn.failures.map(result), exhausted = turn.budgetExceeded)
        val clue = s"capacity=$capacity step=$index $step program=$steps"
        assertEquals(actual, receipt, clue)
        assertEquals(read(r, input), next.input, clue)
        if next.timer then assertEquals(read(r, output), next.output, clue)
        assertEquals(read(r, seen), next.received, clue)
        val snapshot = r.driverSnapshot
        assertEquals(snapshot.pendingEvents, next.jobs.size, clue)
        assertEquals(snapshot.nextDeadlineNanos, next.deadline.map(_._1.toLong * 1000000L), clue)
        assertEquals(snapshot.activeTurns, next.activeTurns, clue)
        assertEquals(snapshot.deliveredUnits, next.units, clue)
        expected = next
      }
    finally r.close()
  property("event routing, rollback, retirement and debounce agree with immutable virtual-time model") {
    Prop.forAll(program, Gen.choose(1, 6))((steps, capacity) => { check(steps, capacity); true })
  }
