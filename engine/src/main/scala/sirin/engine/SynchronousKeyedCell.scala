package sirin.engine

import sirin.*

/** Synchronous materialized source: O(n) updates, bounded range windows on scroll. */
private[engine] class SnapshotSource[K, A](val runtime: HeadlessRuntime)(val owner: runtime.OwnedScope,
    protected val data: Signal[Vector[(K, A)]], val revision: Signal[Revision])(using eq: Equality[A]) extends CollectionSource[K, A]:
  private val stream = runtime.newEvents[Changes[K]](owner).asInstanceOf[RuntimeEventSource[Changes[K]]]
  val changes: Event[Changes[K]] = stream
  private val observed = data.zip(revision).map((values, rev) => Versioned(values, rev))
  private var baseline = Option.empty[Versioned[Vector[(K, A)]]]
  runtime.registerCollection(new CommittedCollection:
    def alive: Boolean = runtime.scopeAvailable(owner)
    def dependency: Signal[?] = observed
    def prepare(read: Signal[?] => Option[Value[Any]]): Option[Occurrence] = read(observed) match
      case Some(Value.Ok(raw)) =>
        val next = raw.asInstanceOf[Versioned[Vector[(K, A)]]]
        val before = baseline
        if !before.exists(_.revision == next.revision) then
          runtime.recordUndo(() => baseline = before)
          baseline = Some(next)
        before.filter(_.revision != next.revision).map { prior =>
          val deltas = runtime.lawful(CollectionDelta(prior.value, next.value, SnapshotSource.this.eq))
          Occurrence(stream.origin, Changes(prior.revision, next.revision, deltas), runtime.transactionCause)
        }
      case Some(Value.Failed(_)) => None
      case _ => throw KernelError.ForeignSignal
  )
  def window(bounds: Signal[IndexRange]): Signal[CollectionWindow[K, A]] =
    data.zip(revision).zip(bounds).map { case ((values, rev), range) =>
      val from = range.from.min(values.size)
      val until = range.until.min(values.size)
      CollectionWindow(rev, from, values.size, values.slice(from, until))
    }(using (a, b) => a.revision == b.revision && a.first == b.first && a.total == b.total &&
      a.entries.size == b.entries.size && a.entries.zip(b.entries).forall((x, y) => x._1 == y._1 && eq.same(x._2, y._2)))
  def size(using txn: UiTxn): Int = runtime.capability(txn)(data.get.size)
  def keyAt(index: Int)(using txn: UiTxn): K = runtime.capability(txn)(data.get(index)._1)
  def indexOf(key: K)(using txn: UiTxn): Option[Int] = runtime.capability(txn) {
    val values = data.get
    val index = runtime.lawful(values.indexWhere(_._1 == key))
    Option.when(index >= 0)(index)
  }
  def get(key: K)(using txn: UiTxn): Option[A] = runtime.capability(txn) { val values = data.get; runtime.lawful(values.find(_._1 == key).map(_._2)) }
  def range(from: Int, until: Int)(using txn: UiTxn): IndexedSeq[(K, A)] = runtime.capability(txn) {
    val values = data.get
    require(from >= 0 && until >= from && until <= values.size, "invalid collection range")
    values.slice(from, until)
  }
  def at(key: K): Signal[Option[A]] = data.map(_.find(_._1 == key).map(_._2))(using (a, b) => (a, b) match
    case (Some(x), Some(y)) => eq.same(x, y)
    case (None, None) => true
    case _ => false)
  lazy val snapshot: Signal[IndexedSeq[A]] = data.map(_.map(_._2): IndexedSeq[A])(using (a, b) => a.size == b.size && a.zip(b).forall((x, y) => eq.same(x, y)))
  def keyedSnapshot: Signal[IndexedSeq[(K, A)]] = data
  def filterBy[Q](query: Signal[Q])(keep: (Q, A) => Boolean): CollectionSource[K, A] = runtime.declare(owner) { current =>
    val projected = data.zip(query).map((values, q) => values.filter((_, a) => keep(q, a)))(using (a, b) =>
      (a eq b) || (a.size == b.size && a.zip(b).forall((x, y) => x._1 == y._1 && eq.same(x._2, y._2))))
    val equality: Equality[Value[Vector[(K, A)]]] = (a, b) => if a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef] then true else (a, b) match
      case (Value.Ok(x), Value.Ok(y)) => projected.valueEquality.same(x, y)
      case (Value.Failed(x), Value.Failed(y)) => x eq y
      case _ => false
    val storage = current.cell[Value[Vector[(K, A)]]](Value.Ok(Vector.empty))(using equality)
    var initialized = false
    runtime.registerBinding(new WorkBinding:
      private var requestedCache = Option.empty[(Value[Vector[(K, A)]], Value[Q], Value[Vector[(K, A)]])]
      private def sameValue[B](a: Value[B], b: Value[B], eq: Equality[B]): Boolean = runtime.lawful {
        if a eq b then true else (a, b) match
          case (Value.Ok(x), Value.Ok(y)) => eq.same(x, y)
          case (Value.Failed(x), Value.Failed(y)) => x eq y
          case _ => false
      }
      def alive: Boolean = runtime.scopeAvailable(current)
      def dependencies: Vector[Signal[?]] = Vector(projected)
      def pending: Boolean = false
      def begin(): Unit = requestedCache = None
      def prepare()(using UiTxn): Unit =
        requestedCache = None
        if !initialized then
          runtime.recordUndo(() => initialized = false)
          initialized = true
      def reconcile(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean = (read(projected), read(storage)) match
        case (Some(next), Some(Value.Ok(previous))) =>
          val requested = runtime.stagedValue(query)
          val delivered = read(query).getOrElse(throw KernelError.ForeignSignal).asInstanceOf[Value[Q]]
          val sameQuery = sameValue(requested, delivered, query.valueEquality)
          val value: Value[Vector[(K, A)]] = if sameQuery then next.asInstanceOf[Value[Vector[(K, A)]]]
            else
              val source = read(data).getOrElse(throw KernelError.ForeignSignal).asInstanceOf[Value[Vector[(K, A)]]]
              requestedCache.filter((prior, q, _) => sameValue(prior, source, data.valueEquality) && sameValue(q, requested, query.valueEquality)) match
                case Some((_, _, cached)) => cached
                case None =>
                  val evaluated = (source, requested) match
                    case (_, Value.Failed(error)) => Value.Failed(error)
                    case (Value.Failed(error), _) => Value.Failed(error)
                    case (Value.Ok(values), Value.Ok(q)) =>
                      try runtime.lawful(Value.Ok(values.filter((_, a) => keep(q, a))))
                      catch case scala.util.control.NonFatal(error) => Value.Failed(error)
                  val before = requestedCache
                  runtime.recordUndo(() => requestedCache = before)
                  requestedCache = Some((source, requested, evaluated))
                  evaluated
          if runtime.lawful(equality.same(previous.asInstanceOf[Value[Vector[(K, A)]]], value)) then false
          else { storage.set(value); true }
        case _ => true
    )
    val lifted = new Signal.Derived(Vector(storage), reader => reader(storage) match
      case Value.Ok(value) => value
      case Value.Failed(error) => Value.Failed(error), projected.valueEquality)
    val revisions = new Signal.Derived(Vector(storage.versioned), reader => reader(storage.versioned) match
      case Value.Ok(Versioned(Value.Ok(_), revision)) => Value.Ok(revision)
      case Value.Ok(Versioned(Value.Failed(error), _)) => Value.Failed(error)
      case Value.Failed(error) => Value.Failed(error), Equality.universal[Revision])
    val speculativeData = new Signal.Speculative(lifted, reader => reader(projected))
    val speculativeRevision = new Signal.Speculative(revisions, reader =>
      (reader(storage.versioned), reader(projected)) match
        case (_, Value.Failed(error)) => Value.Failed(error)
        case (Value.Ok(Versioned(previous, revision)), next) =>
          Value.Ok(if !initialized || equality.same(previous, next) then revision else revision.next)
        case (Value.Failed(error), _) => Value.Failed(error))
    new SnapshotSource(runtime)(current, speculativeData, speculativeRevision)
  }

private[engine] object SynchronousKeyedCell:
  def create[K, A](runtime: HeadlessRuntime)(owner: runtime.OwnedScope, initial: Seq[A], key: A => K)(using eq: Equality[A]): SynchronousKeyedCell[K, A] =
    def keyed(values: Seq[A]): Vector[(K, A)] = runtime.lawful {
      val pairs = values.iterator.map(v => (key(v), v)).toVector
      if pairs.map(_._1).distinct.size != pairs.size then throw KernelError.DuplicateKey
      pairs
    }
    val storage = owner.cell(keyed(initial))(using (a, b) =>
      (a eq b) || (a.size == b.size && a.zip(b).forall((x, y) => x._1 == y._1 && eq.same(x._2, y._2))))
    new SynchronousKeyedCell(runtime)(owner, storage, key, keyed)

private[engine] final class SynchronousKeyedCell[K, A](host: HeadlessRuntime)(owner: host.OwnedScope, storage: Cell[Vector[(K, A)]], key: A => K,
    keyed: Seq[A] => Vector[(K, A)])(using Equality[A]) extends SnapshotSource(host)(owner, storage, storage.versioned.map(_.revision)) with KeyedCell[K, A]:
  private def position(k: K)(using UiTxn): Int =
    val values = storage.get
    val i = runtime.lawful(values.indexWhere(_._1 == k))
    if i < 0 then UiTxn.refuse("missing collection key")
    i
  def append(value: A)(using txn: UiTxn): Unit = runtime.capability(txn) { setAll(storage.get.map(_._2) :+ value) }
  def insert(index: Int, value: A)(using txn: UiTxn): Unit = runtime.capability(txn) {
    val values = storage.get.map(_._2)
    require(index >= 0 && index <= values.size, "invalid insertion index")
    setAll(values.patch(index, Vector(value), 0))
  }
  def remove(k: K)(using txn: UiTxn): Unit = runtime.capability(txn) { val values = storage.get; storage.set(runtime.lawful(values.filterNot(_._1 == k))) }
  def move(k: K, toIndex: Int)(using txn: UiTxn): Unit = runtime.capability(txn) {
    val values = storage.get
    val from = position(k)
    require(toIndex >= 0 && toIndex < values.size, "invalid move index")
    val item = values(from)
    storage.set(values.patch(from, Nil, 1).patch(toIndex, Vector(item), 0))
  }
  def replace(value: A)(using txn: UiTxn): Unit = runtime.capability(txn) {
    val k = runtime.lawful(key(value))
    storage.set(storage.get.updated(position(k), (k, value)))
  }
  def update(k: K)(f: A => A)(using txn: UiTxn): Unit = runtime.capability(txn) {
    val i = position(k)
    val values = storage.get
    val next = runtime.lawful(f(values(i)._2))
    if !runtime.lawful(key(next) == k) then throw KernelError.InvalidKeyChange
    storage.set(values.updated(i, (k, next)))
  }
  def setAll(values: Seq[A])(using txn: UiTxn): Unit = runtime.capability(txn) { storage.set(keyed(values)) }
