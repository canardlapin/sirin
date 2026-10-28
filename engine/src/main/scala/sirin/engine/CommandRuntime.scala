package sirin.engine

import sirin.*

private[engine] final class RuntimeCommand[A, S, R](val runtime: HeadlessRuntime)(owner: runtime.OwnedScope,
    name: String, val label: String, val policy: WorkPolicy,
    enable: CommandStatus[R] => Signal[Boolean], argumentEnabled: Signal[A => Boolean], synchronous: Boolean)(
    start: A => UiTxn ?=> Job[S, R])(complete: (S, CommandResult[R]) => Action) extends Command[A, R]:
  val id: CommandId = CommandId(name)
  private val count = owner.cell(0)
  private val result = owner.cell(Option.empty[CommandResult[R]])(using RuntimeCommand.resultEquality)
  private val refusal = owner.cell(Option.empty[String])
  val running: Signal[Int] = Inputs.from(count)
  val lastResult: Signal[Option[CommandResult[R]]] = Inputs.from(result)
  val lastRefusal: Signal[Option[String]] = Inputs.from(refusal)
  private val rawEnabled = runtime.lawful(enable(this))
  val enabled: Signal[Boolean] = rawEnabled.attempt.map {
    case Value.Ok(value) => value
    case Value.Failed(error) => runtime.note(error); false
  }
  def enabledFor(arg: Signal[A]): Signal[Boolean] = enabled.zip(argumentEnabled.attempt).zip(arg).map {
    case ((general, Value.Ok(predicate)), value) =>
      try general && runtime.lawful(predicate(value))
      catch
        case scala.util.control.NonFatal(error) =>
          runtime.note(error)
          false
    case ((_, Value.Failed(error)), _) => runtime.note(error); false
  }
  private final case class Entry(generation: Long, ticket: Long, snapshot: S, handle: Handle, aborted: Boolean)
  private var entries = Vector.empty[Entry]
  private var generation = 0L
  private var starting = false
  private def setEntries(next: Vector[Entry]): Unit =
    val before = entries
    runtime.recordUndo(() => entries = before)
    entries = next
  private def nextGeneration(): Long =
    val before = generation
    runtime.recordUndo(() => generation = before)
    generation = Math.addExact(generation, 1L)
    generation
  private def current(entry: Entry): Boolean = entry.generation == generation
  private def accepted(entry: Entry): Boolean = !entry.aborted && (policy.acceptance != Acceptance.LatestOnly || current(entry))
  private def schedule(): Unit =
    policy.concurrency match
      case Concurrency.Serial(_) => entries.headOption.foreach(e => runtime.scheduler.ready(e.ticket))
      case _ => entries.foreach(e => runtime.scheduler.ready(e.ticket))
  owner.onDispose(() => {
    entries.foreach(e => { runtime.scheduler.retire(e.ticket); e.handle.abandon() })
    entries = Vector.empty
  })

  private final class Handle(val invocation: InvocationId) extends Invocation[R]:
    private var live = true
    runtime.recordUndo(() => live = false)
    private var terminal: Option[CommandResult[R]] = None
    private var observers = Vector.empty[(runtime.OwnedScope, Cell[Option[CommandResult[R]]])]
    def observe(scope: Scope): Signal[Option[CommandResult[R]]] = runtime.declare(scope) { owned =>
      if !live then throw KernelError.DisposedScope
      val cell = owned.cell(terminal)(using RuntimeCommand.resultEquality)
      if terminal.isEmpty then
        val before = observers
        runtime.recordUndo(() => observers = before)
        observers :+= owned -> cell
        owned.onDispose(() => observers = observers.filterNot(_._2 eq cell))
      Inputs.from(cell)
    }
    def cancel()(using txn: UiTxn): Unit = runtime.capability(txn) {
      runtime.ensureScope(owner)
      if !live then throw KernelError.DisposedScope
      entries.find(_.handle eq this).foreach { entry =>
        setEntries(entries.map(e => if e.handle eq this then e.copy(aborted = true) else e))
        val queued = policy.concurrency match
          case Concurrency.Serial(_) => !entries.headOption.exists(_.handle eq this)
          case _ => false
        runtime.afterCommit(() => runtime.scheduler.cancel(entry.ticket, queued))
      }
    }
    def finish(value: CommandResult[R])(using UiTxn): Unit =
      val before = terminal
      val oldObservers = observers
      runtime.recordUndo(() => { terminal = before; observers = oldObservers })
      terminal = Some(value)
      observers.foreach { (scope, cell) => if runtime.scopeAvailable(scope) then cell.set(terminal) }
      observers = Vector.empty
    def abandon(): Unit = { live = false; observers = Vector.empty }

  private[engine] def requestCancellation()(using UiTxn): Unit =
    val pending = entries
    runtime.afterCommit(() => pending.foreach(e => runtime.scheduler.cancel(e.ticket, false)))

  private def finish(ticket: Long, outcome: Outcome[R]): CommitResult[Unit] =
    if !runtime.scopeAvailable(owner) then CommitResult.Refused("disposed work owner")
    else entries.find(_.ticket == ticket) match
      case None => CommitResult.Refused("retired invocation")
      case Some(entry) =>
        val terminal = CommandResult(entry.handle.invocation, outcome, current(entry))
        val accept = accepted(entry)
        val bookkeeping = runtime.transact {
          runtime.ensureScope(owner)
          entry.handle.finish(terminal)
          setEntries(entries.filterNot(_.ticket == ticket))
          count.set(entries.size)
          if accept then result.set(Some(terminal))
          runtime.afterCommit(() => { runtime.scheduler.retire(ticket); schedule() })
        }
        bookkeeping match
          case CommitResult.Committed(_) if accept && runtime.scopeAvailable(owner) =>
            val delivered = runtime.transact { runtime.ensureScope(owner); complete(entry.snapshot, terminal) }
            delivered match
              case CommitResult.Failed(error) => runtime.note(error)
              case CommitResult.Refused(reason) => runtime.note(new IllegalStateException(reason))
              case _ => ()
            delivered
          case other => other

  def dispatch(arg: A)(using txn: UiTxn): Dispatched[R] = runtime.capability(txn) {
    runtime.ensureScope(owner)
    val attempt = txn.attempt {
      UiTxn.require(!starting, "command dispatch already in progress")
      val general = enabled.attempt.get match
        case Value.Ok(value) => value
        case Value.Failed(error) => runtime.note(error); false
      val allowed = argumentEnabled.attempt.get match
        case Value.Ok(predicate) =>
          try runtime.lawful(predicate(arg))
          catch
            case scala.util.control.NonFatal(error) =>
              runtime.note(error)
              false
        case Value.Failed(error) => runtime.note(error); false
      UiTxn.require(general && allowed, "command disabled")
      if !synchronous then
        policy.concurrency match
          case Concurrency.RefuseWhileRunning => UiTxn.require(entries.isEmpty, "command busy")
          case Concurrency.Serial(capacity) => UiTxn.require(entries.size < capacity.value + 1L, "command queue full")
          case _ => ()
        UiTxn.require(runtime.scheduler.available, runtime.scheduler.refusal.getOrElse("work capacity full"))
      starting = true
      val job = try start(arg) finally starting = false
      val stamp = nextGeneration()
      val handle = new Handle(InvocationId(runtime.nextInvocationId()))
      refusal.set(None)
      if synchronous then
        val terminal = CommandResult(handle.invocation, Outcome.Succeeded(().asInstanceOf[R]), true)
        handle.finish(terminal)
        result.set(Some(terminal))
      else
        var ticket = 0L
        ticket = runtime.scheduler.reserve(job.work, outcome => finish(ticket, outcome))
        val older = entries
        setEntries(entries :+ Entry(stamp, ticket, job.snapshot, handle, false))
        count.set(entries.size)
        runtime.afterCommit(() => {
          runtime.scheduler.publish(ticket)
          if policy.concurrency == Concurrency.Supersede then older.foreach(e => runtime.scheduler.cancel(e.ticket, false))
          schedule()
        })
      handle
    }
    attempt match
      case Attempt.Accepted(handle) => Dispatched.Started(handle)
      case Attempt.Refused(reason) => refusal.set(Some(reason)); Dispatched.Refused(reason)
  }

private[engine] object RuntimeCommand:
  def resultEquality[R]: Equality[Option[CommandResult[R]]] = (a, b) => (a, b) match
    case (None, None) => true
    case (Some(x), Some(y)) => x eq y
    case _ => false
