package sirin.engine

import sirin.*
import scala.util.NotGiven

final case class ProgressQueueSnapshot(pending: Int, reserved: Int, accepted: Long, delivered: Long, coalesced: Long, abandoned: Long)
final case class ProgressDrain(delivered: Int, pending: Int, failures: Vector[CommitResult[Unit]])
final case class WorkPump(progress: ProgressDrain, completions: Vector[CommitResult[Unit]])

private[engine] final class ProgressEntry[P](val value: P, var reserver: Option[Object],
    var replaced: Option[ProgressEntry[P]] = None):
  var committed = reserver.isEmpty
  var consuming = false

/** Producer endpoint contains transport state only, with no UI host/receiver references. */
private[engine] final class ProgressTransport[P](delivery: Delivery, capacity: Int, ownerThread: Thread,
    activity: Activity) extends Progress[P]:
  private val lock = new Object
  private var queue = Vector.empty[ProgressEntry[P]]
  private var published = false
  private var frozen = false
  private var closed = false
  private var cancelled = Option.empty[Int]
  private var dead = false
  private var accepted = 0L
  private var delivered = 0L
  private var coalesced = 0L
  private var abandoned = 0L
  def freeze(): Unit = lock.synchronized { frozen = true }
  def thaw(): Unit =
    lock.synchronized { frozen = false; lock.notifyAll() }
    activity.signal()
  private def replaceable(reserver: Option[Object]): Boolean =
    delivery == Delivery.CoalesceLatest && queue.lastOption.exists(e => !e.consuming &&
      (e.committed || reserver.exists(t => e.reserver.exists(_ eq t))))
  private def refused(worker: Boolean): Option[Offered] =
    if dead || cancelled.nonEmpty then Some(Offered.Cancelled)
    else if closed || (worker && !published) then Some(Offered.Closed)
    else if worker && frozen then Some(Offered.Full)
    else None
  private def offer(value: P, reserver: Option[Object]): (Offered, Option[ProgressEntry[P]]) =
    refused(reserver.isEmpty) match
      case Some(result) => result -> None
      case None =>
        val room = queue.size < capacity
        if !room && !replaceable(reserver) then Offered.Full -> None
        else
          val replaced = if room then None else queue.lastOption
          val entry = new ProgressEntry(value, reserver, if reserver.nonEmpty then replaced else None)
          if room then queue :+= entry else queue = queue.init :+ entry
          if reserver.isEmpty then accepted = Math.addExact(accepted, 1L)
          if replaced.nonEmpty && reserver.isEmpty then coalesced = Math.addExact(coalesced, 1L)
          Offered.Accepted -> Some(entry)
  def tryEmit(value: P)(using NotGiven[UiTxn]): Offered =
    HeadlessRuntime.producerAccess()
    val result = lock.synchronized(offer(value, None)._1)
    if result == Offered.Accepted then activity.signal()
    result
  def emit(value: P)(using cancellation: Cancellation, noTransaction: NotGiven[UiTxn]): Offered =
    HeadlessRuntime.producerAccess()
    // Controlled execution on the UI owner may offer, but never wait for capacity.
    if Thread.currentThread() eq ownerThread then
      if cancellation.cancelled then Offered.Cancelled else tryEmit(value)
    else
      val wake = () => lock.synchronized { lock.notifyAll() }
      val unregister = cancellation match
        case token: ObservableCancellation => token.onCancel(wake)
        case _ => () => ()
      try
        val result = lock.synchronized {
          var outcome = Offered.Full
          while outcome == Offered.Full && !cancellation.cancelled do
            outcome = offer(value, None)._1
            if outcome == Offered.Full then cancellation match
              case _: ObservableCancellation => lock.wait()
              case _ => lock.wait(25L)
          if cancellation.cancelled && outcome == Offered.Full then Offered.Cancelled else outcome
        }
        if result == Offered.Accepted then activity.signal()
        result
      finally unregister()
  def reserve(value: P, key: Object): (Offered, Option[ProgressEntry[P]]) = lock.synchronized(offer(value, Some(key)))
  def undo(entry: ProgressEntry[P]): Unit =
    lock.synchronized {
      val at = queue.indexWhere(_ eq entry)
      if at >= 0 then queue = queue.patch(at, entry.replaced.toVector, 1)
      lock.notifyAll()
    }
    activity.signal()
  def head: Option[ProgressEntry[P]] = lock.synchronized(queue.headOption.filter(e => e.committed && !e.consuming))
  def leaseHead(): Option[ProgressEntry[P]] = lock.synchronized {
    val entry = queue.headOption.filter(e => e.committed && !e.consuming)
    entry.foreach(_.consuming = true)
    entry
  }
  def undoConsume(entry: ProgressEntry[P]): Unit =
    lock.synchronized { entry.consuming = false; lock.notifyAll() }
    activity.signal()
  def terminal: Boolean = lock.synchronized(cancelled.nonEmpty || (closed && queue.isEmpty) || dead)
  def candidate(close: Boolean, cancel: Boolean): ChannelState = lock.synchronized {
    val pending = queue.count(!_.consuming)
    cancelled match
      case Some(count) => ChannelState.Cancelled(count)
      case None if cancel => ChannelState.Cancelled(pending)
      case None if closed || close => if pending == 0 then ChannelState.Drained else ChannelState.Closing(pending)
      case None => ChannelState.Open
  }
  def commit(close: Boolean, cancel: Boolean): Unit = lock.synchronized {
    delivered = Math.addExact(delivered, queue.count(_.consuming).toLong)
    queue = queue.filterNot(_.consuming)
    queue.foreach { entry =>
      if !entry.committed then
        var current = Option(entry)
        var newlyAccepted = 0L
        var replaced = -1L
        while current.nonEmpty do
          val item = current.get
          if !item.committed then newlyAccepted += 1L
          replaced += 1L
          current = item.replaced
        accepted = Math.addExact(accepted, newlyAccepted)
        coalesced = Math.addExact(coalesced, replaced)
        entry.committed = true
        entry.reserver = None
        entry.replaced = None
    }
    if cancel && cancelled.isEmpty then
      val count = queue.size
      cancelled = Some(count)
      abandoned = Math.addExact(abandoned, count.toLong)
      queue = Vector.empty
    if close then closed = true
    published = true
    lock.notifyAll()
  }
  def dispose(): Unit =
    lock.synchronized {
      if !dead then
        abandoned = Math.addExact(abandoned, queue.size.toLong)
        queue = Vector.empty
        dead = true
        lock.notifyAll()
    }
    activity.signal()
  def snapshot: ProgressQueueSnapshot = lock.synchronized {
    ProgressQueueSnapshot(queue.size, queue.count(!_.committed), accepted, delivered, coalesced, abandoned)
  }

private[engine] trait ProgressBinding:
  def alive: Boolean
  def freeze(): Unit
  def thaw(): Unit
  def prepare()(using UiTxn): Unit
  def commit(): Unit
  def dispose(): Unit
  def canDrain: Boolean
  def drainOne(): Option[CommitResult[Unit]]
  def snapshot: ProgressQueueSnapshot

private[engine] final class RuntimeProgress[P](runtime: HeadlessRuntime)(owner: runtime.OwnedScope,
    delivery: Delivery, capacity: Capacity, receive: P => Action) extends ProgressChannel[P] with ProgressBinding:
  private val transport = new ProgressTransport[P](delivery, capacity.value, runtime.ownerThread, runtime.activity)
  private val status = owner.cell[ChannelState](ChannelState.Open)
  val state: Signal[ChannelState] = Inputs.from(status)
  val producer: Progress[P] = transport
  private var closeIntent = false
  private var cancelIntent = false
  def alive: Boolean = runtime.scopeAvailable(owner)
  def freeze(): Unit = transport.freeze()
  def thaw(): Unit = transport.thaw()
  def dispose(): Unit = transport.dispose()
  owner.onDispose(() => dispose())
  private def updateIntent(close: Boolean, cancel: Boolean): Unit =
    val beforeClose = closeIntent
    val beforeCancel = cancelIntent
    runtime.recordUndo(() => { closeIntent = beforeClose; cancelIntent = beforeCancel })
    closeIntent = close
    cancelIntent = cancel
  def offer(value: P)(using txn: UiTxn): Offered = runtime.capability(txn) {
    runtime.ensureScope(owner)
    if cancelIntent then Offered.Cancelled
    else if closeIntent then Offered.Closed
    else
      val (result, entry) = transport.reserve(value, runtime.reservationKey(txn))
      entry.foreach(e => runtime.recordUndo(() => transport.undo(e)))
      result
  }
  def close()(using txn: UiTxn): Unit = runtime.capability(txn) {
    runtime.ensureScope(owner)
    if !transport.terminal then
      updateIntent(true, cancelIntent)
      status.set(transport.candidate(closeIntent, cancelIntent))
  }
  def cancelDelivery()(using txn: UiTxn): Unit = runtime.capability(txn) {
    runtime.ensureScope(owner)
    if !transport.terminal then
      updateIntent(closeIntent, true)
      status.set(transport.candidate(closeIntent, cancelIntent))
  }
  def prepare()(using UiTxn): Unit = if alive then status.set(transport.candidate(closeIntent, cancelIntent))
  def commit(): Unit =
    transport.commit(closeIntent, cancelIntent || !alive)
    if !alive then transport.dispose()
  def canDrain: Boolean = alive && transport.head.nonEmpty
  def snapshot: ProgressQueueSnapshot = transport.snapshot
  def drainOne(): Option[CommitResult[Unit]] =
    var leased = false
    val result = runtime.transact {
      runtime.ensureScope(owner)
      transport.leaseHead().foreach { entry =>
        leased = true
        runtime.recordUndo(() => transport.undoConsume(entry))
        status.set(transport.candidate(closeIntent, cancelIntent))
        receive(entry.value)
      }
    }
    Option.when(leased)(result)
