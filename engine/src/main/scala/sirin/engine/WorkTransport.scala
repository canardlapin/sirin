package sirin.engine

import sirin.*
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal

private[engine] trait ObservableCancellation extends Cancellation:
  def onCancel(wake: () => Unit): () => Unit

private[engine] final class WorkCancellation extends ObservableCancellation:
  private val flag = new AtomicBoolean(false)
  private val detached = new AtomicBoolean(false)
  private val lock = new Object
  private var listeners = Vector.empty[() => Unit]
  def cancelled: Boolean = flag.get()
  def retired: Boolean = detached.get()
  def onCancel(wake: () => Unit): () => Unit =
    val immediate = lock.synchronized {
      if flag.get() then true else { listeners :+= wake; false }
    }
    if immediate then wake()
    () => lock.synchronized { listeners = listeners.filterNot(_ eq wake) }
  def cancel(): Unit =
    flag.set(true)
    val wake = lock.synchronized { val saved = listeners; listeners = Vector.empty; saved }
    wake.foreach(_())
  def retire(): Unit = { detached.set(true); cancel() }

private[engine] final class Activity:
  private val lock = new Object
  private var sequence = 0L
  def stamp: Long = lock.synchronized(sequence)
  def signal(): Unit = lock.synchronized { sequence += 1L; lock.notifyAll() }
  def awaitAfter(before: Long, timeoutMillis: Long): Boolean = lock.synchronized {
    val deadline = System.nanoTime() + timeoutMillis * 1000000L
    var remaining = timeoutMillis
    while sequence == before && remaining > 0 do
      lock.wait(remaining)
      remaining = math.max(0L, (deadline - System.nanoTime() + 999999L) / 1000000L)
    sequence != before
  }

private[engine] object WorkExecution:
  def apply(work: Work[Any], token: Cancellation): Outcome[Any] =
    try Outcome.Succeeded(HeadlessRuntime.runWorker(Work.execute(work, token)))
    catch
      case _: InterruptedException if token.cancelled => Outcome.Cancelled
      case error: InterruptedException => Outcome.Failed(error)
      case NonFatal(error) => Outcome.Failed(error)

final case class WorkerPoolSnapshot(queued: Int, executing: Int, completed: Int, liveThreads: Int, closing: Boolean)
private[engine] final case class WorkerRequest(id: Long, work: Work[Any], token: WorkCancellation)
private[engine] final case class WorkerResult(id: Long, outcome: Outcome[Any])

/** The worker threads capture this transport, never the UI host or terminal callbacks. */
private[engine] final class WorkerTransport(parallelism: Int, capacity: Int, activity: Activity):
  private val lock = new Object
  private val pending = mutable.Queue.empty[WorkerRequest]
  private val results = mutable.Queue.empty[WorkerResult]
  private val executing = mutable.Map.empty[Long, WorkCancellation]
  private var closing = false
  private var alive = parallelism
  private val resultCapacity = Math.addExact(capacity, parallelism)
  private val threads = Vector.tabulate(parallelism) { i =>
    val thread = new Thread(() => loop(), s"sirin-worker-$i")
    thread.setDaemon(true)
    thread
  }
  def start(): Unit = lock.synchronized { threads.foreach(_.start()) }
  def submit(request: WorkerRequest): Boolean =
    lock.synchronized {
      if closing || pending.size >= capacity then false
      else { pending.enqueue(request); lock.notifyAll(); true }
    }
  def retire(id: Long): Unit = lock.synchronized {
    pending.filterInPlace(_.id != id)
    results.filterInPlace(_.id != id)
    lock.notifyAll()
  }
  def poll(): Option[WorkerResult] = lock.synchronized {
    val result = results.dequeueFirst(_ => true)
    if result.nonEmpty then lock.notifyAll()
    result
  }
  def hasResults: Boolean = lock.synchronized(results.nonEmpty)
  def snapshot: WorkerPoolSnapshot = lock.synchronized(WorkerPoolSnapshot(pending.size, executing.size, results.size, alive, closing))
  def shutdown(): Unit =
    val tokens = lock.synchronized {
      closing = true
      val queued = pending.dequeueAll(_ => true).toVector
      queued.filterNot(_.token.retired).foreach(r => results.enqueue(WorkerResult(r.id, Outcome.Cancelled)))
      lock.notifyAll()
      executing.values.toVector ++ queued.map(_.token)
    }
    tokens.foreach(_.cancel())
    activity.signal()
  def awaitStopped(timeoutMillis: Long): Boolean = lock.synchronized {
    val deadline = System.nanoTime() + timeoutMillis * 1000000L
    var remaining = timeoutMillis
    while alive != 0 && remaining > 0 do
      lock.wait(remaining)
      remaining = math.max(0L, (deadline - System.nanoTime() + 999999L) / 1000000L)
    alive == 0
  }
  private def loop(): Unit =
    try
      var continue = true
      while continue do
        val request = lock.synchronized {
          while pending.isEmpty && !closing do lock.wait()
          if pending.isEmpty then None
          else
            val next = pending.dequeue()
            executing(next.id) = next.token
            Some(next)
        }
        request match
          case None => continue = false
          case Some(job) =>
            val outcome = WorkExecution(job.work, job.token)
            val published = lock.synchronized {
              while results.size >= resultCapacity && !job.token.retired do lock.wait()
              val keep = !job.token.retired
              if keep then results.enqueue(WorkerResult(job.id, outcome))
              executing.remove(job.id)
              lock.notifyAll()
              keep
            }
            if published then activity.signal()
    finally
      lock.synchronized { alive -= 1; lock.notifyAll() }
      activity.signal()

/** Shutdown is nonblocking. Bounded physical retirement is an explicit operation. */
final class WorkerPool private[engine](transport: WorkerTransport, stop: () => Unit):
  def snapshot: WorkerPoolSnapshot = transport.snapshot
  def close(): Unit = stop()
  def awaitStopped(timeoutMillis: Long): Boolean =
    require(timeoutMillis >= 0 && timeoutMillis <= 60000, "await must be bounded to 60 seconds")
    transport.awaitStopped(timeoutMillis)
