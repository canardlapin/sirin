package sirin.testkit

import sirin.*
import sirin.engine.*
import sirin.testkit.WorkModel.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.collection.mutable

class WorkAgreementSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(50)
  private def step(n: Int): Gen[Step] =
    val dispatch = Op.Dispatch(n)
    val simple = Gen.frequency(5 -> Gen.const(dispatch), 2 -> Gen.choose(0, 5).map(Op.Cancel(_)),
      2 -> Gen.oneOf(true, false).map(Op.Enable(_)), 1 -> Gen.const(Op.Refuse), 1 -> Gen.const(Op.Defect))
    val edits = Gen.listOfN(2, simple).map(_.zipWithIndex.map {
      case (Op.Dispatch(_), i) => Op.Dispatch(n * 10 + i + 1)
      case (other, _) => other
    }.toVector)
    Gen.frequency(
      5 -> edits.map(Step.Batch(_)),
      2 -> (for inner <- edits; refuse <- Gen.oneOf(true, false)
        yield Step.Batch(Vector(Op.Attempt(inner.toVector, refuse)))),
      4 -> Gen.choose(0, 5).map(Step.Run(_)),
      4 -> Gen.choose(0, 5).map(Step.Deliver(_)),
      2 -> Gen.choose(1, 4).map(Step.Drain(_)))
  private val program = (1 to 30).foldLeft(Gen.const(Vector.empty[Step]))((acc, n) =>
    for before <- acc; next <- step(n) yield before :+ next)
  private def end(outcome: Outcome[Int]): End = outcome match
    case Outcome.Succeeded(value) => End.Success(value)
    case Outcome.Failed(_) => End.Failure
    case Outcome.Cancelled => End.Cancelled
  private def normalized(result: CommitResult[?]): Result = result match
    case CommitResult.Committed(_) => Result.Accepted
    case CommitResult.Refused(_) => Result.Refused
    case CommitResult.Failed(_) => Result.Failed
    case other => fail(s"unexpected $other")
  private def value[A](r: HeadlessRuntime, signal: Signal[A]): A = r.read(signal) match
    case Value.Ok(v) => v
    case Value.Failed(error) => throw error
  private def pick[A](xs: Vector[A], index: Int): Option[A] = xs.lift(if xs.isEmpty then 0 else Math.floorMod(index, xs.size))

  private def check(steps: Vector[Step], mode: Mode, accept: Accept): Unit =
    val r = new HeadlessRuntime(workCapacity = 4)
    val owner = r.scope("owner")
    val enabled = owner.cell(true)
    val applied = owner.cell(Vector.empty[(Int, Boolean)])
    val trace = mutable.ArrayBuffer.empty[Trace]
    val launched = mutable.ArrayBuffer.empty[Int]
    val handles = mutable.Map.empty[Int, Invocation[Int]]
    val tokenByTicket = mutable.Map.empty[Long, Int]
    var matched = 0
    val concurrency = mode match
      case Mode.Parallel => Concurrency.Parallel
      case Mode.Serial => Concurrency.Serial(Capacity(2))
      case Mode.Refuse => Concurrency.RefuseWhileRunning
      case Mode.Supersede => Concurrency.Supersede
    val acceptance = accept match
      case Accept.Latest => Acceptance.LatestOnly
      case Accept.Progressive => Acceptance.Progressive
      case Accept.Every => Acceptance.EveryInvocation
      case Accept.Snapshot => Acceptance.SnapshotAssociated
    def work(token: Int): Work[Int] = Work {
      trace += Trace.Executed(token, summon[Cancellation].cancelled)
      if token % 13 == 0 then throw new IllegalStateException("work")
      token * 10
    }
    val cmd = owner.command[Int, Int, Int]("run", "Run", WorkPolicy(concurrency, acceptance), _ => enabled)(token => {
      owner.afterCommit(() => launched += token)
      sirin.Job(token, work(token))
    })((token, result) => {
      applied.update(_ :+ (token -> result.current))
      if token % 11 == 0 then
        try enabled.update(_ => throw new IllegalStateException("complete"))
        catch case _: IllegalStateException => ()
      else if token % 7 == 0 then UiTxn.refuse("complete")
    })
    owner.observe(cmd.running) { case Value.Ok(n) => trace += Trace.Running(n); case _ => () }
    var delivering = Option.empty[Int]
    owner.observe(cmd.lastResult) {
      case Value.Ok(Some(result)) => trace += Trace.Last(delivering.get, result.current, end(result.outcome))
      case _ => ()
    }
    owner.effect(cmd.running)(n => { trace += Trace.Effect(n); () })
    owner.observe(applied) {
      case Value.Ok(xs) if xs.nonEmpty =>
        val (token, current) = xs.last
        trace += Trace.Applied(token, current)
      case _ => ()
    }
    var stagedTokens = Vector.empty[Int]
    def ops(edits: Vector[Op])(using UiTxn): Unit = edits.foreach {
      case Op.Dispatch(token) => cmd.dispatch(token) match
        case Dispatched.Started(handle) =>
          handles(token) = handle
          stagedTokens :+= token
          val observed = handle.observe(owner)
          owner.observe(observed) {
            case Value.Ok(Some(result)) =>
              trace += Trace.Terminal(token, result.current, end(result.outcome))
            case _ => ()
          }
        case Dispatched.Refused(_) => ()
      case Op.Cancel(index) =>
        pick(stagedTokens, index).foreach(token => handles(token).cancel())
      case Op.Enable(value) => enabled.set(value)
      case Op.Attempt(inner, refuse) =>
        val before = stagedTokens
        UiTxn.attempt { ops(inner); if refuse then UiTxn.refuse("attempt") } match
          case Attempt.Refused(_) => stagedTokens = before
          case Attempt.Accepted(_) => ()
      case Op.Refuse => UiTxn.refuse("root")
      case Op.Defect =>
        try enabled.update(_ => throw new IllegalStateException("defect"))
        catch case _: IllegalStateException => ()
      case Op.Dispose => owner.dispose()
    }
    var runningTokens = Vector.empty[Int]
    var expected = State()
    steps.foreach { step =>
      val (next, result) = WorkModel.step(expected, step, mode, accept)
      val actual = step match
        case Step.Batch(edits) =>
          stagedTokens = runningTokens
          normalized(r.transact { ops(edits) })
        case Step.Run(index) =>
          pick(r.scheduler.tickets.filter(t => t.ready && !t.completed), index).foreach(t => r.scheduler.run(t.id))
          Result.Accepted
        case Step.Deliver(index) =>
          pick(r.scheduler.tickets.filter(_.completed), index).fold(Result.Accepted) { t =>
            delivering = Some(tokenByTicket(t.id))
            normalized(r.scheduler.deliver(t.id))
          }
        case Step.Drain(budget) => r.flushEffects(budget); Result.Accepted
      val unmatched = r.scheduler.tickets.filterNot(t => tokenByTicket.contains(t.id))
      val tokens = launched.drop(matched).toVector
      assertEquals(unmatched.size, tokens.size)
      unmatched.zip(tokens).foreach((ticket, token) => tokenByTicket(ticket.id) = token)
      matched = launched.size
      runningTokens = r.scheduler.tickets.map(t => tokenByTicket(t.id))
      assertEquals(actual, result, s"$mode/$accept $step")
      assertEquals(runningTokens, next.jobs.map(_.token))
      assertEquals(r.scheduler.tickets.map(t => (t.ready, t.completed, t.cancellationRequested)),
        next.jobs.map(j => (j.ready, j.end.nonEmpty, j.requested)))
      if next.alive then
        assertEquals(value(r, cmd.running), next.jobs.size)
        assertEquals(value(r, cmd.lastRefusal), next.refusal)
      assertEquals(r.lifetimes.pendingEffects, next.pending.size)
      assertEquals(trace.toVector, next.trace, s"$mode/$accept $step")
      expected = next
    }
    r.close()

  for mode <- Mode.values; accept <- Accept.values do
    property(s"controlled commands agree with full-state model: $mode/$accept") {
      Prop.forAll(program)(steps => { check(steps, mode, accept); true })
    }

  test("fixed command scripts cover refusal, cancellation, terminal handler failures and owner disposal across all policies") {
    val steps = Vector(
      Step.Batch(Vector(Op.Dispatch(1), Op.Dispatch(2))),
      Step.Batch(Vector(Op.Attempt(Vector(Op.Cancel(0), Op.Enable(false), Op.Dispatch(3)), true))),
      Step.Batch(Vector(Op.Dispatch(7))), Step.Run(0), Step.Run(1), Step.Deliver(0),
      Step.Batch(Vector(Op.Cancel(0))), Step.Run(0), Step.Deliver(0),
      Step.Batch(Vector(Op.Dispatch(11))), Step.Run(0), Step.Deliver(0), Step.Drain(3),
      Step.Batch(Vector(Op.Dispose)), Step.Deliver(0), Step.Drain(100))
    for mode <- Mode.values; accept <- Accept.values do check(steps, mode, accept)
  }
