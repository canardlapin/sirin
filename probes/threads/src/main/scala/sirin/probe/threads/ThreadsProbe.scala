package sirin.probe.threads

import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** P0.6 and P0.7 probes. Every result line starts with `RESULT `; the process
  * exits non-zero if any checked criterion fails.
  *
  * {{{
  * sirin-probe-threads soak [seconds=600] [workers=4] [push_every_us=1000, 0 = none]
  *                                                    P0.6 GC stress while main waits in SDL
  * sirin-probe-threads reentry blocking|plain [seconds=30] [callbacks_per_call=50]
  *                                                    P0.6 Scala callback inside an extern
  * sirin-probe-threads cost                             P0.6 @blocking vs plain extern
  * sirin-probe-threads foreign [n=1000000] [cases=abcd] P0.7 foreign-thread entry
  * sirin-probe-threads guard-abort                      P0.7 guard must abort this process
  * }}}
  */
object ThreadsProbe:
  private var failures = 0

  private def result(line: String): Unit = println(s"RESULT $line")

  private def check(name: String, ok: Boolean, detail: => String = ""): Unit =
    if !ok then failures += 1
    result(s"$name=${if ok then "PASS" else "FAIL"}${if detail.isEmpty then "" else s" ($detail)"}")

  def main(args: Array[String]): Unit =
    TShim.sirin_thread_mark_scala()
    args.toList match
      case "soak" :: rest =>
        val secs    = rest.headOption.map(_.toInt).getOrElse(600)
        val workers = rest.drop(1).headOption.map(_.toInt).getOrElse(4)
        val pushUs  = rest.drop(2).headOption.map(_.toLong).getOrElse(1000L)
        soak(secs, workers, pushUs)
      case "reentry" :: kind :: rest =>
        reentry(kind, rest.headOption.map(_.toInt).getOrElse(30), rest.drop(1).headOption.map(_.toInt).getOrElse(50))
      case "cost" :: Nil             => cost()
      case "foreign" :: rest =>
        val n     = rest.headOption.map(_.toLong).getOrElse(1000000L)
        val cases = rest.drop(1).headOption.getOrElse("abcd")
        foreign(n, cases)
      case "guard-abort" :: Nil =>
        println("calling a guarded Scala entry from a foreign pthread; expect SIGABRT")
        Console.flush()
        TShim.sirin_guard_foreign_call_inprocess()
        println("guard did not abort")
        failures += 1
      case _ =>
        System.err.println("usage: soak [s] [workers] [push_us] | reentry blocking|plain [s] [per_call] | cost | foreign [n] [cases] | guard-abort")
        failures += 1
    result(s"failures=$failures")
    Console.flush()
    if failures > 0 then System.exit(1)

  // -------------------------------------------------------------------------
  // SDL session: video init, hidden window, a user event type, one event buffer.

  final class Session:
    val sdl: SdlValues = SdlValues.load()
    if !Sdl.SDL_Init(sdl.initVideo.toUInt) then
      throw new IllegalStateException(s"SDL_Init failed: ${fromCString(Sdl.SDL_GetError())}")
    val window: Ptr[Byte] = Sdl.SDL_CreateWindow(c"sirin-threads", 64, 64, sdl.windowHidden.toULong)
    require(window != null, s"SDL_CreateWindow failed: ${fromCString(Sdl.SDL_GetError())}")
    val base: Long        = Sdl.SDL_RegisterEvents(2).toLong
    require(base != 0 && base != 0xffffffffL, "SDL_RegisterEvents failed")
    val wakeType: Long    = base
    val workerType: Long  = base + 1
    private val ev        = stdlib.malloc(sdl.eventSize.toUSize)
    private val out       = stdlib.malloc((3 * sizeOf[CUnsignedLongLong]).toUSize).asInstanceOf[Ptr[CUnsignedLongLong]]
    var wakeEvents: Long  = 0L

    /** Waits up to `timeoutMs` for one event, then empties SDL's queue. */
    def pump(timeoutMs: Int)(onWorker: (Int, Long, Long) => Unit): Unit =
      var t = timeoutMs
      while Sdl.SDL_WaitEventTimeout(ev, t) do
        t = 0
        val ty = TShim.sirin_decode_user(ev, out).toLong
        if ty == wakeType then wakeEvents += 1
        else if ty == workerType then onWorker(out(0).toInt, out(1).toLong, out(2).toLong)

    def close(): Unit =
      stdlib.free(ev)
      stdlib.free(out.asInstanceOf[Ptr[Byte]])
      Sdl.SDL_DestroyWindow(window)
      Sdl.SDL_Quit()

  // -------------------------------------------------------------------------
  // P0.6: workers allocate and push timestamped events; main sits in
  // @blocking SDL_WaitEventTimeout and measures push -> receipt latency.

  private def soak(secs: Int, workers: Int, pushUs: Long): Unit =
    val s = new Session
    require(TShim.sirin_watchdog_start(10000L.toULong) != 0)
    TShim.sirin_hb_activate(0.toUInt, 1)
    val stress   = new Stress(workers, firstSlot = 1, pushType = if pushUs > 0 then s.workerType else -1L, pushEveryNs = pushUs * 1000L, gcEvery = 0).start()
    val hist     = new Histogram
    var received = 0L
    val rss0     = TShim.sirin_resident_bytes().toLong
    val t0       = nowNs()
    val deadline = t0 + secs * 1000000000L
    var nextLog  = t0 + 60000000000L
    val onWorker = (_: Int, pushedAt: Long, _: Long) =>
      hist.record(nowNs() - pushedAt)
      received += 1
    while nowNs() < deadline do
      TShim.sirin_hb(0.toUInt)
      s.pump(100)(onWorker)
      if nowNs() >= nextLog then
        println(f"soak t=${(nowNs() - t0) / 1e9}%.0fs received=$received max=${hist.maxNs / 1e6}%.2fms gcs=${GcCounters.read().collections} rss=${TShim.sirin_resident_bytes().toLong / 1048576.0}%.1fMB gc_heap=${SnGc.scalanative_GC_get_used_heapsize().toLong / 1048576.0}%.1fMB")
        Console.flush()
        nextLog += 60000000000L
    val elapsed = (nowNs() - t0) / 1e9
    val rep     = stress.finish()
    // Collect events still queued after the workers stopped.
    val tail = nowNs() + 300000000L
    while nowNs() < tail do s.pump(20)(onWorker)
    TShim.sirin_hb_activate(0.toUInt, 0)
    TShim.sirin_watchdog_stop()
    val failed = TShim.sirin_push_failure_count().toLong
    s.close()
    result("mode=soak")
    result(f"soak.seconds=$elapsed%.1f")
    result(s"soak.workers=$workers soak.push_every_us=$pushUs")
    rep.lines("soak").foreach(result)
    result(s"soak.events_pushed=${rep.pushes} soak.push_failures=$failed soak.events_received=$received")
    result(f"soak.latency_p50_ms=${hist.percentileMs(0.5)}%.2f soak.latency_p99_ms=${hist.percentileMs(0.99)}%.2f soak.latency_p999_ms=${hist.percentileMs(0.999)}%.2f soak.latency_max_ms=${hist.maxNs / 1e6}%.3f")
    result(f"soak.rss_start_mb=${rss0 / 1048576.0}%.1f soak.rss_end_mb=${TShim.sirin_resident_bytes().toLong / 1048576.0}%.1f soak.gc_heap_mb=${SnGc.scalanative_GC_get_used_heapsize().toLong / 1048576.0}%.1f")
    check("soak.no_deadlock", true, "watchdog (10 s stall) never fired")
    check("soak.all_events_received", received == rep.pushes - failed, s"$received of ${rep.pushes - failed}")
    check("soak.latency_max_below_50ms", hist.maxNs < 50000000L, f"${hist.maxNs / 1e6}%.3f ms")
    check("soak.ticker_max_below_50ms", rep.maxOversleepNs < 50000000L, f"${rep.maxOversleepNs / 1e6}%.3f ms")

  // -------------------------------------------------------------------------
  // P0.6 re-entry: C calls a Scala CFuncPtr from inside an extern call while
  // other threads force collections. The callback keeps objects live only in
  // its own frame across allocation bursts and verifies their contents.

  object Reentry:
    val insideStates      = new Array[Long](3)
    var corrupt: Long     = 0L
    var calls: Long       = 0L

    def body(i: Int): Long =
      val st = TShim.sirin_thread_gc_state()
      insideStates(st + 1) += 1
      val live = Array.tabulate(32)(k => Array.fill(256)((i + k).toByte))
      var g    = 0L
      var j    = 0
      while j < 2000 do
        g += new Array[Byte](128 + (j & 63)).length
        j += 1
      var k = 0
      while k < 32 do
        val a = live(k)
        var b = 0
        while b < a.length do
          if a(b) != (i + k).toByte then corrupt += 1
          b += 1
        k += 1
      calls += 1
      g

  private val reentryCb: CFuncPtr1[CInt, CLongLong] =
    CFuncPtr1.fromScalaFunction[CInt, CLongLong]((i: CInt) => Reentry.body(i))

  private def reentry(kind: String, secs: Int, perCall: Int): Unit =
    require(kind == "blocking" || kind == "plain", s"unknown reentry kind $kind")
    require(TShim.sirin_watchdog_start(15000L.toULong) != 0)
    TShim.sirin_hb_activate(0.toUInt, 1)
    TShim.sirin_reenter_reset()
    val stress   = new Stress(3, firstSlot = 1, pushType = -1, pushEveryNs = 0, gcEvery = 50000).start()
    val deadline = nowNs() + secs * 1000000000L
    var outer    = 0L
    var sum      = 0L
    val gc0      = GcCounters.read()
    while nowNs() < deadline do
      TShim.sirin_hb(0.toUInt)
      sum += (if kind == "blocking" then TShim.sirin_reenter_blocking(reentryCb, perCall)
              else TShim.sirin_reenter_plain(reentryCb, perCall)).toLong
      outer += 1
    val gcDuring = GcCounters.read().collections - gc0.collections
    val rep      = stress.finish()
    TShim.sirin_hb_activate(0.toUInt, 0)
    TShim.sirin_watchdog_stop()
    val before = stackalloc[CUnsignedLongLong](3)
    TShim.sirin_reenter_states(before)
    def states(a: Int => Long) = s"unregistered=${a(0)} managed=${a(1)} unmanaged=${a(2)}"
    result(s"mode=reentry-$kind callbacks_per_call=$perCall")
    result(s"reentry.outer_calls=$outer reentry.callbacks=${Reentry.calls} reentry.gc_during=$gcDuring reentry.sum=$sum")
    result(s"reentry.state_in_c_before_callback: ${states(i => before(i).toLong)}")
    result(s"reentry.state_inside_scala_callback: ${states(i => Reentry.insideStates(i))}")
    result(s"reentry.corrupt_bytes=${Reentry.corrupt}")
    rep.lines("reentry").foreach(result)
    check("reentry.completed_without_crash_or_hang", Reentry.calls > 0)
    check("reentry.no_corruption", Reentry.corrupt == 0, s"${Reentry.corrupt} corrupt bytes")

  // -------------------------------------------------------------------------
  // P0.6 cost of strict (non-@blocking) extern semantics.

  private def cost(): Unit =
    val n = 10000000
    def time(f: () => Unit): Double =
      val t = nowNs()
      var i = 0
      while i < n do
        f()
        i += 1
      (nowNs() - t).toDouble / n
    time(() => TShim.sirin_noop_plain()) // warm-up
    val plainNs    = time(() => TShim.sirin_noop_plain())
    val blockingNs = time(() => TShim.sirin_noop_blocking())
    result("mode=cost")
    result(f"cost.noop_plain_ns=$plainNs%.2f cost.noop_blocking_ns=$blockingNs%.2f cost.blocking_overhead_ns=${blockingNs - plainNs}%.2f")

    // While this thread sleeps 200 ms inside an extern, three allocators run.
    def stall(label: String, sleep: () => Unit): Unit =
      val stress = new Stress(3, firstSlot = 1, pushType = -1, pushEveryNs = 0, gcEvery = 0).start()
      Thread.sleep(200)
      stress.ticker.reset()
      val it0 = stress.allocs.map(_.iterations).sum
      val t0  = nowNs()
      var k   = 0
      while k < 10 do
        sleep()
        k += 1
      val wall = (nowNs() - t0) / 1e9
      val it1  = stress.allocs.map(_.iterations).sum
      val rep  = stress.finish()
      result(f"cost.$label.alloc_iterations_per_s=${(it1 - it0) / wall}%.0f cost.$label.ticker_max_oversleep_ms=${rep.maxOversleepNs / 1e6}%.2f cost.$label.ticker_over_50ms=${rep.over50ms} cost.$label.gc_collections=${rep.collections}")
    stall("sleep200ms_blocking", () => TShim.sirin_sleep_blocking(200000.toUInt))
    stall("sleep200ms_plain", () => TShim.sirin_sleep_plain(200000.toUInt))

  // -------------------------------------------------------------------------
  // P0.7: foreign-thread entry.

  private def foreign(n: Long, cases: String): Unit =
    val s = new Session
    require(TShim.sirin_event_watch_install() != 0)
    require(TShim.sirin_watchdog_start(20000L.toULong) != 0)
    TShim.sirin_hb_activate(0.toUInt, 1)
    val stress  = new Stress(4, firstSlot = 1, pushType = -1, pushEveryNs = 0, gcEvery = 0).start()
    val guard0  = TShim.sirin_guard_checks().toLong
    val rss0    = TShim.sirin_resident_bytes().toLong
    val ident   = stackalloc[CInt](2)
    TShim.sirin_foreign_identity(ident)
    result(s"foreign.raw_pthread_identity: marked=${ident(0)} gc_state=${ident(1)}")
    check("foreign.raw_pthread_is_unregistered", ident(0) == 0 && ident(1) == -1)
    val selftest = TShim.sirin_guard_selftest_fork()
    check("foreign.guard_aborts_on_foreign_thread", selftest == 6, s"child status $selftest (6 = SIGABRT)")
    result(s"foreign.main_identity: marked=${TShim.sirin_thread_is_scala()} gc_state=${TShim.sirin_thread_gc_state()}")

    def run(name: Char, body: => Unit): Unit =
      if cases.contains(name) then
        stress.ticker.reset()
        val gc0 = GcCounters.read()
        body
        val gc1 = GcCounters.read()
        result(f"$name.env: ticker_max_oversleep_ms=${stress.ticker.maxOversleepNs / 1e6}%.2f gc_collections=${gc1.collections - gc0.collections} gc_mean_ms=${if gc1.collections == gc0.collections then 0.0 else (gc1.durationNs - gc0.durationNs) / 1e6 / (gc1.collections - gc0.collections)}%.2f rss_mb=${TShim.sirin_resident_bytes().toLong / 1048576.0}%.1f gc_heap_used_mb=${SnGc.scalanative_GC_get_used_heapsize().toLong / 1048576.0}%.1f")
    run('a', caseA(s, n))
    run('b', caseB(s, n))
    run('c', caseC(s, n))
    run('d', caseD(s, n))

    val rep = stress.finish()
    TShim.sirin_hb_activate(0.toUInt, 0)
    TShim.sirin_watchdog_stop()
    val watch = stackalloc[CUnsignedLongLong](3)
    TShim.sirin_event_watch_counts(watch)
    TShim.sirin_event_watch_remove()
    result(s"foreign.sdl_event_watch_calls: scala_threads=${watch(0)} foreign_threads=${watch(1)} other=${watch(2)}")
    result(s"foreign.sdl_push_failures=${TShim.sirin_push_failure_count()} foreign.wake_events_received=${s.wakeEvents}")
    rep.lines("foreign.gc_stress").foreach(result)
    result(f"foreign.rss_start_mb=${rss0 / 1048576.0}%.1f foreign.rss_end_mb=${TShim.sirin_resident_bytes().toLong / 1048576.0}%.1f")
    check("foreign.no_guarded_scala_entry_during_cases", TShim.sirin_guard_checks().toLong == guard0)
    check("foreign.no_live_mailboxes", TShim.sirin_mbox_live_count() == 0L, s"${TShim.sirin_mbox_live_count()} live")
    s.close()

  /** Starts foreign producers; the group copies the handle list. */
  private def startGroup(mb: Ptr[Byte], threads: Int, per: Long, base: Long, hs: Seq[Long], waitNs: Long): Ptr[Byte] =
    val p = stdlib.malloc((sizeOf[CUnsignedInt] * hs.length).toUSize).asInstanceOf[Ptr[CUnsignedInt]]
    hs.zipWithIndex.foreach((h, i) => p(i) = h.toUInt)
    val g = TShim.sirin_foreign_start(mb, threads.toUInt, per.toULong, base.toULong, p, hs.length.toUInt, waitNs, 1)
    stdlib.free(p.asInstanceOf[Ptr[Byte]])
    require(g != null, "sirin_foreign_start failed")
    g

  private def joinGroup(g: Ptr[Byte]): Array[Long] =
    val out = stackalloc[CUnsignedLongLong](Mbox.FgN)
    TShim.sirin_foreign_join(g, out)
    Array.tabulate(Mbox.FgN)(i => out(i).toLong)

  private def closeAndDestroy(c: Consumer): Long =
    TShim.sirin_mbox_close(c.mb)
    require(TShim.sirin_mbox_wait_idle(c.mb, 5000000000L) == 1, "mailbox did not go idle")
    val dropped = TShim.sirin_mbox_destroy(c.mb).toLong
    c.free()
    dropped

  private def fg(a: Array[Long]): String =
    s"ok=${a(Mbox.FgOk)} full=${a(Mbox.FgFull)} closed=${a(Mbox.FgClosed)} stale=${a(Mbox.FgStale)} attempts=${a(Mbox.FgAttempts)} foreign_confirmed=${a(Mbox.FgForeignConfirmed)} identity_violations=${a(Mbox.FgIdentityViolations)}"

  /** (a) Several foreign producers deliver concurrently during heavy GC. */
  private def caseA(s: Session, n: Long): Unit =
    val threads = 8
    val per     = n / threads
    val total   = per * threads
    val mb      = TShim.sirin_mbox_create_sdl(1024.toUInt, 4.toUInt, s.wakeType.toUInt)
    val c       = new Consumer(mb, contiguous = true)
    val h       = TShim.sirin_mbox_register(mb).toLong
    val gc0     = GcCounters.read().collections
    val t0      = nowNs()
    val g       = startGroup(mb, threads, per, 1000L, Seq(h), -1L)
    var wakes0  = s.wakeEvents
    while c.received < total || TShim.sirin_foreign_running(g).toInt > 0 do
      TShim.sirin_hb(0.toUInt)
      s.pump(100)((_, _, _) => ())
      c.drainAll()
    val wall = (nowNs() - t0) / 1e9
    val r    = joinGroup(g)
    c.drainAll()
    val st      = c.stats()
    val dropped = closeAndDestroy(c)
    wakes0 = s.wakeEvents - wakes0
    result(f"a.deliveries=$total a.seconds=$wall%.2f a.rate_per_s=${total / wall}%.0f a.gc_during=${GcCounters.read().collections - gc0}")
    result(s"a.producers: ${fg(r)}")
    result(s"a.mailbox: drained=${st(Mbox.StDrained)} wakes=${st(Mbox.StWakes)} wake_events_seen=$wakes0 high_water=${st(Mbox.StHighWater)} max_concurrent_producers=${st(Mbox.StInflightMax)}")
    check("a.all_delivered", r(Mbox.FgOk) == total && c.received == total && st(Mbox.StDrained) == total,
      s"producer ok=${r(Mbox.FgOk)} received=${c.received} of $total")
    check("a.fifo_per_producer_and_payload_intact", c.orderViolations == 0 && c.badPayload == 0,
      s"order=${c.orderViolations} payload=${c.badPayload}")
    check("a.producers_were_foreign", r(Mbox.FgForeignConfirmed) == threads && r(Mbox.FgIdentityViolations) == 0)
    check("a.no_residue", dropped == 0)

  /** (b) Deliveries racing shutdown: close while producers are active. */
  private def caseB(s: Session, n: Long): Unit =
    val rnd        = new scala.util.Random(7)
    var okTotal    = 0L
    var rounds     = 0
    var badRounds  = 0
    var closedSeen = 0L
    var attempts   = 0L
    var destroyedWithMessages = 0L
    var details    = List.empty[String]
    val rss0       = TShim.sirin_resident_bytes().toLong
    val live0      = TShim.sirin_mbox_live_count()
    while okTotal < n do
      val wait    = if rounds % 2 == 0 then -1L else 0L
      val mb      = TShim.sirin_mbox_create_sdl(256.toUInt, 4.toUInt, s.wakeType.toUInt)
      val c       = new Consumer(mb, contiguous = true)
      val h       = TShim.sirin_mbox_register(mb).toLong
      val g       = startGroup(mb, 4, 1L << 40, 100000L * (rounds + 1), Seq(h), wait)
      val runFor  = nowNs() + rnd.nextInt(20) * 1000000L
      while nowNs() < runFor do
        TShim.sirin_hb(0.toUInt)
        c.drainAll()
        s.pump(0)((_, _, _) => ())
      // Close while producers are active, without draining first.
      TShim.sirin_mbox_close(mb)
      val r = joinGroup(g)
      require(TShim.sirin_mbox_wait_idle(mb, 5000000000L) == 1)
      // Two rounds in three drain after close; the third destroys with messages queued.
      if rounds % 3 != 0 then c.drainAll()
      val st      = c.stats()
      val dropped = TShim.sirin_mbox_destroy(mb).toLong
      c.free()
      destroyedWithMessages += dropped
      val consistent = r(Mbox.FgOk) == st(Mbox.StOk) && st(Mbox.StOk) == c.received + dropped &&
        r(Mbox.FgEndedClosed) == 4 && r(Mbox.FgIdentityViolations) == 0 &&
        c.orderViolations == 0 && c.badPayload == 0 && dropped >= 0
      if !consistent then
        badRounds += 1
        if details.length < 3 then details ::= s"round $rounds: ${fg(r)} received=${c.received} dropped=$dropped"
      okTotal += r(Mbox.FgOk)
      closedSeen += r(Mbox.FgClosed)
      attempts += r(Mbox.FgAttempts)
      rounds += 1
    val rss1 = TShim.sirin_resident_bytes().toLong
    result(s"b.rounds=$rounds b.ok_deliveries=$okTotal b.attempts=$attempts b.closed_results=$closedSeen b.discarded_at_destroy=$destroyedWithMessages")
    result(f"b.rss_start_mb=${rss0 / 1048576.0}%.1f b.rss_end_mb=${rss1 / 1048576.0}%.1f")
    details.foreach(d => result(s"b.detail: $d"))
    check("b.every_round_clean", badRounds == 0, s"$badRounds bad rounds of $rounds")
    check("b.mailboxes_freed", TShim.sirin_mbox_live_count() == live0)

  /** (c) Unregistering consumers while deliveries to them are in flight. */
  private def caseC(s: Session, n: Long): Unit =
    val threads   = 4
    val per       = n / threads
    // A small ring keeps producers blocked mid-delivery, so rotations interleave with them.
    val mb        = TShim.sirin_mbox_create_sdl(64.toUInt, 4096.toUInt, s.wakeType.toUInt)
    val c         = new Consumer(mb, contiguous = false)
    val handles   = Array.fill(4)(TShim.sirin_mbox_register(mb).toLong)
    c.liveHandles = handles
    val g         = startGroup(mb, threads, per, 5000000L, handles.toSeq, -1L)
    var rotations = 0L
    var unregFail = 0L
    var reuseSame = 0L
    while TShim.sirin_foreign_running(g).toInt > 0 do
      TShim.sirin_hb(0.toUInt)
      s.pump(0)((_, _, _) => ())
      c.drainAll()
      // Rotate one consumer per pass while producers are mid-delivery to it.
      val k   = (rotations % 4).toInt
      val old = handles(k)
      if TShim.sirin_mbox_unregister(mb, old.toUInt) != 1 then unregFail += 1
      val nh = TShim.sirin_mbox_register(mb).toLong
      require(nh != 0L, "consumer slots exhausted")
      // Same slot must come back with a strictly newer generation.
      if nh == old || ((nh & 0xffffL) == (old & 0xffffL) && (nh >>> 16) <= (old >>> 16)) then reuseSame += 1
      TShim.sirin_foreign_set_handle(g, k.toUInt, nh.toUInt)
      handles(k) = nh
      rotations += 1
    val r = joinGroup(g)
    c.drainAll()
    val st      = c.stats()
    val dropped = closeAndDestroy(c)
    result(s"c.attempts=${r(Mbox.FgAttempts)} c.rotations=$rotations")
    result(s"c.producers: ${fg(r)}")
    result(s"c.mailbox: delivered_ok=${st(Mbox.StOk)} drained=${st(Mbox.StDrained)} dropped_stale_at_drain=${st(Mbox.StDroppedStale)} rejected_stale=${st(Mbox.StStale)}")
    check("c.attempts_accounted", r(Mbox.FgAttempts) == per * threads && r(Mbox.FgOk) + r(Mbox.FgStale) == per * threads)
    check("c.delivered_accounted", st(Mbox.StOk) == r(Mbox.FgOk) && st(Mbox.StOk) == c.received + st(Mbox.StDroppedStale) && dropped == 0)
    check("c.no_delivery_to_unregistered", c.deadHandleHits == 0, s"${c.deadHandleHits} hits")
    check("c.handles_never_reused", reuseSame == 0 && unregFail == 0, s"reuse=$reuseSame unregister_failures=$unregFail")
    check("c.order_and_payload", c.orderViolations == 0 && c.badPayload == 0)
    check("c.both_race_outcomes_exercised", r(Mbox.FgStale) > 0 && st(Mbox.StDroppedStale) > 0,
      s"rejected_stale=${r(Mbox.FgStale)} dropped_at_drain=${st(Mbox.StDroppedStale)}")
    check("c.producers_were_foreign", r(Mbox.FgForeignConfirmed) == threads && r(Mbox.FgIdentityViolations) == 0)

  /** (d) Deliveries from inside a native call stack that started in Scala. */
  private def caseD(s: Session, n: Long): Unit =
    val perCall = 1000L
    val calls   = n / (2 * perCall)
    val mb      = TShim.sirin_mbox_create_sdl(4096.toUInt, 4.toUInt, s.wakeType.toUInt)
    val c       = new Consumer(mb, contiguous = true)
    val h       = TShim.sirin_mbox_register(mb)
    val out     = stackalloc[CUnsignedLongLong](Mbox.FgN)
    val sum     = new Array[Long](Mbox.FgN)
    var failed  = 0L
    var call    = 0L
    while call < calls do
      TShim.sirin_hb(0.toUInt)
      if TShim.sirin_reentrant_delivery(mb, h, perCall.toULong, perCall.toULong, 9000000.toULong, (call * perCall).toULong, -1L, out) != 0
      then failed += 1
      var i = 0
      while i < Mbox.FgN do
        sum(i) += out(i).toLong
        i += 1
      s.pump(0)((_, _, _) => ())
      c.drainAll()
      call += 1
    val st      = c.stats()
    val dropped = closeAndDestroy(c)
    val total   = calls * 2 * perCall
    result(s"d.calls=$calls d.deliveries=$total d.sync_from_scala_thread=${calls * perCall} d.from_foreign_thread_inside_call=${calls * perCall}")
    result(s"d.producers: ${fg(sum)}")
    check("d.all_delivered", failed == 0 && sum(Mbox.FgOk) == total && c.received == total && st(Mbox.StDrained) == total && dropped == 0,
      s"ok=${sum(Mbox.FgOk)} received=${c.received} thread_failures=$failed")
    check("d.order_and_payload", c.orderViolations == 0 && c.badPayload == 0)
    check("d.inner_threads_were_foreign", sum(Mbox.FgForeignConfirmed) == calls && sum(Mbox.FgIdentityViolations) == 0)
