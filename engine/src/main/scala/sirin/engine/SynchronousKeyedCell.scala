package sirin.engine

import sirin.*

/** O(n) synchronous snapshots; projected revision/delta streams and viewport realization remain deferred. */
private[engine] class SnapshotSource[K, A](val runtime: HeadlessRuntime, protected val data: Signal[Vector[(K, A)]])(using eq: Equality[A]) extends CollectionSource[K, A]:
  def revision: Signal[Revision] = throw KernelError.Deferred("derived collection revisions")
  def changes: Event[Changes[K]] = throw KernelError.Deferred("collection change events")
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
  def filterBy[Q](query: Signal[Q])(keep: (Q, A) => Boolean): CollectionSource[K, A] =
    val filtered = data.zip(query).map((values, q) => values.filter((_, a) => keep(q, a)))(using (a, b) =>
      a.size == b.size && a.zip(b).forall((x, y) => x._1 == y._1 && eq.same(x._2, y._2)))
    new SnapshotSource(runtime, filtered)

private[engine] object SynchronousKeyedCell:
  def create[K, A](runtime: HeadlessRuntime)(owner: runtime.OwnedScope, initial: Seq[A], key: A => K)(using eq: Equality[A]): SynchronousKeyedCell[K, A] =
    def keyed(values: Seq[A]): Vector[(K, A)] = runtime.lawful {
      val pairs = values.iterator.map(v => (key(v), v)).toVector
      if pairs.map(_._1).distinct.size != pairs.size then throw KernelError.DuplicateKey
      pairs
    }
    val storage = owner.cell(keyed(initial))(using (a, b) =>
      a.size == b.size && a.zip(b).forall((x, y) => x._1 == y._1 && eq.same(x._2, y._2)))
    new SynchronousKeyedCell(runtime)(owner, storage, key, keyed)

private[engine] final class SynchronousKeyedCell[K, A](host: HeadlessRuntime)(owner: host.OwnedScope, storage: Cell[Vector[(K, A)]], key: A => K,
    keyed: Seq[A] => Vector[(K, A)])(using Equality[A]) extends SnapshotSource(host, storage) with KeyedCell[K, A]:
  private val stream = host.newEvents[Changes[K]](owner).asInstanceOf[RuntimeEventSource[Changes[K]]]
  override val changes: Event[Changes[K]] = stream
  private val versioned = storage.versioned
  private var baseline = Option.empty[Versioned[Vector[(K, A)]]]
  host.registerCollection(new CommittedCollection:
    def alive: Boolean = host.scopeAvailable(owner)
    def dependency: Signal[?] = versioned
    def prepare(read: Signal[?] => Option[Value[Any]]): Option[Occurrence] =
      read(versioned) match
        case Some(Value.Ok(raw)) =>
          val next = raw.asInstanceOf[Versioned[Vector[(K, A)]]]
          val before = baseline
          host.recordUndo(() => baseline = before)
          baseline = Some(next)
          before.filter(_.revision != next.revision).map { prior =>
            val deltas = host.lawful(CollectionDelta(prior.value, next.value, summon[Equality[A]]))
            Occurrence(stream.origin, Changes(prior.revision, next.revision, deltas), host.transactionCause)
          }
        case _ => throw KernelError.ForeignSignal
  )
  override lazy val revision: Signal[Revision] = storage.versioned.map(_.revision)
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
