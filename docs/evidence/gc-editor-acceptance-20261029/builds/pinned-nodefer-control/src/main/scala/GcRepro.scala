import java.util.concurrent.atomic.AtomicBoolean
import scala.scalanative.unsafe.*

@extern
object Counters:
  def scalanative_GC_get_used_heapsize(): CSize = extern
  def scalanative_GC_stats_collection_total(): CSize = extern

/** Only Scala Native/JDK APIs: no Sirin, SDL, mailbox, or custom C code. */
object GcRepro:
  final class Worker(id: Int, stopped: AtomicBoolean, slots: Int) extends Thread(s"alloc-$id"):
    // Keep the bounded live set observable after join, including in optimized builds.
    val ring = new Array[Array[Byte]](slots)
    @volatile var iterations = 0L
    var checksum = 0L
    override def run(): Unit =
      var x = id * 7919 + 1
      var it = 0L
      var acc = 0L
      while !stopped.get() do
        x = x * 1103515245 + 12345
        val n = 16 + ((x >>> 8) & 1023)
        val a = new Array[Byte](n)
        a(0) = x.toByte
        a(n - 1) = x.toByte
        if slots > 0 then ring((it % slots).toInt) = a
        val l = List(it, it + 1, it + 2)
        val s = java.lang.Long.toString(it)
        acc += l.sum + s.length + a(0)
        it += 1
        if (it & 255) == 0 then iterations = it
      iterations = it
      checksum = acc

  def main(args: Array[String]): Unit =
    require(args.length >= 2 && args.length <= 4,
      "usage: gc-repro seconds workers [survivor-slots=2048] [ticker=true]")
    val seconds = args(0).toInt
    val count = args(1).toInt
    val slots = args.lift(2).map(_.toInt).getOrElse(2048)
    val tickerEnabled = args.lift(3).map(_.toBoolean).getOrElse(true)
    require(seconds > 0 && count > 0 && slots >= 0)
    val stopped = new AtomicBoolean(false)
    val workers = Array.tabulate(count)(new Worker(_, stopped, slots))
    val ticker = new Thread("ticker"):
      override def run(): Unit =
        while !stopped.get() do Thread.sleep(1)
    val start = System.nanoTime()
    def report(label: String): Unit =
      println(s"$label elapsed_ms=${(System.nanoTime() - start) / 1000000} workers=$count slots=$slots ticker=$tickerEnabled heap_bytes=${Counters.scalanative_GC_get_used_heapsize().toLong} collections=${Counters.scalanative_GC_stats_collection_total().toLong} iterations=${workers.map(_.iterations).sum}")
      Console.flush()
    report("START")
    workers.foreach(_.start())
    if tickerEnabled then ticker.start()
    val deadline = start + seconds * 1000000000L
    var next = start + 5000000000L
    while System.nanoTime() < deadline do
      Thread.sleep(100)
      if System.nanoTime() >= next then
        report("SAMPLE")
        next += 5000000000L
    stopped.set(true)
    workers.foreach(_.join())
    if tickerEnabled then ticker.join()
    var liveBytes = 0L
    workers.foreach { w =>
      w.ring.foreach { a =>
        if a != null then
          require(a.nonEmpty && a(0) == a(a.length - 1), "survivor corrupted")
          liveBytes += a.length
      }
    }
    report("DONE")
    println(s"RESULT live_payload_bytes=$liveBytes checksum=${workers.map(_.checksum).sum}")
