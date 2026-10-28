package sirin.engine

import sirin.*
import scala.collection.mutable
import scala.util.control.NonFatal

final case class WorkTicket(id: Long, ready: Boolean, completed: Boolean, cancellationRequested: Boolean)

/** Explicit execution/delivery controls. No threads, polling or implicit I/O. */
final class ControlledScheduler private[engine](runtime: HeadlessRuntime, val capacity: Int):
  require(capacity > 0, "positive work capacity required")
  private final class Ticket(val id: Long, var work: Option[Work[Any]],
      val terminal: Outcome[Any] => CommitResult[Unit]) extends Cancellation:
    var published = false
    var ready = false
    var cancelled = false
    var result: Option[Outcome[Any]] = None
  private val entries = mutable.LinkedHashMap.empty[Long, Ticket]
  private var nextId = 0L
  private[engine] def available: Boolean = entries.size < capacity
  private[engine] def reserve[O](work: Work[O], terminal: Outcome[O] => CommitResult[Unit]): Long =
    if !available then UiTxn.refuse("work capacity full")(using runtime.currentTransaction)
    nextId = Math.addExact(nextId, 1L)
    val id = nextId
    entries(id) = new Ticket(id, Some(work.asInstanceOf[Work[Any]]), o => terminal(o.asInstanceOf[Outcome[O]]))
    runtime.recordUndo(() => entries.remove(id))
    id
  private[engine] def publish(id: Long): Unit = entries.get(id).foreach(_.published = true)
  private[engine] def ready(id: Long): Unit = entries.get(id).foreach(_.ready = true)
  private[engine] def cancel(id: Long, queued: Boolean): Unit = entries.get(id).foreach { ticket =>
    ticket.cancelled = true
    if queued && ticket.result.isEmpty then
      ticket.result = Some(Outcome.Cancelled)
      ticket.work = None
  }
  private[engine] def retire(id: Long): Unit = { entries.remove(id); () }
  private[engine] def clear(): Unit = entries.clear()
  def tickets: Vector[WorkTicket] =
    runtime.control()
    entries.valuesIterator.filter(_.published).map(t => WorkTicket(t.id, t.ready, t.result.nonEmpty, t.cancelled)).toVector
  def run(id: Long): Unit =
    runtime.control()
    val ticket = entries.getOrElse(id, throw new IllegalArgumentException("unknown work ticket"))
    require(ticket.published && ticket.ready && ticket.result.isEmpty, "work is not runnable")
    val work = ticket.work.get
    val outcome = try Outcome.Succeeded(runtime.worker(Work.execute(work, ticket)))
      catch
        case _: InterruptedException if ticket.cancelled => Outcome.Cancelled
        case error: InterruptedException => Outcome.Failed(error)
        case NonFatal(error) => Outcome.Failed(error)
    ticket.work = None
    ticket.result = Some(outcome)
  def deliver(id: Long): CommitResult[Unit] =
    runtime.control()
    entries.get(id) match
      case None => CommitResult.Refused("retired work ticket")
      case Some(ticket) =>
        val outcome = ticket.result.getOrElse(throw new IllegalStateException("work has not terminated"))
        ticket.terminal(outcome)
