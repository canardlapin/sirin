package sirin.engine

import sirin.*
import scala.collection.mutable

final case class WorkTicket(id: Long, ready: Boolean, completed: Boolean, cancellationRequested: Boolean)

/** Shared scheduling, with explicit controlled execution or an attached fixed worker pool. */
final class ControlledScheduler private[engine](runtime: HeadlessRuntime, val capacity: Int):
  require(capacity > 0, "positive work capacity required")
  private final class Ticket(val id: Long, var work: Option[Work[Any]],
      val terminal: Outcome[Any] => CommitResult[Unit]):
    val token = new WorkCancellation
    var published = false
    var ready = false
    var started = false
    var result: Option[Outcome[Any]] = None
  private val entries = mutable.LinkedHashMap.empty[Long, Ticket]
  private var nextId = 0L
  private var completionCursor = 0L
  private var pool = Option.empty[WorkerTransport]
  private[engine] def refusal: Option[String] =
    if pool.exists(_.snapshot.closing) then Some("worker executor closed")
    else Option.when(entries.size >= capacity)("work capacity full")
  private[engine] def available: Boolean = refusal.isEmpty
  private[engine] def reserve[O](work: Work[O], terminal: Outcome[O] => CommitResult[Unit]): Long =
    refusal.foreach(reason => UiTxn.refuse(reason)(using runtime.currentTransaction))
    nextId = Math.addExact(nextId, 1L)
    val id = nextId
    entries(id) = new Ticket(id, Some(work.asInstanceOf[Work[Any]]), o => terminal(o.asInstanceOf[Outcome[O]]))
    runtime.recordUndo(() => { entries.remove(id); () })
    id
  private def submit(ticket: Ticket): Unit =
    if ticket.published && ticket.ready && !ticket.started && ticket.result.isEmpty then pool.foreach { executor =>
      val request = WorkerRequest(ticket.id, ticket.work.get, ticket.token)
      if executor.submit(request) then { ticket.started = true; ticket.work = None }
    }
  private[engine] def publish(id: Long): Unit = entries.get(id).foreach { ticket => ticket.published = true; submit(ticket) }
  private[engine] def ready(id: Long): Unit = entries.get(id).foreach { ticket => ticket.ready = true; submit(ticket) }
  private[engine] def cancel(id: Long, queued: Boolean): Unit = entries.get(id).foreach { ticket =>
    ticket.token.cancel()
    if queued && !ticket.started && ticket.result.isEmpty then
      ticket.result = Some(Outcome.Cancelled)
      ticket.work = None
  }
  private[engine] def retire(id: Long): Unit = entries.remove(id).foreach { ticket =>
    ticket.token.retire()
    pool.foreach(_.retire(id))
  }
  private[engine] def clear(): Unit =
    entries.keys.toVector.foreach(retire)
    pool.foreach(_.shutdown())
  private[engine] def hasCompletions: Boolean = entries.values.exists(_.result.nonEmpty) || pool.exists(_.hasResults)
  private def collect(): Unit = pool.foreach { executor =>
    var next = executor.poll()
    while next.nonEmpty do
      val result = next.get
      entries.get(result.id).foreach(_.result = Some(result.outcome))
      next = executor.poll()
    entries.values.foreach(submit)
  }
  def startWorkers(parallelism: Int): WorkerPool =
    runtime.control()
    require(parallelism > 0 && parallelism <= 64, "worker count must be between 1 and 64")
    require(pool.isEmpty, "one worker pool per host")
    val transport = new WorkerTransport(parallelism, capacity, runtime.activity)
    pool = Some(transport)
    transport.start()
    entries.values.foreach(submit)
    new WorkerPool(transport, () => {
      runtime.control()
      entries.values.foreach { ticket =>
        ticket.token.cancel()
        if !ticket.started && ticket.result.isEmpty then
          ticket.work = None
          ticket.result = Some(Outcome.Cancelled)
      }
      transport.shutdown()
    })
  def tickets: Vector[WorkTicket] =
    runtime.control()
    collect()
    entries.valuesIterator.filter(_.published).map(t => WorkTicket(t.id, t.ready, t.result.nonEmpty, t.token.cancelled)).toVector
  def run(id: Long): Unit =
    runtime.control()
    require(pool.isEmpty, "controlled execution is unavailable with a worker pool")
    val ticket = entries.getOrElse(id, throw new IllegalArgumentException("unknown work ticket"))
    require(ticket.published && ticket.ready && !ticket.started && ticket.result.isEmpty, "work is not runnable")
    val work = ticket.work.get
    ticket.started = true
    ticket.work = None
    ticket.result = Some(WorkExecution(work, ticket.token))
  def deliver(id: Long): CommitResult[Unit] =
    runtime.control()
    collect()
    entries.get(id) match
      case None => CommitResult.Refused("retired work ticket")
      case Some(ticket) =>
        val outcome = ticket.result.getOrElse(throw new IllegalStateException("work has not terminated"))
        runtime.caused(CauseKind.Completion)(ticket.terminal(outcome))
  private[engine] def drain(budget: Int): Vector[CommitResult[Unit]] =
    collect()
    val ready = entries.valuesIterator.filter(_.result.nonEmpty).take(budget).map(_.id).toVector
    ready.map(deliver)

  /** Each driver turn supplies IDs already attempted, including failed bookkeeping. */
  private[engine] def drainOne(excluded: Set[Long]): Option[(Long, CommitResult[Unit])] =
    collect()
    val ready = entries.valuesIterator.filter(t => t.result.nonEmpty && !excluded(t.id)).map(_.id).toVector
    (ready.filter(_ > completionCursor) ++ ready.filter(_ <= completionCursor)).headOption.map { id =>
      completionCursor = id
      id -> deliver(id)
    }
