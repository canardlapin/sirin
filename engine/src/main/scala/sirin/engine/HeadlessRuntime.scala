package sirin.engine

import sirin.*
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.{ControlThrowable, NonFatal}

enum KernelError(message: String) extends RuntimeException(message):
  case WrongThread extends KernelError("UI runtime used by a different thread")
  case Disposed extends KernelError("UI runtime is disposed")
  case DisposedScope extends KernelError("scope or provisional cell is disposed")
  case ExpiredTransaction extends KernelError("transaction capability is no longer active")
  case ForeignSignal extends KernelError("signal belongs to another runtime or is unsupported")
  case ReadOnlyPhase extends KernelError("mutation or undeclared read in a read-only phase")
  case CycleDetected extends KernelError("dependency cycle detected")
  case BudgetExceeded extends KernelError("topology settle pass budget exceeded")
  case DuplicatePlacement extends KernelError("static position already mounted with this definition")
  case Deferred(feature: String) extends KernelError(s"headless feature not implemented: $feature")

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

/** Process-local definition identity, independent of an allocation of a scope. */
final class ScopeDefinition(val name: String)
final case class ScopeIdentity(owner: Long, position: Int, definition: ScopeDefinition)
final case class LifetimeSnapshot(scopes: Set[Long], cells: Set[Long], nodes: Int,
    edges: Int, subscriptions: Int, pendingEffects: Int)

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

/** Deterministic single-thread host. Graph edits settle privately between passes.
  * Scoped resources are owned until transactional disposal. Desktop hosting,
  * component reconciliation, commands and native retirement are separate stages.
  */
final class HeadlessRuntime(val settleBudget: Int = 100):
  require(settleBudget > 0, "positive settle pass budget required")
  private val owner = Thread.currentThread()
  private var closed = false
  private var notifying = false
  private var active: Option[Transaction] = None
  private var nextId = 0L
  private def freshId(): Long = { nextId = Math.addExact(nextId, 1L); nextId }
  private val nodes = mutable.LinkedHashMap.empty[Signal[?], Node]
  private val roots = mutable.LinkedHashSet.empty[Signal[?]]
  private val sources = mutable.LinkedHashSet.empty[Source[?]]
  private val scopes = mutable.LinkedHashMap.empty[Long, OwnedScope]
  private val watches = mutable.ArrayBuffer.empty[Watch]
  private final case class EffectJob(live: () => Boolean, run: () => CommitResult[Unit])
  private val effectQueue = mutable.Queue.empty[EffectJob]
  private val reported = mutable.ArrayBuffer.empty[Throwable]

  private final class Node(val signal: Signal[?], var inputs: Vector[Signal[?]],
      val equality: Equality[Any], var value: Value[Any], var initialized: Boolean):
    var selected: Option[Signal[?]] = None
    var selectionReady = false
    var selectionFailure: Option[Throwable] = None
    var runs = 0L
    def copyNode(): Node =
      val n = new Node(signal, inputs, equality, value, initialized)
      n.selected = selected
      n.selectionReady = selectionReady
      n.selectionFailure = selectionFailure
      n.runs = runs
      n

  private final class Refusal(val reason: String) extends ControlThrowable
  private final class Frame:
    var refusal: Option[String] = None

  private final class Source[A](val initial: A, val equality: Equality[A], val scope: Option[OwnedScope]) extends Cell[A]:
    val runtime: HeadlessRuntime = HeadlessRuntime.this
    val id: Long = freshId()
    var live = true
    var committed = active.isEmpty
    var revision: Revision = Revision.initial
    override private[sirin] def valueEquality: Equality[A] = equality
    val versioned: Signal[Versioned[A]] = new Signal.Derived(Vector(this), reader =>
      reader(this) match
        case Value.Ok(value) => Value.Ok(Versioned(value, reader.revision(this)))
        case Value.Failed(error) => Value.Failed(error),
      (a, b) => a.revision == b.revision && equality.same(a.value, b.value))
    def set(value: A)(using txn: UiTxn): Unit =
      val t = transaction(txn)
      t.boundary { checkSource(this); t.writes(this) = value; t.memo.clear() }
    def update(f: A => A)(using txn: UiTxn): Unit =
      val t = transaction(txn)
      t.boundary { set(f(get)) }
    def snapshot(using txn: UiTxn): Versioned[A] =
      val t = transaction(txn)
      t.boundary {
        checkSource(this)
        if !this.committed then t.refuse("snapshot requires a committed cell; finish mounting first")
        val committed = valueOf(nodes(this).value).asInstanceOf[A]
        if t.writes.get(this).exists(value => !same(equality.asInstanceOf[Equality[Any]], Value.Ok(committed), Value.Ok(value))) then
          t.refuse("snapshot requires committed cell value; commit edits before saving")
        Versioned(committed, revision)
      }

  private final class Watch(val signal: Signal[?], val scope: Option[OwnedScope], val deliver: Value[Any] => Unit):
    var live = true
    var initial = true

  private def checkOwner(): Unit =
    if Thread.currentThread() ne owner then throw KernelError.WrongThread
    if closed then throw KernelError.Disposed

  private def fail(error: Throwable): Nothing =
    HeadlessRuntime.phase.get() match
      case Some(phase) if !phase.notification => phase.failure = Some(error)
      case Some(_) => ()
      case None => active.foreach(t => if t.poison.isEmpty then t.poison = Some(error))
    throw error

  private def mutation(): Unit =
    checkOwner()
    if HeadlessRuntime.phase.get().nonEmpty || notifying then fail(KernelError.ReadOnlyPhase)

  private def installation(): Unit =
    mutation()
    if active.nonEmpty then fail(KernelError.ReadOnlyPhase)

  private def transaction(txn: UiTxn): Transaction =
    mutation()
    active match
      case Some(current) if current eq txn => current
      case _ => fail(KernelError.ExpiredTransaction)

  private def valid(scope: OwnedScope, disposed: Set[OwnedScope] = Set.empty): Boolean =
    scope.live && !disposed(scope) && scope.parent.forall(valid(_, disposed))

  private def checkScope(scope: OwnedScope): Unit =
    mutation()
    if !valid(scope, active.fold(Set.empty[OwnedScope])(_.disposals.toSet)) then fail(KernelError.DisposedScope)

  private def checkSource(source: Source[?]): Unit =
    if (source.runtime ne this) then fail(KernelError.ForeignSignal)
    if !source.live || source.scope.exists(s => !valid(s, active.fold(Set.empty[OwnedScope])(_.disposals.toSet))) then
      fail(KernelError.DisposedScope)

  private def valueOf[A](value: Value[A]): A = value match
    case Value.Ok(a) => a
    case Value.Failed(error) => throw error

  private def guarded[A](body: => A): A = HeadlessRuntime.readOnly(this, false)(body)
  private def compute(derived: Signal.Derived[?], reader: Signal.Reader): Value[Any] =
    try guarded(derived.compute(reader).asInstanceOf[Value[Any]])
    catch case NonFatal(error) => Value.Failed(error)
  private def select(dynamic: Signal.Dynamic[?, ?], value: Any): Either[Throwable, Signal[?]] =
    try Right(guarded(dynamic.asInstanceOf[Signal.Dynamic[Any, Any]].select(value)))
    catch case NonFatal(error) => Left(error)
  private def same(eq: Equality[Any], a: Value[Any], b: Value[Any]): Boolean = (a, b) match
    case (Value.Ok(x), Value.Ok(y)) => guarded(eq.same(x, y))
    case (Value.Failed(x), Value.Failed(y)) => x eq y
    case _ => false
  private def report(callback: => Unit): Unit =
    try callback
    catch case NonFatal(error) => reported += error
  private def release(callback: () => Unit): Unit =
    HeadlessRuntime.readOnly(this, true)(report(callback()))

  private def createCell[A](initial: A, scope: Option[OwnedScope])(using equality: Equality[A]): Cell[A] =
    val source = new Source(initial, equality, scope)
    sources += source
    nodes(source) = new Node(source, Vector.empty, equality.asInstanceOf[Equality[Any]], Value.Ok(initial), source.committed)
    active.foreach(_.undo += (() => { sources -= source; nodes.remove(source); source.live = false }))
    source

  def cell[A](initial: A)(using Equality[A]): Cell[A] =
    installation()
    createCell(initial, None)

  /** Mutations without an explicit capability join the host's active staging. */
  private def edit[A](body: Transaction => A): A =
    mutation()
    active match
      case Some(t) => t.boundary(body(t))
      case None => transact { body(active.get) } match
        case CommitResult.Committed(value) => value
        case CommitResult.Failed(error) => throw error
        case CommitResult.Refused(reason) => throw new IllegalStateException(reason)
        case CommitResult.Joined(_) => throw new IllegalStateException("unexpected nested mount")

  /** Names are diagnostic; explicit mount supplies static positional identity. */
  def scope(name: String): OwnedScope = edit { t => createScope(name, None, None, t) }

  private def createScope(name: String, parent: Option[OwnedScope], identity: Option[ScopeIdentity], t: Transaction): OwnedScope =
    parent.foreach(checkScope)
    identity.foreach { key =>
      if scopes.values.exists(s => s.identity.contains(key) && valid(s, t.disposals.toSet)) then fail(KernelError.DuplicatePlacement)
    }
    val s = new OwnedScope(freshId(), name, parent, identity)
    scopes(s.id) = s
    t.undo += (() => { scopes.remove(s.id); s.live = false; s.generation += 1 })
    s

  final class OwnedScope private[HeadlessRuntime](val id: Long, val name: String,
      val parent: Option[OwnedScope], val identity: Option[ScopeIdentity]) extends Scope:
    private[HeadlessRuntime] var live = true
    private[HeadlessRuntime] var generation = 0L
    private[HeadlessRuntime] val pins = mutable.LinkedHashSet.empty[Signal[?]]
    private[HeadlessRuntime] val releases = mutable.ArrayBuffer.empty[() => Unit]
    def isDisposed: Boolean = { checkOwner(); !valid(this) }
    def cell[A](initial: A)(using Equality[A]): Cell[A] = edit { _ => checkScope(this); createCell(initial, Some(this)) }
    def child(name: String): OwnedScope = edit(t => createScope(name, Some(this), None, t))
    def mount(position: Int, definition: ScopeDefinition): OwnedScope = edit { t =>
      require(position >= 0, "nonnegative static position required")
      checkScope(this)
      scopes.values.find(s => s.identity.exists(i => i.owner == id && i.position == position) && valid(s, t.disposals.toSet)).foreach { existing =>
        if existing.identity.exists(_.definition eq definition) then fail(KernelError.DuplicatePlacement)
        t.disposals += existing
        t.memo.clear()
      }
      createScope(definition.name, Some(this), Some(ScopeIdentity(id, position, definition)), t)
    }
    def dispose()(using txn: UiTxn): Unit = transaction(txn).boundary {
      if live then
        active.get.disposals += this
        active.get.memo.clear()
    }
    def onDispose(callback: () => Unit): Unit = edit { t =>
      checkScope(this)
      releases += callback
      t.undo += (() => { releases -= callback; release(callback) })
    }
    def observe[A](signal: Signal[A])(callback: Value[A] => Unit): Subscription = edit { t =>
      checkScope(this)
      addWatch(signal, Some(this), value => callback(value.asInstanceOf[Value[A]]), Some(t))
    }
    def effect[A](signal: Signal[A])(run: A => Action): Unit =
      observe(signal) {
        case Value.Ok(value) => enqueue(Some(this), () => true, () => transact(run(value)))
        case Value.Failed(error) => reported += error
      }
      ()
    /** A generation-checked asynchronous delivery; stale delivery is a no-op. */
    def callback(run: Action): () => CommitResult[Unit] =
      checkScope(this)
      val stamp = generation
      () =>
        mutation()
        if !valid(this, active.fold(Set.empty[OwnedScope])(_.disposals.toSet)) || generation != stamp then CommitResult.Refused("disposed scope")
        else transact(run)
    def afterCommit(callback: () => Unit)(using txn: UiTxn): Unit =
      checkScope(this)
      val stamp = generation
      HeadlessRuntime.this.afterCommit(() => if valid(this) && generation == stamp then callback())
    /** Scoped pin, retired on scope disposal. */
    def read[A](signal: Signal[A]): Value[A] =
      checkScope(this)
      installation()
      pins += signal
      try
        ensureGraph()
        nodes(signal).value.asInstanceOf[Value[A]]
      catch case NonFatal(error) =>
        pins -= signal
        throw error
    private def deferred[A](feature: String): A = { checkScope(this); fail(KernelError.Deferred(feature)) }
    def keyed[K, A](initial: Seq[A])(key: A => K): KeyedCell[K, A] = deferred("keyed collections")
    def events[A]: EventSource[A] = deferred("events")
    def ref(): NodeRef = deferred("node references")
    def on[A](event: Event[A])(run: A => Action): Unit = deferred("events")
    def debounce[A](signal: Signal[A], quiet: FiniteDuration): Signal[A] = deferred("deadlines")
    def latest[I, O](input: Signal[I], policy: LatestPolicy)(work: I => Work[O]): Signal[TaskState[I, O]] = deferred("tasks")
    def progress[P](delivery: Delivery, capacity: Capacity)(receive: P => Action): ProgressChannel[P] = deferred("channels")
    def action[A](id: String, label: String, enabled: Signal[Boolean], enabledFor: Signal[A => Boolean])(run: A => Action): Command[A, Unit] = deferred("commands")
    def command[A, S, R](id: String, label: String, policy: WorkPolicy,
        enabled: CommandStatus[R] => Signal[Boolean], enabledFor: Signal[A => Boolean])(
        start: A => UiTxn ?=> Job[S, R])(complete: (S, CommandResult[R]) => Action): Command[A, R] = deferred("commands")
    def animate(target: Signal[Double], transition: Transition): Presentation[Double] = deferred("animation")
    def reparent(key: GlobalKey, content: View): Reparentable = deferred("reparenting")

  private def watchLive(w: Watch, disposed: Set[OwnedScope] = Set.empty): Boolean = w.live && w.scope.forall(valid(_, disposed))
  private def enqueue(scope: Option[OwnedScope], live: () => Boolean, run: () => CommitResult[Unit]): Unit =
    val stamp = scope.map(_.generation)
    effectQueue.enqueue(EffectJob(() => live() && scope.forall(s => valid(s) && stamp.contains(s.generation)), run))
  private def compactEffects(): Unit =
    val kept = effectQueue.filter(_.live()).toVector
    effectQueue.clear()
    effectQueue ++= kept
  private def addWatch(signal: Signal[?], scope: Option[OwnedScope], callback: Value[Any] => Unit, txn: Option[Transaction]): Subscription =
    val watch = new Watch(signal, scope, callback)
    watches += watch
    txn.foreach(_.undo += (() => { watch.live = false; watches -= watch }))
    new Subscription:
      def cancel(): Unit = edit { t =>
        val was = watch.live
        watch.live = false
        t.undo += (() => watch.live = was)
      }

  def observe[A](signal: Signal[A])(callback: Value[A] => Unit): Subscription =
    installation()
    edit(t => addWatch(signal, None, value => callback(value.asInstanceOf[Value[A]]), Some(t)))

  def effect[A](signal: Signal[A])(callback: A => Action): Subscription =
    var live = true
    val subscription = observe(signal) {
      case Value.Ok(value) => enqueue(None, () => live, () => transact(callback(value)))
      case Value.Failed(error) => reported += error
    }
    new Subscription:
      def cancel(): Unit =
        installation()
        subscription.cancel()
        live = false
        compactEffects()

  /** Explicit committed read pins this root until close. */
  def read[A](signal: Signal[A]): Value[A] =
    checkOwner()
    val permitted = HeadlessRuntime.phase.get().forall(p => p.notification && (p.runtime eq this) && notifying)
    if !permitted || (active.nonEmpty && !notifying) then fail(KernelError.ReadOnlyPhase)
    signal match
      case source: Source[?] => checkSource(source)
      case _ => ()
    if notifying then nodes.getOrElse(signal, throw KernelError.ReadOnlyPhase).value.asInstanceOf[Value[A]]
    else
      val existed = roots(signal)
      roots += signal
      try
        ensureGraph()
        nodes(signal).value.asInstanceOf[Value[A]]
      catch case NonFatal(error) =>
        if !existed then roots -= signal
        throw error

  def evaluations(signal: Signal[?]): Long = { checkOwner(); nodes.get(signal).fold(0L)(_.runs) }
  def diagnostics: Vector[Throwable] = { checkOwner(); reported.toVector }
  def lifetimes: LifetimeSnapshot =
    checkOwner()
    LifetimeSnapshot(scopes.values.filter(valid(_)).map(_.id).toSet,
      sources.filter(s => s.live && s.scope.forall(valid(_))).map(_.id).toSet,
      nodes.size, nodes.values.map(_.inputs.size).sum, watches.count(watchLive(_)), effectQueue.count(_.live()))

  def afterCommit(callback: () => Unit)(using txn: UiTxn): Unit = transaction(txn).boundary { active.get.after += callback }

  private final class Transaction extends UiTxn:
    val writes = mutable.LinkedHashMap.empty[Source[?], Any]
    val after = mutable.ArrayBuffer.empty[() => Unit]
    val memo = mutable.HashMap.empty[Signal[?], Value[Any]]
    val visiting = mutable.HashSet.empty[Signal[?]]
    val disposals = mutable.LinkedHashSet.empty[OwnedScope]
    val undo = mutable.ArrayBuffer.empty[() => Unit]
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
    def rollback(to: Int): Unit =
      undo.drop(to).reverseIterator.foreach(action => action())
      undo.dropRightInPlace(undo.size - to)
    private def candidateRevision(source: Source[?]): Revision =
      if source.committed && writes.get(source).exists(v => !same(source.equality.asInstanceOf[Equality[Any]], nodes(source).value, Value.Ok(v))) then source.revision.next
      else source.revision
    private val reader: Signal.Reader = new Signal.Reader:
      def apply[A](signal: Signal[A]): Value[A] = staged(signal).asInstanceOf[Value[A]]
      def revision(cell: Cell[?]): Revision = cell match
        case source: Source[?] => checkSource(source); candidateRevision(source)
        case _ => fail(KernelError.ForeignSignal)
    def staged(signal: Signal[?]): Value[Any] = memo.getOrElseUpdate(signal, {
      if visiting(signal) then
        poison = Some(KernelError.CycleDetected)
        fail(KernelError.CycleDetected)
      visiting += signal
      try signal match
        case source: Source[?] =>
          checkSource(source)
          writes.get(source).map(Value.Ok(_)).getOrElse(nodes(source).value)
        case constant: Signal.Constant[?] => Value.Ok(constant.value)
        case derived: Signal.Derived[?] => compute(derived, reader)
        case dynamic: Signal.Dynamic[?, ?] => staged(dynamic.input) match
          case Value.Failed(error) => Value.Failed(error)
          case Value.Ok(value) => select(dynamic, value) match
            case Left(error) => Value.Failed(error)
            case Right(inner) => staged(inner)
        case _ => fail(KernelError.ForeignSignal)
      finally visiting -= signal
    })
    private[sirin] def read[A](signal: Signal[A]): A = boundary { valueOf(staged(signal)).asInstanceOf[A] }
    def require(condition: Boolean, reason: => String): Unit = boundary { if !condition then refuse(reason) }
    def refuse(reason: String): Nothing =
      transaction(this)
      frames.last.refusal = Some(reason)
      throw new Refusal(reason)
    def attempt[A](body: UiTxn ?=> A): Attempt[A] = boundary {
      val saved = writes.toVector
      val savedAfter = after.size
      val savedUndo = undo.size
      val savedDisposals = disposals.toVector
      val frame = new Frame
      frames += frame
      try
        val value = body(using this)
        frame.refusal.fold[Attempt[A]](Attempt.Accepted(value))(Attempt.Refused(_))
      catch case refusal: Refusal => Attempt.Refused(refusal.reason)
      finally
        frames.remove(frames.size - 1)
        if frame.refusal.nonEmpty then
          writes.clear(); writes ++= saved
          disposals.clear(); disposals ++= savedDisposals
          after.dropRightInPlace(after.size - savedAfter)
          rollback(savedUndo)
          memo.clear()
    }

  def transact[A](body: UiTxn ?=> A): CommitResult[A] =
    mutation()
    active match
      case Some(txn) =>
        try CommitResult.Joined(txn.boundary(body(using txn)))
        catch
          case refusal: Refusal => CommitResult.Refused(refusal.reason)
          case NonFatal(error) => CommitResult.Failed(error)
      case None =>
        val txn = new Transaction
        active = Some(txn)
        var result: CommitResult[A] = null
        var releases = Vector.empty[() => Unit]
        try
          val value = txn.boundary(body(using txn))
          txn.poison match
            case Some(error) => result = CommitResult.Failed(error)
            case None => txn.frames.head.refusal match
              case Some(reason) => result = CommitResult.Refused(reason)
              case None => releases = publish(txn); result = CommitResult.Committed(value)
        catch
          case refusal: Refusal => result = txn.poison.fold[CommitResult[A]](CommitResult.Refused(refusal.reason))(CommitResult.Failed(_))
          case NonFatal(error) => result = CommitResult.Failed(error)
        finally
          if !result.isInstanceOf[CommitResult.Committed[?]] then txn.rollback(0)
          active = None
        result match
          case CommitResult.Committed(_) =>
            releases.foreach(release)
            notifying = true
            try HeadlessRuntime.readOnly(this, true)(txn.after.foreach(callback => report(callback())))
            finally notifying = false
          case _ => ()
        result

  private def graphRoots(disposed: Set[OwnedScope]): Vector[Signal[?]] =
    (sources.iterator.filter(s => s.live && s.scope.forall(valid(_, disposed))).map(s => s: Signal[?]) ++
      roots.iterator ++ scopes.valuesIterator.filter(valid(_, disposed)).flatMap(_.pins) ++
      watches.iterator.filter(watchLive(_, disposed)).map(_.signal)).toVector.distinct

  /** Values and topology are copied. Only the final settled graph is installed. */
  private def settle(writes: collection.Map[Source[?], Any], disposed: Set[OwnedScope]): mutable.LinkedHashMap[Signal[?], Node] =
    val work: mutable.LinkedHashMap[Signal[?], Node] = mutable.LinkedHashMap.from(nodes.iterator.map((s, n) => s -> n.copyNode()))
    val required = graphRoots(disposed)
    var dirty = Set.empty[Signal[?]]
    var pass = 0
    var pending = true
    while pending do
      if pass >= settleBudget then throw KernelError.BudgetExceeded
      pass += 1
      val seen = mutable.HashSet.empty[Signal[?]]
      val visiting = mutable.HashSet.empty[Signal[?]]
      val ordered = mutable.ArrayBuffer.empty[(Node, Int)]
      def attach(signal: Signal[?]): Int =
        if visiting(signal) then throw KernelError.CycleDetected
        if seen(signal) then ordered.find(_._1.signal.eq(signal)).get._2
        else
          visiting += signal
          val node = work.getOrElseUpdate(signal, signal match
            case source: Source[?] =>
              if source.runtime ne this then throw KernelError.ForeignSignal
              new Node(source, Vector.empty, source.equality.asInstanceOf[Equality[Any]], Value.Ok(source.initial), true)
            case constant: Signal.Constant[?] => new Node(signal, Vector.empty, constant.valueEquality.asInstanceOf[Equality[Any]], Value.Ok(constant.value), true)
            case derived: Signal.Derived[?] => new Node(signal, derived.inputs, derived.equality.asInstanceOf[Equality[Any]], Value.Failed(KernelError.BudgetExceeded), false)
            case dynamic: Signal.Dynamic[?, ?] => new Node(signal, Vector(dynamic.input), dynamic.equality.asInstanceOf[Equality[Any]], Value.Failed(KernelError.BudgetExceeded), false)
            case _ => throw KernelError.ForeignSignal)
          val height = 1 + node.inputs.map(attach).maxOption.getOrElse(-1)
          visiting -= signal
          seen += signal
          ordered += ((node, height))
          height
      required.foreach(attach)
      work.keysIterator.filterNot(seen).toVector.foreach(work.remove)
      val changed = mutable.HashSet.empty[Signal[?]]
      val edits = mutable.ArrayBuffer.empty[(Node, Option[Signal[?]])]
      val reader = new Signal.Reader:
        def apply[A](signal: Signal[A]): Value[A] = work(signal).value.asInstanceOf[Value[A]]
        def revision(cell: Cell[?]): Revision = cell match
          case source: Source[?] if source.runtime eq HeadlessRuntime.this =>
            if source.committed && writes.get(source).exists(v => !same(source.equality.asInstanceOf[Equality[Any]], nodes(source).value, Value.Ok(v))) then source.revision.next
            else source.revision
          case _ => throw KernelError.ForeignSignal
      def update(node: Node, next: Value[Any]): Unit =
        if !node.initialized || !same(node.equality, node.value, next) then
          node.value = next
          changed += node.signal
        node.initialized = true
      ordered.sortBy(_._2).foreach { (node, _) => node.signal match
        case source: Source[?] =>
          if !source.live || source.scope.exists(s => !valid(s, disposed)) then update(node, Value.Failed(KernelError.DisposedScope))
          else if !source.committed then update(node, Value.Ok(writes.getOrElse(source, source.initial)))
          else if pass == 1 then writes.get(source).foreach(v => update(node, Value.Ok(v)))
        case derived: Signal.Derived[?] if !node.initialized || dirty(node.signal) || node.inputs.exists(changed) =>
          node.runs += 1
          update(node, compute(derived, reader))
        case dynamic: Signal.Dynamic[?, ?] if !node.initialized || dirty(node.signal) || node.inputs.exists(changed) =>
          node.runs += 1
          if !node.selectionReady || changed(dynamic.input) then
            node.selectionReady = true
            val selection = reader(dynamic.input) match
              case Value.Failed(error) => Left(error)
              case Value.Ok(value) => select(dynamic, value)
            val next = selection.toOption
            node.selectionFailure = selection.swap.toOption
            if next != node.selected then edits += ((node, next))
          if !edits.exists(_._1.eq(node)) then
            update(node, node.selectionFailure.fold(node.selected.fold[Value[Any]](Value.Failed(KernelError.BudgetExceeded))(s => reader(s).asInstanceOf[Value[Any]]))(Value.Failed(_)))
        case _ => ()
      }
      edits.foreach { (node, selected) =>
        node.selected = selected
        val input = node.signal.asInstanceOf[Signal.Dynamic[?, ?]].input
        node.inputs = Vector(input) ++ selected
      }
      dirty = edits.map(_._1.signal).toSet
      pending = edits.nonEmpty
    work

  private def ensureGraph(): Unit =
    val graph = settle(Map.empty, Set.empty)
    nodes.clear(); nodes ++= graph

  private def publish(txn: Transaction): Vector[() => Unit] =
    val disposed = txn.disposals.toSet
    val candidate = settle(txn.writes, disposed)
    val revisions = txn.writes.iterator.collect {
      case (source, value) if source.committed && source.live && source.scope.forall(valid(_, disposed)) &&
          !same(source.equality.asInstanceOf[Equality[Any]], nodes(source).value, Value.Ok(value)) => source -> source.revision.next
    }.toVector
    val changed = candidate.iterator.collect {
      case (signal, node) if nodes.get(signal).forall(old => !same(node.equality, old.value, node.value)) => signal
    }.toSet
    val retiring = scopes.values.filter(s => !valid(s, disposed)).toVector.reverse
    val releases = retiring.flatMap(_.releases.toVector.reverse)
    // No application code between committing graph, revisions and lifetimes.
    nodes.clear(); nodes ++= candidate
    revisions.foreach((source, revision) => source.revision = revision)
    retiring.foreach { s => s.live = false; s.generation += 1; s.releases.clear(); s.pins.clear(); scopes.remove(s.id) }
    sources.filterInPlace(s => s.live && s.scope.forall(valid(_)))
    sources.foreach(_.committed = true)
    watches.filterInPlace(watchLive(_))
    compactEffects()
    notifying = true
    try HeadlessRuntime.readOnly(this, true) {
      watches.toVector.foreach { watch =>
        if watch.initial || changed(watch.signal) then
          watch.initial = false
          report(watch.deliver(nodes(watch.signal).value))
      }
    }
    finally notifying = false
    releases

  def flushEffects(budget: Int = 100): EffectDrain =
    installation()
    require(budget > 0, "positive effect transaction budget required")
    compactEffects()
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
    EffectDrain(count, effectQueue.count(_.live()), errors.result())

  def close(): Unit =
    installation()
    val releases = scopes.values.toVector.reverse.flatMap(_.releases.toVector.reverse)
    scopes.values.foreach { s => s.live = false; s.generation += 1; s.releases.clear(); s.pins.clear() }
    scopes.clear(); watches.clear(); effectQueue.clear(); roots.clear(); nodes.clear(); sources.clear()
    // Keep ownership guards active during release, then invalidate the host.
    releases.foreach(release)
    closed = true
