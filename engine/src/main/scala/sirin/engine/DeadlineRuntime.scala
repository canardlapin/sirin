package sirin.engine

import sirin.*

private[engine] trait DeadlineBinding extends WorkBinding:
  def due: Option[Long]
  def fire(): CommitResult[Unit]
  def retainedInputs: Int

private[engine] final class DebounceRuntime[A](runtime: HeadlessRuntime)(owner: runtime.OwnedScope,
    input: Signal[A], quietNanos: Long) extends DeadlineBinding:
  private val state = owner.cell[Value[A]](Value.Failed(KernelError.Deferred("initial debounce settle")))(using (a, b) => a eq b)
  val signal: Signal[A] = new Signal.Derived(Vector(state), reader => reader(state) match
    case Value.Ok(value) => value
    case Value.Failed(error) => Value.Failed(error), input.valueEquality)
  private var previous = Option.empty[Value[A]]
  private var candidate = Option.empty[Value[A]]
  private var scheduled = Option.empty[(Long, Value[A], Long)]
  owner.onDispose(() => { previous = None; candidate = None; scheduled = None })
  def retainedInputs: Int = previous.size + candidate.size + scheduled.size
  def alive: Boolean = runtime.scopeAvailable(owner)
  def dependencies: Vector[Signal[?]] = Vector(input)
  def pending: Boolean = false
  def due: Option[Long] = if alive then scheduled.map(_._1) else None
  def begin(): Unit = candidate = None
  private def same(a: Value[A], b: Value[A]): Boolean = (a, b) match
    case (Value.Ok(x), Value.Ok(y)) => runtime.lawful(input.valueEquality.same(x, y))
    case (Value.Failed(x), Value.Failed(y)) => x eq y
    case _ => false
  private def schedule(next: Option[(Long, Value[A], Long)]): Unit =
    val before = scheduled
    runtime.recordUndo(() => scheduled = before)
    scheduled = next
  def reconcile(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean = read(input) match
    case Some(raw) =>
      val value = raw.asInstanceOf[Value[A]]
      candidate = Some(value)
      if previous.isEmpty && !read(state).exists {
        case Value.Ok(stored) => same(stored.asInstanceOf[Value[A]], value)
        case _ => false
      } then { state.set(value); true }
      else false
    case None => true
  def prepare()(using UiTxn): Unit = candidate.foreach { value =>
    if !previous.exists(same(_, value)) then
      val before = previous
      runtime.recordUndo(() => previous = before)
      previous = Some(value)
      if before.nonEmpty then
        if same(state.get, value) then schedule(None)
        else schedule(Some((Math.addExact(runtime.timeNanos(), quietNanos), value, runtime.transactionCause)))
  }
  def fire(): CommitResult[Unit] =
    val (_, value, cause) = scheduled.get
    val result = runtime.caused(CauseKind.Deadline, Some(cause)) {
      runtime.transact { schedule(None); state.set(value) }
    }
    // A delivery attempt is consumed even if the candidate graph rejects it.
    result match
      case CommitResult.Committed(_) => ()
      case _ => scheduled = None
    result
