package sirin.engine

import sirin.*
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.{ControlThrowable, NonFatal}

trait CommittedRead extends Committed:
  def apply[A](signal: Signal[A]): Value[A]

enum KernelError(message: String) extends RuntimeException(message):
  case WrongThread extends KernelError("UI runtime used by a different thread")
  case Disposed extends KernelError("UI runtime is disposed")
  case DisposedScope extends KernelError("scope or provisional cell is disposed")
  case ExpiredTransaction extends KernelError("transaction capability is no longer active")
  case StaleWindow extends KernelError("range window does not match candidate revision or bounds")
  case DuplicateGlobalKey extends KernelError("duplicate retained global key")
  case DuplicateReference extends KernelError("node reference has multiple attachments")
  case ForeignNodeRef extends KernelError("node reference or panel belongs to another runtime")
  case ForeignEvent extends KernelError("event belongs to another runtime or is unsupported")
  case ForeignSignal extends KernelError("signal belongs to another runtime or is unsupported")
  case ReadOnlyPhase extends KernelError("mutation or undeclared read in a read-only phase")
  case CycleDetected extends KernelError("dependency cycle detected")
  case BudgetExceeded extends KernelError("topology settle pass budget exceeded")
  case DuplicateKey extends KernelError("duplicate sibling key")
  case InvalidKeyChange extends KernelError("replacement changed a stable key")
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
    edges: Int, subscriptions: Int, pendingEffects: Int, mounts: Int = 0)

object HeadlessRuntime:
  private final class ReadPhase(val runtime: HeadlessRuntime, val notification: Boolean, val constructionRoot: Option[Long], val worker: Boolean = false):
    var failure: Option[Throwable] = None
  private val phase = new ThreadLocal[Option[ReadPhase]]:
    override def initialValue(): Option[ReadPhase] = None
  private val transactions = new ThreadLocal[List[Throwable => Unit]]:
    override def initialValue(): List[Throwable => Unit] = Nil
  private[engine] def producerAccess(): Unit =
    if phase.get().exists(p => !p.worker) || transactions.get().nonEmpty then
      val error = KernelError.ReadOnlyPhase
      phase.get().foreach(_.failure = Some(error))
      transactions.get().headOption.foreach(_(error))
      throw error
  private[engine] def runWorker[A](body: => A): A =
    val previous = phase.get()
    val current = new ReadPhase(null, false, None, true)
    phase.set(Some(current))
    try
      val value = body
      current.failure.foreach(error => throw error)
      value
    finally phase.set(previous)
  private def readOnly[A](runtime: HeadlessRuntime, notification: Boolean, constructionRoot: Option[Long] = None)(body: => A): A =
    val previous = phase.get()
    val current = new ReadPhase(runtime, notification, constructionRoot)
    phase.set(Some(current))
    try
      val result = body
      current.failure.foreach(error => throw error)
      result
    finally phase.set(previous)

/** Deterministic single-thread host. Graph edits settle privately between passes.
  * Scoped resources are owned until transactional disposal. Desktop hosting,
  * native hosting and GPU retirement are separate stages.
  */
final class HeadlessRuntime(val settleBudget: Int = 100, workCapacity: Int = 256,
    val clock: MonotonicClock = MonotonicClock.system(), eventCapacity: Int = 4096, traceCapacity: Int = 256):
  require(settleBudget > 0, "positive settle pass budget required")
  require(eventCapacity > 0 && traceCapacity > 0, "positive event and trace capacities required")
  private val owner = Thread.currentThread()
  private[engine] def ownerThread: Thread = owner
  private[engine] val activity = new Activity
  private var publishing = false
  private var progressCursor = 0
  private var preferProgress = true
  private val progressBindings = mutable.ArrayBuffer.empty[ProgressBinding]
  private[engine] val docking = new DockingRuntime(this)
  private val eventHub = new EventHub(this, eventCapacity)
  private val changeBindings = mutable.ArrayBuffer.empty[CommittedCollection]
  private val traceRing = mutable.Queue.empty[TraceRecord]
  private def appendTrace(record: TraceRecord): Unit =
    traceRing.enqueue(record)
    while traceRing.size > traceCapacity do traceRing.dequeue()
  private var causeSerial = 0L
  private var causeContext = (CauseKind.Transaction, Option.empty[Long])
  private var activeTurns = 0L
  private var deliveredUnits = 0L
  private var transactionCount = 0L
  private var parkCount = 0L
  private var driverCursor = 0
  private var lastTime = 0L
  private val detachClock = clock match
    case manual: ManualClock => manual.onAdvance(() => activity.signal())
    case _ => () => ()
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
  private val mounts = mutable.ArrayBuffer.empty[MountedView]
  private val workBindings = mutable.ArrayBuffer.empty[WorkBinding]
  val scheduler = new ControlledScheduler(this, workCapacity)
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
      t.boundary { checkSource(this); t.writes(this) = value; t.invalidate(this) }
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
    if Thread.currentThread() ne owner then
      HeadlessRuntime.phase.get().filter(_.worker).foreach(_.failure = Some(KernelError.WrongThread))
      throw KernelError.WrongThread
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

  private[engine] def reservationKey(txn: UiTxn): Object = transaction(txn).reservationKey

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
  private def same(eq: Equality[Any], a: Value[Any], b: Value[Any]): Boolean = if a eq b then true else (a, b) match
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

  private def construct[A](scope: OwnedScope)(body: => A): A =
    checkOwner()
    HeadlessRuntime.phase.get() match
      case Some(p) if p.constructionRoot.nonEmpty =>
        def under(s: OwnedScope): Boolean = p.constructionRoot.contains(s.id) || s.parent.exists(under)
        if (p.runtime ne this) || !under(scope) then fail(KernelError.ReadOnlyPhase)
        HeadlessRuntime.phase.set(None)
        try body
        finally HeadlessRuntime.phase.set(Some(p))
      case _ => body

  private[engine] def control(): Unit = installation()
  private[engine] def currentTransaction: UiTxn = active.get
  private[engine] def nextInvocationId(): Long = freshId()
  private[engine] def note(error: Throwable): Unit = reported += error
  private[engine] def ensureScope(scope: OwnedScope): Unit = checkScope(scope)
  private[engine] def declare[A](scope: Scope)(body: OwnedScope => A): A = scope match
    case s: HeadlessRuntime#OwnedScope if s.runtime eq this =>
      val owned = s.asInstanceOf[OwnedScope]
      construct(owned) { edit { _ => checkScope(owned); body(owned) } }
    case _ => fail(KernelError.ForeignSignal)
  private[engine] def lawful[A](body: => A): A = guarded(body)
  private[engine] def capability[A](txn: UiTxn)(body: => A): A = transaction(txn).boundary(body)
  private[engine] def build[A](scope: OwnedScope)(body: => A): A =
    HeadlessRuntime.readOnly(this, false, Some(scope.id))(body)
  private[engine] def structuralEdit[A](body: UiTxn ?=> A): A = edit(t => body(using t))
  private[engine] def transactionCause: Long = active.get.cause
  private[engine] def caused[A](kind: CauseKind, parent: Option[Long] = None)(body: => A): A =
    val before = causeContext
    causeContext = kind -> parent
    try body finally causeContext = before
  private[engine] def timeNanos(): Long =
    val now = lawful(clock.nowNanos)
    require(now >= lastTime, "monotonic clock moved backwards")
    lastTime = now
    now
  private[engine] def stageOccurrence(origin: EventOrigin, value: Any): Unit =
    val txn = active.get
    UiTxn.require(txn.occurrences.size < eventCapacity, "event staging capacity full")(using txn)
    val index = txn.occurrences.size
    txn.occurrences += Occurrence(origin, value, txn.cause)
    txn.undo += (() => txn.occurrences.dropRightInPlace(txn.occurrences.size - index))
  private[engine] def stagedValue[A](signal: Signal[A])(using txn: UiTxn): Value[A] =
    try transaction(txn).staged(signal).asInstanceOf[Value[A]]
    catch case KernelError.DisposedScope => Value.Failed(KernelError.DisposedScope)
  private[engine] def registerBinding(binding: WorkBinding): Unit =
    workBindings += binding
    recordUndo(() => workBindings -= binding)
  private[engine] def registerCollection(binding: CommittedCollection): Unit =
    changeBindings += binding
    recordUndo(() => changeBindings -= binding)
  private[engine] def newEvents[A](owner: OwnedScope): EventSource[A] = eventHub.create(owner)
  private[engine] def recordUndo(action: () => Unit): Unit = active.get.undo += action
  private[engine] def invalidateReads(): Unit = active.get.clearMemo()
  private[engine] def scopeAvailable(scope: OwnedScope): Boolean =
    valid(scope, active.fold(Set.empty[OwnedScope])(_.disposals.toSet))

  private def mountedAvailable(mount: MountedView, disposed: Set[OwnedScope] = Set.empty): Boolean =
    (mount.runtime eq this) && scopes.get(mount.owner.id).exists(valid(_, disposed))

  def mount(owner: OwnedScope, view: View): MountedView=mountRoot(owner,view,None)
  private def mountRoot(owner: OwnedScope,view: View,quit: Option[UiTxn => CloseDecision]): MountedView = structuralEdit {
    checkScope(owner)
    val root = owner.child("headless-mount")
    val mounted = new MountedView(this)(root, view,quit)
    mounts += mounted
    recordUndo(() => mounts -= mounted)
    mounted
  }

  /** Mount the public window set under existing transactional reconciliation. */
  def mountWindows(owner: OwnedScope, windows: Windows): MountedView =
    mountRoot(owner, Column(Windows.MenuDescription(windows.applicationMenu), windows.view),windows.quitHandler)

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
    val runtime: HeadlessRuntime = HeadlessRuntime.this
    private[HeadlessRuntime] var live = true
    private[HeadlessRuntime] var generation = 0L
    private[HeadlessRuntime] val pins = mutable.LinkedHashSet.empty[Signal[?]]
    private[HeadlessRuntime] val releases = mutable.ArrayBuffer.empty[() => Unit]
    def isDisposed: Boolean = { checkOwner(); !valid(this) }
    def cell[A](initial: A)(using Equality[A]): Cell[A] = construct(this) { edit { _ => checkScope(this); createCell(initial, Some(this)) } }
    def child(name: String): OwnedScope = construct(this) { edit(t => createScope(name, Some(this), None, t)) }
    def mount(position: Int, definition: ScopeDefinition): OwnedScope = construct(this) { edit { t =>
      require(position >= 0, "nonnegative static position required")
      checkScope(this)
      scopes.values.find(s => s.identity.exists(i => i.owner == id && i.position == position) && valid(s, t.disposals.toSet)).foreach { existing =>
        if existing.identity.exists(_.definition eq definition) then fail(KernelError.DuplicatePlacement)
        t.disposals += existing
        t.clearMemo()
      }
      createScope(definition.name, Some(this), Some(ScopeIdentity(id, position, definition)), t)
    } }
    def dispose()(using txn: UiTxn): Unit = transaction(txn).boundary {
      if live then
        active.get.disposals += this
        active.get.clearMemo()
    }
    def onDispose(callback: () => Unit): Unit = construct(this) { edit { t =>
      checkScope(this)
      releases += callback
      t.undo += (() => { releases -= callback; release(callback) })
    } }
    def observe[A](signal: Signal[A])(callback: Value[A] => Unit): Subscription = construct(this) { edit { t =>
      checkScope(this)
      addWatch(signal, Some(this), value => callback(value.asInstanceOf[Value[A]]), Some(t))
    } }
    def effect[A](signal: Signal[A])(run: A => Action): Unit =
      observe(signal) {
        case Value.Ok(value) => enqueue(Some(this), () => true, () => transact(run(value)))
        case Value.Failed(error) => reported += error
      }
      ()
    /** A generation-checked asynchronous delivery; stale delivery is a no-op. */
    def callback(run: Action): () => CommitResult[Unit] =
      construct(this) { checkScope(this) }
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
    def keyed[K, A](initial: Seq[A])(key: A => K)(using Equality[A]): KeyedCell[K, A] = construct(this) { edit { _ =>
      checkScope(this)
      SynchronousKeyedCell.create(HeadlessRuntime.this)(this, initial, key)
    } }
    def events[A]: EventSource[A] = construct(this) { edit { _ =>
      checkScope(this)
      eventHub.create(this)
    } }
    def ref(): NodeRef = construct(this) { edit { _ =>
      checkScope(this)
      docking.createRef(this)
    } }
    def on[A](event: Event[A])(run: A => Action): Unit = construct(this) { edit { _ =>
      checkScope(this)
      eventHub.on(this, event, run)
    } }
    def debounce[A](signal: Signal[A], quiet: FiniteDuration): Signal[A] = construct(this) { edit { t =>
      checkScope(this)
      val nanos = quiet.toNanos
      require(quiet.length > 0 && nanos > 0 && BigInt(quiet.length) * quiet.unit.toNanos(1) == BigInt(nanos), "quiet period must be positive and representable")
      val binding = new DebounceRuntime(HeadlessRuntime.this)(this, signal, nanos)
      workBindings += binding
      t.undo += (() => workBindings -= binding)
      binding.signal
    } }
    def latest[I, O](input: Signal[I], policy: LatestPolicy)(work: I => Work[O]): Signal[TaskState[I, O]] = construct(this) { edit { t =>
      checkScope(this)
      val binding = new LatestRuntime(HeadlessRuntime.this)(this, input, policy, work)
      workBindings += binding
      t.undo += (() => workBindings -= binding)
      binding.signal
    } }
    def progress[P](delivery: Delivery, capacity: Capacity)(receive: P => Action): ProgressChannel[P] = construct(this) { edit { t =>
      checkScope(this)
      val binding = new RuntimeProgress(HeadlessRuntime.this)(this, delivery, capacity, receive)
      progressBindings += binding
      if publishing then binding.freeze()
      t.undo += (() => { progressBindings -= binding; binding.dispose() })
      binding
    } }
    def action[A](id: String, label: String, enabled: Signal[Boolean], enabledFor: Signal[A => Boolean])(run: A => Action): Command[A, Unit] = construct(this) { edit { _ =>
      checkScope(this)
      val noWork = Work(())
      new RuntimeCommand[A, Unit, Unit](HeadlessRuntime.this)(this, id, label, WorkPolicy.download,
        _ => enabled, enabledFor, true)(arg => { run(arg); Job((), noWork) })((_, _) => ())
    } }
    def command[A, S, R](id: String, label: String, policy: WorkPolicy,
        enabled: CommandStatus[R] => Signal[Boolean], enabledFor: Signal[A => Boolean])(
        start: A => UiTxn ?=> Job[S, R])(complete: (S, CommandResult[R]) => Action): Command[A, R] = construct(this) { edit { _ =>
      checkScope(this)
      new RuntimeCommand[A, S, R](HeadlessRuntime.this)(this, id, label, policy, enabled, enabledFor, false)(start)(complete)
    } }
    def animate(target: Signal[Double], transition: Transition): Presentation[Double] = deferred("animation")
    def reparent(key: GlobalKey, content: View): Reparentable = construct(this) { edit { _ =>
      checkScope(this)
      docking.createPanel(this, key, content)
    } }

  private def watchLive(w: Watch, disposed: Set[OwnedScope] = Set.empty): Boolean = w.live && w.scope.forall(valid(_, disposed))
  private def enqueue(scope: Option[OwnedScope], live: () => Boolean, run: () => CommitResult[Unit]): Unit =
    val stamp = scope.map(_.generation)
    val cause = active.map(_.cause)
    effectQueue.enqueue(EffectJob(() => live() && scope.forall(s => valid(s) && stamp.contains(s.generation)),
      () => caused(CauseKind.Effect, cause)(run())))
    activity.signal()
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

  /** Backend capture over declared nodes. The reader expires after this call. */
  def withCommitted[A](body: CommittedRead => A): A =
    control()
    ensureGraph()
    var live = true
    val reader = new CommittedRead:
      def apply[B](signal: Signal[B]): Value[B] =
        checkOwner()
        if !live then throw KernelError.ExpiredTransaction
        nodes.getOrElse(signal, throw KernelError.ReadOnlyPhase).value.asInstanceOf[Value[B]]
    try HeadlessRuntime.readOnly(this, false)(body(reader))
    finally live = false
  /** Wake-only backend notification; may run on a worker, never under a transport lock. */
  def onActivity(wake: () => Unit): Subscription =
    installation()
    val remove = activity.onWake(() => HeadlessRuntime.readOnly(this, false)(wake()))
    new Subscription:
      def cancel(): Unit = { installation(); remove() }
  def wakeFailures: Long = { control(); activity.wakeFailures }

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
      nodes.size, nodes.values.map(_.inputs.size).sum, watches.count(watchLive(_)), effectQueue.count(_.live()), mounts.count(m => mountedAvailable(m, active.fold(Set.empty[OwnedScope])(_.disposals.toSet))))

  def afterCommit(callback: () => Unit)(using txn: UiTxn): Unit = transaction(txn).boundary { active.get.after += callback }

  private final class Transaction extends UiTxn:
    causeSerial = Math.addExact(causeSerial, 1L)
    val cause = causeSerial
    val context = causeContext
    var graphPasses = 0
    var structuralPasses = 0
    val occurrences = mutable.ArrayBuffer.empty[Occurrence]
    val reservationKey = new Object
    val writes = mutable.LinkedHashMap.empty[Source[?], Any]
    val after = mutable.ArrayBuffer.empty[() => Unit]
    val memo = mutable.HashMap.empty[Signal[?], Value[Any]]
    val visiting = mutable.HashSet.empty[Signal[?]]
    private val readStack = mutable.ArrayBuffer.empty[Signal[?]]
    private val memoParents = mutable.HashMap.empty[Signal[?], mutable.HashSet[Signal[?]]]
    private val memoChildren = mutable.HashMap.empty[Signal[?], mutable.HashSet[Signal[?]]]
    private def noteRead(signal: Signal[?]): Unit = readStack.lastOption.foreach { parent =>
      memoParents.getOrElseUpdate(signal, mutable.HashSet.empty) += parent
      memoChildren.getOrElseUpdate(parent, mutable.HashSet.empty) += signal
    }
    def invalidate(source: Signal[?]): Unit =
      val pending = mutable.Queue(source)
      val seen = mutable.HashSet.empty[Signal[?]]
      while pending.nonEmpty do
        val signal = pending.dequeue()
        if seen.add(signal) then
          memo.remove(signal)
          memoParents.get(signal).foreach(_.foreach(pending.enqueue(_)))
    def clearMemo(): Unit = { memo.clear(); memoParents.clear(); memoChildren.clear() }
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
        case source: Source[?] => noteRead(source); checkSource(source); candidateRevision(source)
        case _ => fail(KernelError.ForeignSignal)
    def staged(signal: Signal[?]): Value[Any] =
      noteRead(signal)
      memo.getOrElseUpdate(signal, {
      if visiting(signal) then
        poison = Some(KernelError.CycleDetected)
        fail(KernelError.CycleDetected)
      memoChildren.remove(signal).foreach(_.foreach { child =>
        memoParents.get(child).foreach { parents =>
          parents -= signal
          if parents.isEmpty then memoParents.remove(child)
        }
      })
      visiting += signal
      readStack += signal
      try signal match
        case source: Source[?] =>
          checkSource(source)
          writes.get(source).map(Value.Ok(_)).getOrElse(nodes(source).value)
        case constant: Signal.Constant[?] => Value.Ok(constant.value)
        case projected: Signal.Speculative[?] =>
          try
            staged(projected.delivered) match
              case Value.Failed(error: KernelError) => throw error
              case _ => () // Ordinary application failure may recover speculatively.
            guarded(projected.speculative(reader)).asInstanceOf[Value[Any]]
          catch
            case failure: Signal.InputFailure => Value.Failed(failure.error)
            case error: KernelError => throw error
            case NonFatal(error) => Value.Failed(error)
        case input: Signal.Input[?] => staged(input.delivered) match
          case Value.Failed(error) => Value.Failed(error)
          case Value.Ok(_) =>
            try staged(guarded(input.speculative(reader)))
            catch
              case failure: Signal.InputFailure => Value.Failed(failure.error)
              case error: KernelError => throw error
              case NonFatal(error) => Value.Failed(error)
        case derived: Signal.Derived[?] => compute(derived, reader)
        case dynamic: Signal.Dynamic[?, ?] => staged(dynamic.input) match
          case Value.Failed(error) => Value.Failed(error)
          case Value.Ok(value) => select(dynamic, value) match
            case Left(error) => Value.Failed(error)
            case Right(inner) => staged(inner)
        case _ => fail(KernelError.ForeignSignal)
      finally
        readStack.remove(readStack.size - 1)
        visiting -= signal
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
          clearMemo()
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
        val previousGuards = HeadlessRuntime.transactions.get()
        HeadlessRuntime.transactions.set((error => if txn.poison.isEmpty then txn.poison = Some(error)) :: previousGuards)
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
          docking.beginPass()
          active = None
          HeadlessRuntime.transactions.set(previousGuards)
        transactionCount += 1L
        val outcome = result match
          case CommitResult.Committed(_) => TraceOutcome.Committed
          case CommitResult.Refused(_) => TraceOutcome.Refused
          case CommitResult.Failed(KernelError.CycleDetected) => TraceOutcome.CycleDetected
          case CommitResult.Failed(KernelError.BudgetExceeded) => TraceOutcome.BudgetExceeded
          case _ => TraceOutcome.Failed
        appendTrace(TraceRecord(txn.cause, txn.context._2, txn.context._1, outcome, txn.graphPasses, txn.structuralPasses))
        result match
          case CommitResult.Committed(_) =>
            releases.foreach(release)
            notifying = true
            try HeadlessRuntime.readOnly(this, true)(txn.after.foreach(callback => report(callback())))
            finally notifying = false
            if scheduler.available && workBindings.exists(b => b.alive && b.pending) then
              transact(()) match
                case CommitResult.Failed(error) => reported += error
                case _ => ()
          case _ => ()
        result

  private def graphRoots(disposed: Set[OwnedScope]): Vector[Signal[?]] =
    (sources.iterator.filter(s => s.live && s.scope.forall(valid(_, disposed))).map(s => s: Signal[?]) ++
      docking.dependencies.iterator ++ roots.iterator ++ scopes.valuesIterator.filter(valid(_, disposed)).flatMap(_.pins) ++
      watches.iterator.filter(watchLive(_, disposed)).map(_.signal) ++
      mounts.iterator.filter(m => mountedAvailable(m, disposed)).flatMap(_.dependencies) ++
      workBindings.iterator.filter(_.alive).flatMap(_.dependencies) ++
      changeBindings.iterator.filter(_.alive).map(_.dependency)).toVector.distinct

  /** Values and topology are copied. Only the final settled graph is installed. */
  private def settle(writes: collection.Map[Source[?], Any], disposed: Set[OwnedScope],
      base: collection.Map[Signal[?], Node] = nodes): mutable.LinkedHashMap[Signal[?], Node] =
    val work: mutable.LinkedHashMap[Signal[?], Node] = mutable.LinkedHashMap.from(base.iterator.map((s, n) => s -> n.copyNode()))
    val required = graphRoots(disposed)
    var dirty = Set.empty[Signal[?]]
    var pass = 0
    var pending = true
    while pending do
      if pass >= settleBudget then throw KernelError.BudgetExceeded
      pass += 1
      active.foreach(_.graphPasses += 1)
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
              new Node(source, Vector.empty, source.equality.asInstanceOf[Equality[Any]], Value.Ok(source.initial), source.committed)
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
    publishing = true
    progressBindings.foreach(_.freeze())
    try publishCandidate(txn)
    finally
      publishing = false
      progressBindings.foreach(_.thaw())

  private def publishCandidate(txn: Transaction): Vector[() => Unit] =
    progressBindings.foreach(_.prepare()(using txn))
    workBindings.foreach(_.begin())
    var candidate = settle(txn.writes, txn.disposals.toSet)
    var structuralPass = 0
    var changedStructure = true
    while changedStructure do
      if structuralPass >= settleBudget then throw KernelError.BudgetExceeded
      structuralPass += 1
      txn.structuralPasses += 1
      changedStructure = false
      docking.beginPass()
      val read: Signal[?] => Option[Value[Any]] = s => candidate.get(s).map(_.value)
      workBindings.toVector.filter(_.alive).foreach { binding =>
        if binding.reconcile(read)(using txn) then changedStructure = true
      }
      // Settle materialized projections before making irreversible row-lifetime decisions.
      if !changedStructure then
        mounts.toVector.filter(m => mountedAvailable(m, txn.disposals.toSet)).foreach { mount =>
          if mount.reconcile(read)(using txn) then changedStructure = true
        }
        if docking.prepare(read)(using txn) then changedStructure = true
      if changedStructure then candidate = settle(txn.writes, txn.disposals.toSet, candidate)
    workBindings.toVector.filter(_.alive).foreach(_.prepare()(using txn))
    candidate = settle(txn.writes, txn.disposals.toSet, candidate)
    val changes = changeBindings.toVector.filter(_.alive).flatMap(_.prepare(s => candidate.get(s).map(_.value)))
    val publishEvents = eventHub.prepare(txn.occurrences.toVector ++ changes)
    val disposed = txn.disposals.toSet
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
    mounts.filterInPlace(m => mountedAvailable(m))
    workBindings.filterInPlace(_.alive)
    progressBindings.foreach(_.commit())
    progressBindings.filterInPlace(_.alive)
    changeBindings.filterInPlace(_.alive)
    publishEvents()
    docking.commit()
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

  def progressQueues: Vector[ProgressQueueSnapshot] =
    control()
    progressBindings.filter(_.alive).map(_.snapshot).toVector

  def drainProgress(budget: Int = 100): ProgressDrain =
    control()
    require(budget > 0, "positive progress delivery budget required")
    var delivered = 0
    var blocked = false
    val failures = Vector.newBuilder[CommitResult[Unit]]
    while delivered < budget && !blocked do
      val bindings = progressBindings.toVector
      val start = if bindings.isEmpty then 0 else Math.floorMod(progressCursor, bindings.size)
      val next = (bindings.drop(start) ++ bindings.take(start)).find(_.canDrain)
      next.foreach(b => progressCursor = bindings.indexOf(b) + 1)
      next match
        case None => blocked = true
        case Some(binding) => caused(CauseKind.Progress)(binding.drainOne()) match
          case Some(CommitResult.Committed(_)) => delivered += 1
          case Some(result) => failures += result; blocked = true
          case None => blocked = true
    ProgressDrain(delivered, progressBindings.filter(_.alive).map(_.snapshot.pending).sum, failures.result())

  def pump(budget: Int = 100): WorkPump =
    control()
    require(budget > 0, "positive pump budget required")
    val hasCompletions = scheduler.hasCompletions
    val progressBudget = if !hasCompletions then budget else if budget > 1 then budget / 2 else
      preferProgress = !preferProgress
      if preferProgress then 1 else 0
    val progress = if progressBudget > 0 then drainProgress(progressBudget)
      else ProgressDrain(0, progressBindings.filter(_.alive).map(_.snapshot.pending).sum, Vector.empty)
    WorkPump(progress, scheduler.drain(math.max(0, budget - progress.delivered)))

  /** Backend event-loop wait in this runtime's clock domain: -1 means no deadline. */
  def activityWaitMillis: Int =
    control()
    val now=timeNanos()
    if hasReady(now) then 0
    else nextDeadline.fold(-1) { due =>
      val delta=math.max(0L,due-now)
      (delta/1000000L+(if delta%1000000L==0 then 0 else 1)).min(Int.MaxValue.toLong).toInt
    }

  def awaitActivity(timeoutMillis: Long): Boolean =
    control()
    require(timeoutMillis >= 0 && timeoutMillis <= 60000, "activity wait must be bounded to 60 seconds")
    val stamp = activity.stamp
    val now = timeNanos()
    if hasReady(now) then true
    else
      val waitMillis = boundedWaitMillis(timeoutMillis, now)
      parkCount += 1L
      val signalled = activity.awaitAfter(stamp, waitMillis)
      signalled || hasReady(timeNanos())

  private[engine] def boundedWaitMillis(timeoutMillis: Long, now: Long): Long = clock match
    case _: ManualClock => timeoutMillis
    case _ => nextDeadline.fold(timeoutMillis) { due =>
      val delta = math.max(0L, due - now)
      val ceiling = delta / 1000000L + (if delta % 1000000L == 0 then 0L else 1L)
      math.min(timeoutMillis, ceiling)
    }
  private[engine] def deadlines: Vector[DeadlineBinding] = workBindings.collect { case d: DeadlineBinding if d.alive => d }.toVector
  private def nextDeadline: Option[Long] = deadlines.flatMap(_.due).minOption
  private def hasReady(now: Long): Boolean = eventHub.pending > 0 || effectQueue.exists(_.live()) ||
    progressBindings.exists(_.canDrain) || scheduler.hasCompletions || nextDeadline.exists(_ <= now)
  /** Backend resolves transactional intents after settle against its complete interaction tree. */
  def takeFocusRequests(): Vector[Long] = {control();docking.takeFocusRequests()}
  def publishFocus(references: Set[Long],ancestors: Set[Long],keyboard: Boolean)(using txn: UiTxn): Unit =
    capability(txn)(docking.publishFocus(references,ancestors,keyboard))
  def attachments: Vector[RefAttachment] = { control(); docking.attachments }
  def traces: Vector[TraceRecord] = { control(); traceRing.toVector }
  def driverSnapshot: DriverSnapshot =
    control()
    DriverSnapshot(activeTurns, deliveredUnits, transactionCount, parkCount, eventHub.pending,
      effectQueue.count(_.live()), deadlines.count(_.due.nonEmpty), nextDeadline, traceRing.size)
  def drainEvents(budget: Int = 100): EventDrain =
    control()
    require(budget > 0, "positive event budget required")
    var count = 0
    val failures = Vector.newBuilder[CommitResult[Unit]]
    while count < budget && eventHub.pending > 0 do
      eventHub.drainOne().foreach { result =>
        count += 1
        result match
          case CommitResult.Committed(_) => ()
          case other => failures += other
      }
    EventDrain(count, eventHub.pending, failures.result())
  def drive(budget: Int = 100): DriverTurn =
    control()
    require(budget > 0, "positive driver budget required")
    var count = 0
    var empty = 0
    var blockedProgress = false
    var attemptedCompletions = Set.empty[Long]
    val failures = Vector.newBuilder[CommitResult[Unit]]
    def outcome(result: CommitResult[Unit]): Unit = result match
      case CommitResult.Committed(_) => ()
      case other => failures += other
    while count < budget && empty < 5 do
      val kind = driverCursor
      driverCursor = (driverCursor + 1) % 5
      var executed = false
      kind match
        case 0 => eventHub.drainOne().foreach { result => executed = true; outcome(result) }
        case 1 =>
          val now = timeNanos()
          deadlines.filter(_.due.exists(_ <= now)).sortBy(_.due.get).headOption.foreach { binding =>
            executed = true; outcome(binding.fire())
          }
        case 2 =>
          if !blockedProgress then
            val receipt = drainProgress(1)
            executed = receipt.delivered > 0 || receipt.failures.nonEmpty
            blockedProgress = receipt.failures.nonEmpty
            receipt.failures.foreach(failures += _)
        case 3 =>
          scheduler.drainOne(attemptedCompletions).foreach { (id, result) =>
            attemptedCompletions += id
            executed = true
            outcome(result)
          }
        case _ =>
          compactEffects()
          if effectQueue.nonEmpty then
            executed = true
            outcome(effectQueue.dequeue().run())
      if executed then { count += 1; empty = 0 } else empty += 1
    if count > 0 then { activeTurns += 1L; deliveredUnits += count }
    val ready = hasReady(timeNanos())
    val errors = failures.result()
    val exhausted = count == budget && ready
    if count > 0 then
      causeSerial = Math.addExact(causeSerial, 1L)
      appendTrace(TraceRecord(causeSerial, None, CauseKind.Driver,
        if exhausted then TraceOutcome.BudgetExceeded else if errors.nonEmpty then TraceOutcome.Failed else TraceOutcome.Committed,
        0, 0, count))
    DriverTurn(count, ready, nextDeadline, errors, exhausted)

  def close(): Unit =
    installation()
    detachClock()
    activity.clearWakes()
    docking.clear()
    eventHub.clear()
    changeBindings.clear()
    progressBindings.foreach(_.dispose())
    progressBindings.clear()
    val releases = scopes.values.toVector.reverse.flatMap(_.releases.toVector.reverse)
    scopes.values.foreach { s => s.live = false; s.generation += 1; s.releases.clear(); s.pins.clear() }
    scopes.clear(); watches.clear(); mounts.clear(); workBindings.clear(); scheduler.clear(); effectQueue.clear(); roots.clear(); nodes.clear(); sources.clear()
    // Keep ownership guards active during release, then invalidate the host.
    releases.foreach(release)
    closed = true
