package sirin.probe.threads

import java.util.concurrent.atomic.AtomicBoolean

import scala.scalanative.unsigned.*

/** Monotonic clock shared with the C side. */
inline def nowNs(): Long = TShim.sirin_now_ns().toLong

/** A Scala-created thread: marks itself for the shim guard before running `body`. */
abstract class ScalaThread(name: String) extends Thread(name):
  protected def body(): Unit
  final override def run(): Unit =
    TShim.sirin_thread_mark_scala()
    body()

/** Allocates heavily: short-lived garbage plus a rotating ring of survivors, so
  * that marking has real work. Optionally pushes an SDL user event carrying a
  * timestamp every `pushEveryNs`, and calls `System.gc()` every `gcEvery`
  * iterations.
  */
final class Allocator(
    id: Int,
    slot: Int,
    stop: AtomicBoolean,
    pushType: Long,
    pushEveryNs: Long,
    gcEvery: Long
) extends ScalaThread(s"alloc-$id"):
  @volatile var iterations: Long = 0L
  @volatile var bytes: Long      = 0L
  @volatile var pushes: Long     = 0L
  @volatile var checksum: Long   = 0L

  protected def body(): Unit =
    TShim.sirin_hb_activate(slot.toUInt, 1)
    val ring  = new Array[AnyRef](2048)
    var x     = id * 7919 + 1
    var it    = 0L
    var by    = 0L
    var pu    = 0L
    var acc   = 0L
    var next  = nowNs() + pushEveryNs
    while !stop.get() do
      x = x * 1103515245 + 12345
      val n = 16 + ((x >>> 8) & 1023)
      val a = new Array[Byte](n)
      a(0) = x.toByte
      a(n - 1) = x.toByte
      ring((it & 2047).toInt) = a
      val l = List(it, it + 1, it + 2)
      val s = java.lang.Long.toString(it)
      acc += l.sum + s.length + a(0)
      by += n + 3 * 32 + 48
      it += 1
      if (it & 255) == 0 then
        TShim.sirin_hb(slot.toUInt)
        if pushType >= 0 then
          val t = nowNs()
          if t >= next then
            TShim.sirin_push_user(pushType.toUInt, id, t.toULong, pu.toULong)
            pu += 1
            next = t + pushEveryNs
        iterations = it
        bytes = by
        pushes = pu
      if gcEvery > 0 && it % gcEvery == 0 then System.gc()
    iterations = it
    bytes = by
    pushes = pu
    checksum = acc
    TShim.sirin_hb_activate(slot.toUInt, 0)

/** Sleeps 1 ms in a loop and records how late it wakes. A thread returning
  * from a sleep into managed code waits at the safepoint while a
  * stop-the-world collection runs, so oversleep bounds the observed pause.
  */
final class Ticker(stop: AtomicBoolean) extends ScalaThread("ticker"):
  @volatile var maxOversleepNs: Long = 0L
  @volatile var over5ms: Long        = 0L
  @volatile var over20ms: Long       = 0L
  @volatile var over50ms: Long       = 0L
  @volatile var ticks: Long          = 0L

  def reset(): Unit =
    maxOversleepNs = 0L; over5ms = 0L; over20ms = 0L; over50ms = 0L

  protected def body(): Unit =
    while !stop.get() do
      val t0 = nowNs()
      Thread.sleep(1)
      val over = nowNs() - t0 - 1000000L
      if over > maxOversleepNs then maxOversleepNs = over
      if over > 5000000L then over5ms += 1
      if over > 20000000L then over20ms += 1
      if over > 50000000L then over50ms += 1
      ticks += 1

/** A set of allocators plus a ticker sharing one stop flag. */
final class Stress(workers: Int, firstSlot: Int, pushType: Long, pushEveryNs: Long, gcEvery: Long):
  val stop    = new AtomicBoolean(false)
  val allocs  = (0 until workers).map(i => new Allocator(i, firstSlot + i, stop, pushType, pushEveryNs, gcEvery))
  val ticker  = new Ticker(stop)
  private val gc0 = GcCounters.read()

  def start(): this.type =
    allocs.foreach(_.start())
    ticker.start()
    this

  def finish(): StressReport =
    stop.set(true)
    allocs.foreach(_.join())
    ticker.join()
    val gc = GcCounters.read()
    StressReport(
      iterations = allocs.map(_.iterations).sum,
      bytes = allocs.map(_.bytes).sum,
      pushes = allocs.map(_.pushes).sum,
      collections = gc.collections - gc0.collections,
      gcNs = gc.durationNs - gc0.durationNs,
      maxOversleepNs = ticker.maxOversleepNs,
      over5ms = ticker.over5ms,
      over20ms = ticker.over20ms,
      over50ms = ticker.over50ms
    )

final case class StressReport(
    iterations: Long,
    bytes: Long,
    pushes: Long,
    collections: Long,
    gcNs: Long,
    maxOversleepNs: Long,
    over5ms: Long,
    over20ms: Long,
    over50ms: Long
):
  def lines(prefix: String): Seq[String] = Seq(
    s"$prefix.alloc_iterations=$iterations",
    f"$prefix.alloc_gib=${bytes / 1073741824.0}%.2f",
    s"$prefix.gc_collections=$collections",
    f"$prefix.gc_mean_ms=${if collections == 0 then 0.0 else gcNs / 1e6 / collections}%.3f",
    f"$prefix.gc_total_ms=${gcNs / 1e6}%.1f",
    f"$prefix.ticker_max_oversleep_ms=${maxOversleepNs / 1e6}%.3f",
    s"$prefix.ticker_over_5ms=$over5ms",
    s"$prefix.ticker_over_20ms=$over20ms",
    s"$prefix.ticker_over_50ms=$over50ms"
  )

final case class GcCounters(collections: Long, durationNs: Long)

object GcCounters:
  def read(): GcCounters =
    GcCounters(
      SnGc.scalanative_GC_stats_collection_total().toLong,
      SnGc.scalanative_GC_stats_collection_duration_total().toLong
    )

/** Latency histogram with 10 µs bins up to 500 ms. */
final class Histogram:
  private val binNs  = 10000L
  private val bins   = new Array[Long](50001)
  var count: Long    = 0L
  var maxNs: Long    = 0L

  def record(ns: Long): Unit =
    val v = if ns < 0 then 0L else ns
    val b = math.min(v / binNs, (bins.length - 1).toLong).toInt
    bins(b) += 1
    count += 1
    if v > maxNs then maxNs = v

  def percentileMs(p: Double): Double =
    if count == 0 then 0.0
    else
      val target = math.ceil(p * count).toLong
      var acc    = 0L
      var i      = 0
      while i < bins.length && acc + bins(i) < target do
        acc += bins(i)
        i += 1
      (i + 1) * binNs / 1e6
