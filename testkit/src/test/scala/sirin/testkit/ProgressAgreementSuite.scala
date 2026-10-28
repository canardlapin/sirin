package sirin.testkit

import sirin.*
import sirin.engine.*
import sirin.testkit.ProgressModel.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

class ProgressAgreementSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(150)
  private val edit = Gen.frequency(5 -> Gen.choose(1, 20).map(Op.Offer(_)),
    4 -> Gen.choose(1, 20).map(Op.Produce(_)), 1 -> Gen.const(Op.Close),
    1 -> Gen.const(Op.Cancel), 1 -> Gen.const(Op.Refuse), 1 -> Gen.const(Op.Defect))
  private val step = Gen.frequency(
    5 -> Gen.listOfN(3, edit).map(xs => Step.Batch(xs.toVector)),
    3 -> (for xs <- Gen.listOfN(3, edit); refuse <- Gen.oneOf(true, false)
      yield Step.Batch(Vector(Op.Offer(1), Op.Attempt(xs.toVector, refuse), Op.Produce(2)))),
    4 -> Gen.choose(1, 20).map(Step.Produce(_)),
    4 -> (for n <- Gen.choose(1, 4); reject <- Gen.oneOf(true, false) yield Step.Drain(n, reject)))
  private val activeEdit = Gen.frequency(5 -> Gen.choose(1, 20).map(Op.Offer(_)),
    4 -> Gen.choose(1, 20).map(Op.Produce(_)), 1 -> Gen.const(Op.Refuse), 1 -> Gen.const(Op.Defect))
  private val activeStep = Gen.frequency(
    5 -> Gen.listOfN(3, activeEdit).map(xs => Step.Batch(xs.toVector)),
    3 -> (for xs <- Gen.listOfN(3, activeEdit); refuse <- Gen.oneOf(true, false)
      yield Step.Batch(Vector(Op.Attempt(xs.toVector, refuse)))),
    4 -> (for n <- Gen.choose(1, 4); reject <- Gen.oneOf(true, false) yield Step.Drain(n, reject)))
  private val program = for
    live <- Gen.listOfN(20, activeStep)
    ending <- Gen.listOfN(20, step)
  yield (live ++ ending).toVector
  private def result(value: CommitResult[?]): Result = value match
    case CommitResult.Committed(_) => Result.Accepted
    case CommitResult.Refused(_) => Result.Refused
    case CommitResult.Failed(_) => Result.Failed
    case other => fail(s"unexpected $other")
  private def read[A](r: HeadlessRuntime, signal: Signal[A]): A = r.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  private def produce(endpoint: Progress[Int], n: Int): Offered =
    val receipt = new AtomicReference[Either[Throwable, Offered]]()
    val done = new CountDownLatch(1)
    val thread = new Thread(() => {
      try receipt.set(Right(endpoint.tryEmit(n)))
      catch case error: Throwable => receipt.set(Left(error))
      finally done.countDown()
    })
    thread.setDaemon(true)
    thread.start()
    assert(done.await(5, TimeUnit.SECONDS))
    receipt.get() match
      case Right(offered) => offered
      case Left(error) => throw error
  private def check(steps: Vector[Step], policy: Delivery, capacity: Int): Unit =
    val r = new HeadlessRuntime
    val scope = r.scope("progress")
    val received = scope.cell(Vector.empty[Int])
    var reject = false
    val channel = scope.progress[Int](policy, Capacity(capacity)) { n =>
      received.update(_ :+ n)
      if reject && n % 5 == 0 then UiTxn.refuse("receiver")
      else if reject && n % 7 == 0 then
        try received.update(_ => throw new IllegalStateException("receiver"))
        catch case _: IllegalStateException => ()
    }
    var expected = State()
    var offers = Vector.empty[String]
    def ops(edits: Vector[Op])(using UiTxn): Unit = edits.foreach {
      case Op.Offer(n) => offers :+= channel.offer(n).toString
      case Op.Produce(n) => offers :+= produce(channel.producer, n).toString
      case Op.Close => channel.close()
      case Op.Cancel => channel.cancelDelivery()
      case Op.Refuse => UiTxn.refuse("root")
      case Op.Defect =>
        try received.update(_ => throw new IllegalStateException("defect"))
        catch case _: IllegalStateException => ()
      case Op.Attempt(inner, refuse) => UiTxn.attempt { ops(inner); if refuse then UiTxn.refuse("attempt") }; ()
    }
    try steps.zipWithIndex.foreach { (step, index) =>
      val (next, receipt) = ProgressModel.step(expected, step, policy == Delivery.CoalesceLatest, capacity)
      offers = Vector.empty
      val actual = step match
        case Step.Batch(edits) => Receipt(result(r.transact { ops(edits) }), offers)
        case Step.Produce(n) => Receipt(offers = Vector(produce(channel.producer, n).toString))
        case Step.Drain(n, shouldReject) =>
          reject = shouldReject
          val drain = r.drainProgress(n)
          Receipt(drained = drain.delivered, failure = drain.failures.headOption.map(result))
      val clue = s"$policy capacity=$capacity step=$index $step program=$steps"
      assertEquals(actual, receipt, clue)
      assertEquals(read(r, channel.state).toString, next.status, clue)
      assertEquals(read(r, received), next.received, clue)
      assertEquals(r.progressQueues.head, ProgressQueueSnapshot(next.slots.size, 0, next.accepted,
        next.delivered, next.coalesced, next.abandoned), clue)
      assertEquals(next.accepted, next.delivered + next.coalesced + next.abandoned + next.slots.size, clue)
      expected = next
    }
    finally r.close()
  for policy <- Delivery.values do
    property(s"bounded progress agrees with independent slot-history oracle: $policy") {
      Prop.forAll(program, Gen.choose(1, 4))((steps, capacity) => { check(steps, policy, capacity); true })
    }
