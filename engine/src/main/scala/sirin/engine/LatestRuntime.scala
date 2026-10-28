package sirin.engine

import sirin.*

private[engine] trait WorkBinding:
  def alive: Boolean
  def dependencies: Vector[Signal[?]]
  def pending: Boolean
  def begin(): Unit
  def prepare()(using UiTxn): Unit
  def reconcile(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean

private[engine] final class LatestRuntime[I, O](runtime: HeadlessRuntime)(owner: runtime.OwnedScope,
    input: Signal[I], policy: LatestPolicy, work: I => Work[O]) extends WorkBinding:
  private val state = owner.cell[Value[TaskState[I, O]]](Value.Ok(TaskState.Idle))(using (a, b) => a eq b)
  val signal: Signal[TaskState[I, O]] = new Signal.Derived(Vector(state), reader => reader(state) match
    case Value.Ok(value) => value
    case Value.Failed(error) => Value.Failed(error), (a, b) => a eq b)
  private var waiting = false
  def pending: Boolean = waiting
  private def waitForStart(value: Boolean): Unit =
    val before = waiting
    runtime.recordUndo(() => waiting = before)
    waiting = value
  private var previous: Option[Value[I]] = None
  private var candidateInput: Option[Value[I]] = None
  private var baseline: Option[Value[TaskState[I, O]]] = None
  def begin(): Unit = { candidateInput = None; baseline = None }
  private var epoch = 0L
  private var acceptedEpoch = 0L
  private var shown: Option[Completed[I, O]] = None
  private val command = new RuntimeCommand[I, (Long, I), O](runtime)(owner, "latest", "Latest",
    WorkPolicy(Concurrency.Supersede, Acceptance.EveryInvocation), _ => Signal.always,
    Signal.const((_: I) => true), false)(value => Job((epoch, value), runtime.lawful(work(value))))((snapshot, result) =>
      val (stamp, value) = snapshot
      val current = stamp == epoch
      val liveInput = previous.flatMap {
        case Value.Ok(in) => Some(in)
        case _ => None
      }
      if liveInput.nonEmpty && stamp > acceptedEpoch && (current || policy == LatestPolicy.Progressive) then
        val beforeEpoch = acceptedEpoch
        val beforeShown = shown
        runtime.recordUndo(() => { acceptedEpoch = beforeEpoch; shown = beforeShown })
        acceptedEpoch = stamp
        val completed = Completed(value, result.outcome, current)
        shown = Some(completed)
        if current then state.set(Value.Ok(TaskState.Done(completed)))
        else state.set(Value.Ok(TaskState.Pending(liveInput.get, shown)))
  )
  def alive: Boolean = runtime.scopeAvailable(owner)
  def dependencies: Vector[Signal[?]] = Vector(input)
  private def same(a: Value[I], b: Value[I]): Boolean = (a, b) match
    case (Value.Ok(x), Value.Ok(y)) => runtime.lawful(input.valueEquality.same(x, y))
    case (Value.Failed(x), Value.Failed(y)) => x eq y
    case _ => false
  def reconcile(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
    (read(input), read(state)) match
      case (Some(raw), Some(Value.Ok(stored))) =>
        val value = raw.asInstanceOf[Value[I]]
        val storedState = stored.asInstanceOf[Value[TaskState[I, O]]]
        if baseline.isEmpty then baseline = Some(storedState)
        if candidateInput.exists(same(_, value)) then false
        else
          candidateInput = Some(value)
          val desired = if previous.exists(same(_, value)) then baseline.get else value match
            case Value.Failed(error) => Value.Failed(error)
            case Value.Ok(in) => Value.Ok(TaskState.Pending(in, shown.map(_.copy(current = false))))
          if desired eq storedState then false
          else { state.set(desired); true }
      case _ => true

  // Called once, only after structure and all latest state bindings have settled.
  def prepare()(using UiTxn): Unit = candidateInput.foreach { value =>
    if !previous.exists(same(_, value)) then
      val before = previous
      val beforeEpoch = epoch
      runtime.recordUndo(() => { previous = before; epoch = beforeEpoch })
      previous = Some(value)
      epoch = Math.addExact(epoch, 1L)
      command.requestCancellation()
      waitForStart(value.isInstanceOf[Value.Ok[?]])
    if waiting && runtime.scheduler.available then value match
      case Value.Ok(in) => command.dispatch(in) match
        case Dispatched.Refused(_) => ()
        case Dispatched.Started(_) => waitForStart(false)
      case _ => ()
  }
