package sirin.engine

import sirin.*
import scala.collection.mutable

private[engine] final class EventOrigin(val host: HeadlessRuntime)(val owner: host.OwnedScope):
  var valid = true
  def alive: Boolean = valid && host.scopeAvailable(owner)
private[engine] final case class Occurrence(origin: EventOrigin, value: Any, cause: Long)
private[engine] class EventRecipe[A](val origin: EventOrigin, val select: Any => Option[A]) extends Event[A]:
  def map[B](f: A => B): Event[B] = new EventRecipe(origin, value => select(value).map(f))
  def filter(p: A => Boolean): Event[A] = new EventRecipe(origin, value => select(value).filter(p))
private[engine] final class RuntimeEventSource[A](source: EventOrigin)
    extends EventRecipe[A](source, value => Some(value.asInstanceOf[A])) with EventSource[A]:
  def emit(value: A)(using txn: UiTxn): Unit = origin.host.capability(txn) {
    origin.host.ensureScope(origin.owner)
    if !origin.valid then throw KernelError.DisposedScope
    origin.host.stageOccurrence(origin, value)
  }
final case class EventDrain(delivered: Int, pending: Int, failures: Vector[CommitResult[Unit]])
private[engine] final class EventHub(val runtime: HeadlessRuntime, capacity: Int):
  private final class Listener(val owner: runtime.OwnedScope, val event: EventRecipe[Any], val run: Any => Action):
    def alive: Boolean = runtime.scopeAvailable(owner) && event.origin.alive
  private final case class Job(listener: Listener, occurrence: Occurrence)
  private val listeners = mutable.ArrayBuffer.empty[Listener]
  private val queue = mutable.Queue.empty[Job]
  def create[A](owner: HeadlessRuntime#OwnedScope): RuntimeEventSource[A] =
    val origin = new EventOrigin(runtime)(owner.asInstanceOf[runtime.OwnedScope])
    runtime.recordUndo(() => origin.valid = false)
    new RuntimeEventSource(origin)
  def on[A](owner: HeadlessRuntime#OwnedScope, event: Event[A], run: A => Action): Unit = event match
    case recipe: EventRecipe[?] if recipe.origin.host eq runtime =>
      if !recipe.origin.alive then throw KernelError.DisposedScope
      val listener = new Listener(owner.asInstanceOf[runtime.OwnedScope], recipe.asInstanceOf[EventRecipe[Any]], value => run(value.asInstanceOf[A]))
      listeners += listener
      runtime.recordUndo(() => listeners -= listener)
    case _ => throw KernelError.ForeignEvent
  private def compact(): Unit =
    listeners.filterInPlace(_.alive)
    queue.filterInPlace(_.listener.alive)
  /** Pure preflight, called before graph/lifetime installation. */
  def prepare(occurrences: Vector[Occurrence]): () => Unit =
    val live = listeners.filter(_.alive).toVector
    val jobs = Vector.newBuilder[Job]
    var count = queue.count(_.listener.alive)
    occurrences.foreach { occurrence =>
      if occurrence.origin.alive then
        live.foreach { listener =>
          if listener.event.origin eq occurrence.origin then
            count += 1
            UiTxn.require(count <= capacity, "event delivery capacity full")(using runtime.currentTransaction)
            jobs += Job(listener, occurrence)
        }
    }
    val accepted = jobs.result()
    () =>
      compact()
      queue ++= accepted
      if accepted.nonEmpty then runtime.activity.signal()
  def pending: Int = { compact(); queue.size }
  def drainOne(): Option[CommitResult[Unit]] =
    compact()
    queue.dequeueFirst(_ => true).map { job =>
      runtime.caused(CauseKind.Event, Some(job.occurrence.cause)) {
        runtime.transact {
          runtime.lawful(job.listener.event.select(job.occurrence.value)).foreach(job.listener.run(_))
        }
      }
    }
  def clear(): Unit = { queue.clear(); listeners.clear() }

private[engine] trait CommittedCollection:
  def alive: Boolean
  def dependency: Signal[?]
  def prepare(read: Signal[?] => Option[Value[Any]]): Option[Occurrence]

/** Source batches use a longest retained subsequence to avoid moving stable keys. */
private[engine] object CollectionDelta:
  def apply[K, A](before: Vector[(K, A)], after: Vector[(K, A)], equality: Equality[A]): Vector[Delta[K]] =
    val next = after.toMap
    val old = before.toMap
    val changes = Vector.newBuilder[Delta[K]]
    var working = before.map(_._1)
    before.foreach { (key, _) =>
      if !next.contains(key) then
        changes += Delta.Removed(key, working.indexOf(key))
        working = working.filterNot(_ == key)
    }
    val desired = after.map(_._1).filter(old.contains)
    val positions = working.zipWithIndex.toMap
    // Patience sorting, with predecessor links, chooses an LIS deterministically.
    val tails = mutable.ArrayBuffer.empty[Int]
    val previous = Array.fill(desired.size)(-1)
    desired.indices.foreach { i =>
      val position = positions(desired(i))
      var lo = 0
      var hi = tails.size
      while lo < hi do
        val mid = (lo + hi) / 2
        if positions(desired(tails(mid))) < position then lo = mid + 1 else hi = mid
      if lo > 0 then previous(i) = tails(lo - 1)
      if lo == tails.size then tails += i else tails(lo) = i
    }
    var keep = Set.empty[K]
    var cursor = tails.lastOption.getOrElse(-1)
    while cursor >= 0 do
      keep += desired(cursor)
      cursor = previous(cursor)
    var anchor = Option.empty[K]
    desired.reverseIterator.foreach { key =>
      if !keep(key) then
        val from = working.indexOf(key)
        val without = working.patch(from, Nil, 1)
        val to = anchor.fold(without.size)(without.indexOf)
        working = without.patch(to, Vector(key), 0)
        if from != to then changes += Delta.Moved(key, from, to)
      anchor = Some(key)
    }
    after.zipWithIndex.foreach { case ((key, _), index) =>
      if !old.contains(key) then
        working = working.patch(index, Vector(key), 0)
        changes += Delta.Inserted(key, index)
    }
    after.foreach { (key, value) => if old.get(key).exists(prior => !equality.same(prior, value)) then changes += Delta.Replaced(key) }
    changes.result()
