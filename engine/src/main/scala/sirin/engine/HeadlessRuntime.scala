package sirin.engine

import sirin.*
import scala.collection.mutable
import scala.util.control.{ControlThrowable, NonFatal}

/** Explicit failures at the headless ownership/phase boundary. */
enum KernelError(message: String) extends RuntimeException(message):
  case WrongThread extends KernelError("UI runtime used by a different thread")
  case Disposed extends KernelError("UI runtime is disposed")
  case ExpiredTransaction extends KernelError("transaction capability is no longer active")
  case ForeignSignal extends KernelError("signal belongs to another runtime or is unsupported")
  case ReadOnlyPhase extends KernelError("mutation or undeclared read in a read-only phase")
  case TopologyDeferred extends KernelError("dynamic topology is not implemented in C1.2a/b")

enum CommitResult[+A]:
  case Committed(value: A)
  /** Nested call joined the outer transaction; nothing published yet. */
  case Joined(value: A)
  case Refused(reason: String)
  case Failed(error: Throwable)

final case class EffectDrain(executed: Int, pending: Int, failures: Vector[Throwable]):
  def budgetExceeded: Boolean = pending > 0

trait Subscription:
  def cancel(): Unit

object HeadlessRuntime:
  private final class ReadPhase(val runtime: HeadlessRuntime, val notification: Boolean):
    var failure: Option[Throwable] = None
  private val phase = new ThreadLocal[Option[ReadPhase]]:
    override def initialValue(): Option[ReadPhase] = None
  private def readOnly[A](runtime: HeadlessRuntime, notification: Boolean)(body: => A): A =
    val previous = phase.get()
    val current = new ReadPhase(runtime, notification)
    phase.set(Some(current))
    try
      val result = body
      current.failure.foreach(error => throw error)
      result
    finally phase.set(previous)

/** Single-thread-owned static graph driver. It owns every registered node and
  * subscription until close. This is not a mounted Scope or a desktop host.
  * Effects are drained explicitly, each in a fresh transaction. No graph,
  * observer or effect installation is allowed during transaction evaluation.
  */
final class HeadlessRuntime:
  private val owner = Thread.currentThread()
  private var closed = false
  private var evaluating = false
  private var notifying = false
  private var active: Option[Transaction] = None
  private val nodes = mutable.LinkedHashMap.empty[Signal[?], Node]
  private val watches = mutable.ArrayBuffer.empty[Watch]
  private final case class EffectJob(live: () => Boolean, run: () => CommitResult[Unit])
  private val effectQueue = mutable.Queue.empty[EffectJob]
  private val reported = mutable.ArrayBuffer.empty[Throwable]

  private final class Node(val signal: Signal[?], val inputs: Vector[Signal[?]],
      val height: Int, val equality: Equality[Any], var value: Value[Any]):
    var runs = 0L

  private final class Refusal(val reason: String) extends ControlThrowable
  private final class Frame:
    var refusal: Option[String] = None

  private final class Source[A](val initial: A, val equality: Equality[A]) extends Cell[A]:
    val runtime: HeadlessRuntime = HeadlessRuntime.this
    override private[sirin] def valueEquality: Equality[A] = equality
    var revision: Revision = Revision.initial
    val versioned: Signal[Versioned[A]] = new Signal.Derived(Vector(this), reader =>
      reader(this) match
        case Value.Ok(value) => Value.Ok(Versioned(value, reader.revision(this)))
        case Value.Failed(error) => Value.Failed(error),
      (a, b) => a.revision == b.revision && equality.same(a.value, b.value))
    def set(value: A)(using txn: UiTxn): Unit = transaction(txn).write(this, value)
    def update(f: A => A)(using txn: UiTxn): Unit =
      val t = transaction(txn)
      t.boundary { set(f(get))(using txn) }
    def snapshot(using txn: UiTxn): Versioned[A] =
      val t = transaction(txn)
      t.boundary {
        val committed = valueOf(nodes(this).value).asInstanceOf[A]
        if t.writes.get(this).exists(value => !same(nodes(this), Value.Ok(committed), Value.Ok(value))) then
          t.refuse("snapshot requires committed cell value; commit edits before saving")
        Versioned(committed, revision)
      }

  private final class Watch(val signal: Signal[?], val deliver: Value[Any] => Unit):
    var live = true

  private def checkOwner(): Unit =
    if Thread.currentThread() ne owner then throw KernelError.WrongThread
    if closed then throw KernelError.Disposed

  private def installation(): Unit =
    checkOwner()
    if HeadlessRuntime.phase.get().nonEmpty || evaluating || notifying || active.nonEmpty then fail(KernelError.ReadOnlyPhase)

  private def fail(error: Throwable): Nothing =
    // Read-only guards span every driver on this thread. Even a caught protocol
    // violation fails its derivation; it cannot mutate another captured driver.
    HeadlessRuntime.phase.get() match
      case Some(phase) if !phase.notification => phase.failure = Some(error)
      case Some(_) => ()
      case None => active.foreach(_.poison = Some(error))
    throw error

  private def transaction(txn: UiTxn): Transaction =
    checkOwner()
    if HeadlessRuntime.phase.get().nonEmpty || evaluating || notifying then fail(KernelError.ReadOnlyPhase)
    active match
      case Some(current) if current eq txn => current
      case _ => fail(KernelError.ExpiredTransaction)

  private def valueOf[A](value: Value[A]): A = value match
    case Value.Ok(a) => a
    case Value.Failed(error) => throw error

  private def compute(derived: Signal.Derived[?], reader: Signal.Reader): Value[Any] =
    val previous = evaluating
    evaluating = true
    try HeadlessRuntime.readOnly(this, false)(derived.compute(reader).asInstanceOf[Value[Any]])
    catch case NonFatal(error) => Value.Failed(error)
    finally evaluating = previous

  private def same(node: Node, a: Value[Any], b: Value[Any]): Boolean = (a, b) match
    case (Value.Ok(x), Value.Ok(y)) =>
      val previous = evaluating
      evaluating = true
      try HeadlessRuntime.readOnly(this, false)(node.equality.same(x, y))
      finally evaluating = previous
    case (Value.Failed(x), Value.Failed(y)) => x eq y
    case _ => false

  private val committedReader: Signal.Reader = new Signal.Reader:
    def apply[A](signal: Signal[A]): Value[A] = nodes(signal).value.asInstanceOf[Value[A]]
    def revision(cell: Cell[?]): Revision = cell match
      case source: Source[?] if source.runtime eq HeadlessRuntime.this => source.revision
      case _ => throw KernelError.ForeignSignal

  private def register(signal: Signal[?]): Node = nodes.getOrElseUpdate(signal, {
    signal match
      case source: Source[?] if source.runtime eq this =>
        new Node(signal, Vector.empty, 0, source.equality.asInstanceOf[Equality[Any]], Value.Ok(source.initial))
      case constant: Signal.Constant[?] =>
        new Node(signal, Vector.empty, 0, constant.valueEquality.asInstanceOf[Equality[Any]], Value.Ok(constant.value))
      case derived: Signal.Derived[?] =>
        val parents = derived.inputs.map(register)
        val node = new Node(signal, derived.inputs, 1 + parents.map(_.height).maxOption.getOrElse(0),
          derived.equality.asInstanceOf[Equality[Any]], compute(derived, committedReader))
        node.runs += 1
        node
      case _: Signal.Dynamic[?, ?] => fail(KernelError.TopologyDeferred)
      case _ => fail(KernelError.ForeignSignal)
  })

  def cell[A](initial: A)(using equality: Equality[A]): Cell[A] =
    installation()
    val source = new Source(initial, equality)
    register(source)
    source

  /** Explicit committed observation; use signal.get for staged reads. */
  def read[A](signal: Signal[A]): Value[A] =
    checkOwner()
    val permitted = HeadlessRuntime.phase.get().forall(p => p.notification && (p.runtime eq this) && notifying)
    if !permitted || evaluating || (active.nonEmpty && !notifying) then fail(KernelError.ReadOnlyPhase)
    val node = if notifying then nodes.getOrElse(signal, throw KernelError.ReadOnlyPhase) else register(signal)
    node.value.asInstanceOf[Value[A]]

  def evaluations(signal: Signal[?]): Long =
    checkOwner()
    nodes.get(signal).fold(0L)(_.runs)

  def diagnostics: Vector[Throwable] =
    checkOwner()
    reported.toVector

  private def report(callback: => Unit): Unit =
    try callback
    catch case NonFatal(error) => reported += error

  def observe[A](signal: Signal[A])(callback: Value[A] => Unit): Subscription =
    installation()
    val node = register(signal)
    val watch = new Watch(signal, value => callback(value.asInstanceOf[Value[A]]))
    watches += watch
    notifying = true
    try HeadlessRuntime.readOnly(this, true)(report(watch.deliver(node.value)))
    finally notifying = false
    new Subscription:
      def cancel(): Unit =
        installation()
        watch.live = false
        watches -= watch

  /** Once initially and once per changed commit; errors require signal.attempt. */
  def effect[A](signal: Signal[A])(callback: A => Action): Subscription =
    var live = true
    val subscription = observe(signal) {
      case Value.Ok(value) => effectQueue.enqueue(EffectJob(() => live, () => transact(callback(value))))
      case Value.Failed(error) => reported += error
    }
    new Subscription:
      def cancel(): Unit =
        installation()
        live = false
        subscription.cancel()
        val kept = effectQueue.filter(_.live()).toVector
        effectQueue.clear()
        effectQueue ++= kept

  /** Engine hook for future command starts; rolls back with the attempt. */
  def afterCommit(callback: () => Unit)(using txn: UiTxn): Unit =
    transaction(txn).boundary { active.get.after += callback }

  private final class Transaction extends UiTxn:
    val writes = mutable.LinkedHashMap.empty[Source[?], Any]
    val after = mutable.ArrayBuffer.empty[() => Unit]
    val memo = mutable.HashMap.empty[Signal[?], Value[Any]]
    val frames = mutable.ArrayBuffer(new Frame)
    var poison: Option[Throwable] = None

    def boundary[A](body: => A): A =
      transaction(this)
      try body
      catch
        case refusal: Refusal => throw refusal
        case NonFatal(error) =>
          if poison.isEmpty then poison = Some(error)
          throw error

    private def candidateRevision(source: Source[?]): Revision =
      val committed = nodes(source)
      if writes.get(source).exists(value => !same(committed, committed.value, Value.Ok(value))) then source.revision.next
      else source.revision

    val speculativeReader: Signal.Reader = new Signal.Reader:
      def apply[A](signal: Signal[A]): Value[A] = staged(signal).asInstanceOf[Value[A]]
      def revision(cell: Cell[?]): Revision = cell match
        case source: Source[?] if source.runtime eq HeadlessRuntime.this => candidateRevision(source)
        case _ => throw KernelError.ForeignSignal

    def staged(signal: Signal[?]): Value[Any] = memo.getOrElseUpdate(signal, signal match
      case source: Source[?] if source.runtime eq HeadlessRuntime.this =>
        writes.get(source).map(Value.Ok(_)).getOrElse(nodes(source).value)
      case derived: Signal.Derived[?] => compute(derived, speculativeReader)
      case _ => register(signal).value)

    private[sirin] def read[A](signal: Signal[A]): A = boundary {
      register(signal)
      valueOf(staged(signal)).asInstanceOf[A]
    }

    def write[A](source: Source[A], value: A): Unit = boundary {
      writes(source) = value
      memo.clear()
    }

    def require(condition: Boolean, reason: => String): Unit = boundary {
      if !condition then refuse(reason)
    }

    def refuse(reason: String): Nothing =
      transaction(this)
      frames.last.refusal = Some(reason)
      throw new Refusal(reason)

    def attempt[A](body: UiTxn ?=> A): Attempt[A] = boundary {
      val saved = writes.toVector
      val savedAfter = after.size
      val savedNodes = nodes.keySet.toSet
      val frame = new Frame
      frames += frame
      try
        val value = body(using this)
        frame.refusal match
          case Some(reason) => Attempt.Refused(reason)
          case None => Attempt.Accepted(value)
      catch case refusal: Refusal => Attempt.Refused(refusal.reason)
      finally
        frames.remove(frames.size - 1)
        if frame.refusal.nonEmpty then
          writes.clear()
          writes ++= saved
          after.dropRightInPlace(after.size - savedAfter)
          nodes.keys.filterNot(savedNodes).toVector.foreach(nodes.remove)
          memo.clear()
    }

  def transact[A](body: UiTxn ?=> A): CommitResult[A] =
    checkOwner()
    if HeadlessRuntime.phase.get().nonEmpty || evaluating || notifying then fail(KernelError.ReadOnlyPhase)
    active match
      case Some(txn) =>
        try CommitResult.Joined(txn.boundary(body(using txn)))
        catch
          case refusal: Refusal => CommitResult.Refused(refusal.reason)
          case NonFatal(error) => CommitResult.Failed(error)
      case None =>
        val txn = new Transaction
        val oldNodes = nodes.keySet.toSet
        active = Some(txn)
        var result: CommitResult[A] = null
        try
          val value = txn.boundary(body(using txn))
          txn.poison match
            case Some(error) => result = CommitResult.Failed(error)
            case None => txn.frames.head.refusal match
              case Some(reason) => result = CommitResult.Refused(reason)
              case None =>
                publish(txn)
                result = CommitResult.Committed(value)
        catch
          case refusal: Refusal => result = txn.poison.fold[CommitResult[A]](CommitResult.Refused(refusal.reason))(CommitResult.Failed(_))
          case NonFatal(error) => result = CommitResult.Failed(error)
        finally active = None
        result match
          case CommitResult.Committed(_) =>
            notifying = true
            try HeadlessRuntime.readOnly(this, true)(txn.after.foreach(callback => report(callback())))
            finally notifying = false
          case _ => nodes.keys.filterNot(oldNodes).toVector.foreach(nodes.remove)
        result

  private def publish(txn: Transaction): Unit =
    val candidate: mutable.HashMap[Signal[?], Value[Any]] = mutable.HashMap.from(nodes.iterator.map((signal, node) => signal -> node.value))
    val revisions = mutable.HashMap.empty[Source[?], Revision]
    val changed = mutable.HashSet.empty[Signal[?]]
    txn.writes.foreach { (source, value) =>
      val node = nodes(source)
      if !same(node, node.value, Value.Ok(value)) then
        revisions(source) = source.revision.next
        candidate(source) = Value.Ok(value)
        changed += source
    }
    val reader = new Signal.Reader:
      def apply[A](signal: Signal[A]): Value[A] = candidate(signal).asInstanceOf[Value[A]]
      def revision(cell: Cell[?]): Revision = cell match
        case source: Source[?] if source.runtime eq HeadlessRuntime.this => revisions.getOrElse(source, source.revision)
        case _ => throw KernelError.ForeignSignal
    nodes.values.toVector.sortBy(_.height).foreach { node =>
      node.signal match
        case derived: Signal.Derived[?] if node.inputs.exists(changed) =>
          val next = compute(derived, reader)
          node.runs += 1
          if !same(node, node.value, next) then
            candidate(node.signal) = next
            changed += node.signal
        case _ => ()
    }
    // No callbacks or user code between the two publication loops.
    candidate.foreach((signal, value) => nodes(signal).value = value)
    revisions.foreach((source, revision) => source.revision = revision)
    notifying = true
    try HeadlessRuntime.readOnly(this, true)(watches.toVector.filter(w => w.live && changed(w.signal)).foreach(w => report(w.deliver(nodes(w.signal).value))))
    finally notifying = false

  def flushEffects(budget: Int = 100): EffectDrain =
    installation()
    require(budget > 0, "positive effect transaction budget required")
    var count = 0
    val errors = Vector.newBuilder[Throwable]
    while effectQueue.nonEmpty && count < budget do
      val job = effectQueue.dequeue()
      if job.live() then
        job.run() match
          case CommitResult.Failed(error) => errors += error
          case CommitResult.Refused(reason) => errors += new IllegalStateException(reason)
          case _ => ()
        count += 1
    EffectDrain(count, effectQueue.size, errors.result())

  def close(): Unit =
    installation()
    closed = true
    watches.clear()
    effectQueue.clear()
    nodes.clear()
    reported.clear()
