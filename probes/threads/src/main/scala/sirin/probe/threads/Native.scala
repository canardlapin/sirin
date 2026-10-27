package sirin.probe.threads

import scala.scalanative.unsafe.*

/** Mirrors `sirin_msg` in sirin_mailbox.h; checked by `MailboxSuite`. */
object MsgLayout:
  type Payload = CArray[Byte, Nat.Digit2[Nat._6, Nat._4]]
  type Msg     = CStruct6[CUnsignedInt, CUnsignedInt, CUnsignedLongLong, CUnsignedLongLong, CUnsignedLongLong, Payload]
  final val PayloadCap = 64

/** Status codes and stats indices from sirin_mailbox.h and sirin_foreign.h. */
object Mbox:
  final val Ok       = 0
  final val Full     = 1
  final val Closed   = 2
  final val TooLarge = 3
  final val Stale    = 4

  // sirin_mbox_stats indices
  final val StOk = 0; final val StFull = 1; final val StClosed = 2; final val StTooLarge = 3
  final val StStale = 4; final val StDrained = 5; final val StDroppedStale = 6; final val StWakes = 7
  final val StCount = 8; final val StHighWater = 9; final val StInflightMax = 10
  final val StCapacity = 11; final val StIsClosed = 12; final val StN = 13

  // sirin_foreign_join indices
  final val FgOk = 0; final val FgFull = 1; final val FgClosed = 2; final val FgTooLarge = 3
  final val FgStale = 4; final val FgAttempts = 5; final val FgForeignConfirmed = 6
  final val FgIdentityViolations = 7; final val FgEndedClosed = 8; final val FgN = 9

import MsgLayout.Msg

@extern
object TShim:
  // Thread identity guard (sirin_guard.c)
  def sirin_thread_mark_scala(): Unit = extern
  def sirin_thread_unmark(): Unit     = extern
  def sirin_thread_is_scala(): CInt   = extern
  def sirin_thread_gc_state(): CInt   = extern
  def sirin_guard_checks(): CUnsignedLongLong = extern

  // Mailbox (sirin_mailbox.c). Deliver may wait on a condition variable.
  def sirin_mbox_create_counting(capacity: CUnsignedInt, maxConsumers: CUnsignedInt, counter: Ptr[CUnsignedLongLong]): Ptr[Byte] = extern
  def sirin_mbox_create_nowake(capacity: CUnsignedInt, maxConsumers: CUnsignedInt): Ptr[Byte] = extern
  def sirin_mbox_create_sdl(capacity: CUnsignedInt, maxConsumers: CUnsignedInt, eventType: CUnsignedInt): Ptr[Byte] = extern
  @blocking def sirin_mbox_deliver(mb: Ptr[Byte], consumer: CUnsignedInt, producer: CUnsignedLongLong, seq: CUnsignedLongLong, data: Ptr[Byte], len: CUnsignedInt, waitNs: CLongLong): CInt = extern
  def sirin_mbox_register(mb: Ptr[Byte]): CUnsignedInt = extern
  def sirin_mbox_unregister(mb: Ptr[Byte], handle: CUnsignedInt): CInt = extern
  def sirin_mbox_drain(mb: Ptr[Byte], out: Ptr[Msg], max: CSize, remaining: Ptr[CUnsignedLongLong]): CSize = extern
  def sirin_mbox_close(mb: Ptr[Byte]): Unit = extern
  @blocking def sirin_mbox_wait_idle(mb: Ptr[Byte], timeoutNs: CLongLong): CInt = extern
  def sirin_mbox_destroy(mb: Ptr[Byte]): CLongLong = extern
  def sirin_mbox_stats(mb: Ptr[Byte], out: Ptr[CUnsignedLongLong]): Unit = extern
  def sirin_mbox_live_count(): CLongLong = extern
  def sirin_msg_layout(out: Ptr[CSize]): CSize = extern
  def sirin_now_ns(): CUnsignedLongLong = extern

  // Foreign producers (sirin_foreign.c)
  def sirin_payload_checksum(producer: CUnsignedLongLong, seq: CUnsignedLongLong): CUnsignedLongLong = extern
  def sirin_foreign_start(mb: Ptr[Byte], nthreads: CUnsignedInt, nPerThread: CUnsignedLongLong, producerBase: CUnsignedLongLong, handles: Ptr[CUnsignedInt], nhandles: CUnsignedInt, waitNs: CLongLong, stopOnClosed: CInt): Ptr[Byte] = extern
  def sirin_foreign_set_handle(g: Ptr[Byte], i: CUnsignedInt, handle: CUnsignedInt): Unit = extern
  def sirin_foreign_running(g: Ptr[Byte]): CUnsignedInt = extern
  @blocking def sirin_foreign_join(g: Ptr[Byte], out: Ptr[CUnsignedLongLong]): Unit = extern
  @blocking def sirin_reentrant_delivery(mb: Ptr[Byte], handle: CUnsignedInt, nSync: CUnsignedLongLong, nForeign: CUnsignedLongLong, producerBase: CUnsignedLongLong, seqBase: CUnsignedLongLong, waitNs: CLongLong, out: Ptr[CUnsignedLongLong]): CInt = extern
  @blocking def sirin_foreign_identity(out: Ptr[CInt]): Unit = extern
  @blocking def sirin_guard_selftest_fork(): CInt = extern
  @blocking def sirin_guard_foreign_call_inprocess(): Unit = extern
  def sirin_invoke_guarded(cb: CFuncPtr1[CInt, CLongLong], arg: CInt): CLongLong = extern

  // SDL glue, watchdog, re-entry, cost (sirin_threads_shim.c)
  def sirin_threads_sdl_constants(out: Ptr[CUnsignedLongLong]): CSize = extern
  def sirin_push_user(eventType: CUnsignedInt, code: CInt, a: CUnsignedLongLong, b: CUnsignedLongLong): CInt = extern
  def sirin_push_failure_count(): CUnsignedLongLong = extern
  def sirin_decode_user(event: Ptr[Byte], out: Ptr[CUnsignedLongLong]): CUnsignedInt = extern
  def sirin_event_watch_install(): CInt = extern
  def sirin_event_watch_remove(): Unit = extern
  def sirin_event_watch_counts(out: Ptr[CUnsignedLongLong]): Unit = extern
  def sirin_hb(slot: CUnsignedInt): Unit = extern
  def sirin_hb_activate(slot: CUnsignedInt, active: CInt): Unit = extern
  def sirin_watchdog_start(stallMs: CUnsignedLongLong): CInt = extern
  @blocking def sirin_watchdog_stop(): Unit = extern
  @blocking def sirin_reenter_blocking(cb: CFuncPtr1[CInt, CLongLong], n: CInt): CLongLong = extern
  def sirin_reenter_plain(cb: CFuncPtr1[CInt, CLongLong], n: CInt): CLongLong = extern
  def sirin_reenter_states(out: Ptr[CUnsignedLongLong]): Unit = extern
  def sirin_reenter_reset(): Unit = extern
  def sirin_noop_plain(): Unit = extern
  @blocking def sirin_noop_blocking(): Unit = extern
  def sirin_sleep_plain(us: CUnsignedInt): Unit = extern
  @blocking def sirin_sleep_blocking(us: CUnsignedInt): Unit = extern
  def sirin_resident_bytes(): CUnsignedLongLong = extern

/** Scala Native GC counters (immix, shared/jmx.c). */
@extern
object SnGc:
  def scalanative_GC_stats_collection_total(): CSize          = extern
  def scalanative_GC_stats_collection_duration_total(): CSize = extern
  def scalanative_GC_get_used_heapsize(): CSize               = extern

@extern
object Sdl:
  def SDL_Init(flags: CUnsignedInt): CBool = extern
  def SDL_Quit(): Unit = extern
  def SDL_GetError(): CString = extern
  def SDL_CreateWindow(title: CString, w: CInt, h: CInt, flags: CUnsignedLongLong): Ptr[Byte] = extern
  def SDL_DestroyWindow(window: Ptr[Byte]): Unit = extern
  def SDL_RegisterEvents(n: CInt): CUnsignedInt = extern
  @blocking def SDL_WaitEventTimeout(event: Ptr[Byte], timeoutMs: CInt): CBool = extern

final case class SdlValues(eventUser: Long, eventLast: Long, eventSize: Long, windowHidden: Long, initVideo: Long, quit: Long)

object SdlValues:
  def load(): SdlValues =
    val out = stackalloc[CUnsignedLongLong](8)
    val n   = TShim.sirin_threads_sdl_constants(out).toInt
    require(n == 6, s"unexpected SDL constant count $n")
    def v(i: Int) = out(i).toLong
    SdlValues(v(0), v(1), v(2), v(3), v(4), v(5))
