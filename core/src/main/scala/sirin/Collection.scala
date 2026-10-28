// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

enum Delta[+K]:
  case Inserted(key: K, index: Int)
  case Removed(key: K, index: Int)
  case Moved(key: K, from: Int, to: Int)
  case Replaced(key: K)

/** A batch of changes taking a source from one revision to the next. */
final case class Changes[+K](from: Revision, to: Revision, deltas: Seq[Delta[K]])

/** A keyed, revisioned source with key/index lookup, range access, and
  * change deltas (VISION §4). Reads are transactional.
  */
trait CollectionSource[K, +A]:
  def revision: Signal[Revision]
  def changes: Event[Changes[K]]
  def size(using UiTxn): Int
  def keyAt(index: Int)(using UiTxn): K
  def indexOf(key: K)(using UiTxn): Option[Int]
  def get(key: K)(using UiTxn): Option[A]
  def range(from: Int, until: Int)(using UiTxn): IndexedSeq[(K, A)]

  /** The live value under one key. */
  def at(key: K): Signal[Option[A]]

  /** An O(n) copy of the whole collection; named so the cost is visible. */
  def snapshot: Signal[IndexedSeq[A]]

  /** Explicit O(n) key/value copy for synchronous full-list reconciliation. */
  def keyedSnapshot: Signal[IndexedSeq[(K, A)]]
  def filterBy[Q](query: Signal[Q])(keep: (Q, A) => Boolean): CollectionSource[K, A]

/** A UI-owned keyed collection. Writes are transactional. */
trait KeyedCell[K, A] extends CollectionSource[K, A]:
  def append(value: A)(using UiTxn): Unit
  def insert(index: Int, value: A)(using UiTxn): Unit
  def remove(key: K)(using UiTxn): Unit
  def move(key: K, toIndex: Int)(using UiTxn): Unit

  /** Replaces the value under its (existing) key; identity is kept. */
  def replace(value: A)(using UiTxn): Unit
  def update(key: K)(f: A => A)(using UiTxn): Unit

  /** Replaces the contents, reconciling by key into minimal deltas. */
  def setAll(values: Seq[A])(using UiTxn): Unit

/** Synchronous fixed-height realization. Only viewport + overscan is mounted;
  * range/delta processing must not take a full snapshot. Selection belongs
  * to the caller, independently of row realization. Positive height required.
  */
object VirtualRows:
  def apply[K, A](source: CollectionSource[K, A], rowHeight: Lp, overscan: Int)(
      row: Inputs[A] => View
  ): View = ???
