package sirin.engine

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.NotGiven
import sirin.UiTxn
import java.util.concurrent.atomic.AtomicLong

trait MonotonicClock:
  def nowNanos: Long
object MonotonicClock:
  def system(): MonotonicClock = new MonotonicClock:
    private val origin = System.nanoTime()
    def nowNanos: Long = System.nanoTime() - origin

/** Thread-safe test clock. Advance wakes hosts but never runs UI callbacks. */
final class ManualClock(initialNanos: Long = 0L) extends MonotonicClock:
  require(initialNanos >= 0, "negative clock origin")
  private val now = new AtomicLong(initialNanos)
  private val lock = new Object
  private val listeners = mutable.ArrayBuffer.empty[() => Unit]
  def nowNanos: Long = now.get()
  private[engine] def onAdvance(wake: () => Unit): () => Unit =
    lock.synchronized { listeners += wake }
    () => lock.synchronized { listeners -= wake }
  def advanceBy(duration: FiniteDuration)(using NotGiven[UiTxn]): Unit =
    HeadlessRuntime.producerAccess()
    require(duration.length >= 0, "clock cannot move backwards")
    val nanos = duration.toNanos
    require(nanos >= 0 && BigInt(duration.length) * duration.unit.toNanos(1) == BigInt(nanos), "clock duration overflow")
    var changed = false
    while !changed do
      val before = now.get()
      changed = now.compareAndSet(before, Math.addExact(before, nanos))
    val wake = lock.synchronized(listeners.toVector)
    wake.foreach(_())
