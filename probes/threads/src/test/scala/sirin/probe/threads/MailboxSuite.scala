package sirin.probe.threads

import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import MsgLayout.Msg

/** P0.7: deterministic mailbox semantics (bounded, FIFO, close, ownership,
  * stale handles, wake coalescing) and the thread-identity guard.
  */
class MailboxSuite extends munit.FunSuite:

  override def beforeEach(context: BeforeEach): Unit = TShim.sirin_thread_mark_scala()

  private final class Box(capacity: Int, consumers: Int = 4):
    val counter: Ptr[CUnsignedLongLong] =
      stdlib.calloc(1.toUSize, sizeOf[CUnsignedLongLong].toUSize).asInstanceOf[Ptr[CUnsignedLongLong]]
    val mb: Ptr[Byte] = TShim.sirin_mbox_create_counting(capacity.toUInt, consumers.toUInt, counter)
    assert(mb != null)
    val out: Ptr[Msg] = stdlib.malloc((sizeOf[Msg] * 1024).toUSize).asInstanceOf[Ptr[Msg]]
    val rem: Ptr[CUnsignedLongLong] = stdlib.malloc(sizeOf[CUnsignedLongLong].toUSize).asInstanceOf[Ptr[CUnsignedLongLong]]

    def register(): Long = TShim.sirin_mbox_register(mb).toLong

    def deliver(h: Long, producer: Long, seq: Long, bytes: Array[Byte], waitNs: Long = 0L): Int =
      val p = stdlib.malloc(math.max(bytes.length, 1).toUSize)
      var i = 0
      while i < bytes.length do
        p(i) = bytes(i)
        i += 1
      val r = TShim.sirin_mbox_deliver(mb, h.toUInt, producer.toULong, seq.toULong, p, bytes.length.toUInt, waitNs)
      stdlib.free(p)
      r

    def deliverPtr(h: Long, seq: Long, p: Ptr[Byte], len: Int): Int =
      TShim.sirin_mbox_deliver(mb, h.toUInt, 1.toULong, seq.toULong, p, len.toUInt, 0L)

    /** Drains up to max; returns (consumer, producer, seq, payload) tuples. */
    def drain(max: Int = 1024): List[(Long, Long, Long, List[Byte])] =
      val n = TShim.sirin_mbox_drain(mb, out, max.toCSize, rem).toInt
      (0 until n).toList.map { i =>
        val m = out + i
        val bytes   = m.at6.asInstanceOf[Ptr[Byte]]
        val payload = (0 until m._2.toInt).map(j => bytes(j)).toList
        (m._1.toLong, m._3.toLong, m._4.toLong, payload)
      }

    def remaining: Long = (!rem).toLong
    def wakes: Long     = (!counter).toLong

    def stats: Array[Long] =
      val o = stackalloc[CUnsignedLongLong](Mbox.StN)
      TShim.sirin_mbox_stats(mb, o)
      Array.tabulate(Mbox.StN)(i => o(i).toLong)

    def closeAndDestroy(): Long =
      TShim.sirin_mbox_close(mb)
      val d = TShim.sirin_mbox_destroy(mb).toLong
      stdlib.free(out.asInstanceOf[Ptr[Byte]])
      stdlib.free(rem.asInstanceOf[Ptr[Byte]])
      stdlib.free(counter.asInstanceOf[Ptr[Byte]])
      d

  test("sirin_msg layout matches C") {
    val o = stackalloc[CSize](8)
    val n = TShim.sirin_msg_layout(o).toInt
    val p = stackalloc[Msg]()
    def off(f: Ptr[?]) = f.toLong - p.toLong
    assertEquals(
      (0 until n).map(i => o(i).toLong).toList,
      List(sizeOf[Msg].toLong, off(p.at1), off(p.at2), off(p.at3), off(p.at4), off(p.at5), off(p.at6))
    )
  }

  test("bounded: capacity is enforced without waiting, and a timed wait expires") {
    val b = new Box(4)
    val h = b.register()
    assertEquals((0 until 4).map(i => b.deliver(h, 1, i, Array[Byte](1))).toList, List.fill(4)(Mbox.Ok))
    assertEquals(b.deliver(h, 1, 4, Array[Byte](1)), Mbox.Full)
    val t0 = nowNs()
    assertEquals(b.deliver(h, 1, 4, Array[Byte](1), waitNs = 20000000L), Mbox.Full)
    assert(nowNs() - t0 >= 15000000L, "timed wait returned too early")
    assertEquals(b.stats(Mbox.StFull), 2L)
    assertEquals(b.stats(Mbox.StHighWater), 4L)
    assertEquals(b.drain(1).length, 1)
    assertEquals(b.deliver(h, 1, 4, Array[Byte](1)), Mbox.Ok)
    assertEquals(b.closeAndDestroy(), 4L)
  }

  test("FIFO: messages drain in delivery order, across a drain limit") {
    val b = new Box(128)
    val h = b.register()
    (0 until 100).foreach(i => assertEquals(b.deliver(h, 7, i, Array(i.toByte, (i * 3).toByte)), Mbox.Ok))
    val first = b.drain(60)
    assertEquals(b.remaining, 40L)
    val all = first ++ b.drain()
    assertEquals(b.remaining, 0L)
    assertEquals(all.map(_._3), (0L until 100L).toList)
    assertEquals(all.map(_._4), (0 until 100).map(i => List(i.toByte, (i * 3).toByte)).toList)
    assert(all.forall(m => m._1 == h && m._2 == 7L))
    assertEquals(b.closeAndDestroy(), 0L)
  }

  test("ownership: the payload is copied at delivery; oversize is rejected; empty is allowed") {
    val b   = new Box(8)
    val h   = b.register()
    val src = stdlib.malloc((MsgLayout.PayloadCap + 1).toUSize)
    (0 until MsgLayout.PayloadCap).foreach(i => src(i) = i.toByte)
    assertEquals(b.deliverPtr(h, 0, src, MsgLayout.PayloadCap), Mbox.Ok)
    (0 until MsgLayout.PayloadCap).foreach(i => src(i) = 0xee.toByte) // producer reuses its buffer
    stdlib.free(src)                                               // ... or frees it
    assertEquals(b.deliver(h, 1, 1, new Array[Byte](MsgLayout.PayloadCap + 1)), Mbox.TooLarge)
    assertEquals(b.deliver(h, 1, 2, Array.emptyByteArray), Mbox.Ok)
    val got = b.drain()
    assertEquals(got.length, 2)
    assertEquals(got.head._4, (0 until MsgLayout.PayloadCap).map(_.toByte).toList)
    assertEquals(got(1)._4, Nil)
    assertEquals(b.stats(Mbox.StTooLarge), 1L)
    assertEquals(b.closeAndDestroy(), 0L)
  }

  test("close: later deliveries return CLOSED; queued messages stay drainable; destroy needs close") {
    val b = new Box(8)
    val h = b.register()
    assertEquals(b.deliver(h, 1, 0, Array[Byte](1)), Mbox.Ok)
    assertEquals(b.deliver(h, 1, 1, Array[Byte](2)), Mbox.Ok)
    assertEquals(TShim.sirin_mbox_destroy(b.mb).toLong, -1L, "destroy of an open mailbox must refuse")
    TShim.sirin_mbox_close(b.mb)
    assertEquals(b.deliver(h, 1, 2, Array[Byte](3)), Mbox.Closed)
    assertEquals(b.deliver(h, 1, 2, Array[Byte](3), waitNs = -1L), Mbox.Closed)
    assertEquals(TShim.sirin_mbox_register(b.mb).toLong, 0L, "no registration after close")
    assertEquals(b.drain(1).map(_._3), List(0L))
    assertEquals(b.closeAndDestroy(), 1L, "destroy reports the undrained message it discards")
  }

  test("close releases a foreign producer blocked on a full mailbox with CLOSED") {
    val b  = new Box(1)
    val h  = b.register()
    assertEquals(b.deliver(h, 1, 0, Array[Byte](1)), Mbox.Ok)
    val hs = stackalloc[CUnsignedInt](1)
    hs(0) = h.toUInt
    val g  = TShim.sirin_foreign_start(b.mb, 1.toUInt, 1.toULong, 50.toULong, hs, 1.toUInt, -1L, 1)
    // Whether the producer is already waiting or not yet started, its only
    // possible outcome is CLOSED: the mailbox stays full until close.
    Thread.sleep(50)
    TShim.sirin_mbox_close(b.mb)
    val out = stackalloc[CUnsignedLongLong](Mbox.FgN)
    TShim.sirin_foreign_join(g, out)
    assertEquals(out(Mbox.FgClosed).toLong, 1L)
    assertEquals(out(Mbox.FgEndedClosed).toLong, 1L)
    assertEquals(out(Mbox.FgOk).toLong, 0L)
    assertEquals(out(Mbox.FgForeignConfirmed).toLong, 1L)
    assertEquals(TShim.sirin_mbox_wait_idle(b.mb, 1000000000L), 1)
    assertEquals(b.closeAndDestroy(), 1L)
  }

  test("stale handles: rejected at delivery, dropped at drain, never reissued") {
    val b  = new Box(8)
    val h1 = b.register()
    val h2 = b.register()
    assertNotEquals(h1, 0L)
    assertEquals(b.deliver(h1, 1, 0, Array[Byte](1)), Mbox.Ok) // in flight when h1 goes away
    assertEquals(b.deliver(h2, 1, 1, Array[Byte](2)), Mbox.Ok)
    assertEquals(TShim.sirin_mbox_unregister(b.mb, h1.toUInt), 1)
    assertEquals(TShim.sirin_mbox_unregister(b.mb, h1.toUInt), 0, "double unregister")
    assertEquals(b.deliver(h1, 1, 2, Array[Byte](3)), Mbox.Stale)
    assertEquals(b.deliver(0L, 1, 2, Array[Byte](3)), Mbox.Stale)
    val h3 = b.register() // reuses h1's slot with a new generation
    assertEquals(h3 & 0xffffL, h1 & 0xffffL)
    assertNotEquals(h3, h1)
    assertEquals(b.deliver(h1, 1, 2, Array[Byte](3)), Mbox.Stale)
    assertEquals(b.drain().map(m => (m._1, m._3)), List((h2, 1L)))
    assertEquals(b.stats(Mbox.StDroppedStale), 1L)
    assertEquals(b.stats(Mbox.StStale), 3L)
    assertEquals(b.closeAndDestroy(), 0L)
  }

  test("generations never wrap: an exhausted slot is retired, not reissued") {
    val b     = new Box(4, consumers = 1)
    var last  = 0L
    var count = 0
    var h     = b.register()
    while h != 0L do
      assert((h >>> 16) > (last >>> 16), s"generation did not increase: $h after $last")
      last = h
      count += 1
      assertEquals(TShim.sirin_mbox_unregister(b.mb, h.toUInt), 1)
      h = b.register()
    assertEquals(count, 0xffff)
    assertEquals(b.deliver(last, 1, 0, Array[Byte](1)), Mbox.Stale)
    assertEquals(b.closeAndDestroy(), 0L)
  }

  test("wake coalescing: one wake per empty-to-pending transition") {
    val b = new Box(16)
    val h = b.register()
    (0 until 3).foreach(i => b.deliver(h, 1, i, Array[Byte](1)))
    assertEquals(b.wakes, 1L)
    assertEquals(b.drain(2).length, 2)
    b.deliver(h, 1, 3, Array[Byte](1))
    assertEquals(b.wakes, 1L, "queue never emptied, so no new wake")
    assertEquals(b.drain().length, 2)
    b.deliver(h, 1, 4, Array[Byte](1))
    assertEquals(b.wakes, 2L)
    assertEquals(b.stats(Mbox.StWakes), 2L)
    assertEquals(b.closeAndDestroy(), 1L)
  }

  test("identity: a raw pthread is unmarked and unknown to the GC; this thread is both") {
    val o = stackalloc[CInt](2)
    TShim.sirin_foreign_identity(o)
    assertEquals((o(0), o(1)), (0, -1))
    assertEquals(TShim.sirin_thread_is_scala(), 1)
    assertEquals(TShim.sirin_thread_gc_state(), 0, "Managed while running Scala code")
  }

  test("guard: a foreign thread entering a guarded Scala path aborts (forked child)") {
    assertEquals(TShim.sirin_guard_selftest_fork(), 6, "expected SIGABRT (6)")
  }

  test("guard: a marked Scala thread passes and the callback runs") {
    val before = TShim.sirin_guard_checks().toLong
    val cb     = CFuncPtr1.fromScalaFunction[CInt, CLongLong]((i: CInt) => i.toLong * 2)
    assertEquals(TShim.sirin_invoke_guarded(cb, 21).toLong, 42L)
    assertEquals(TShim.sirin_guard_checks().toLong, before + 1)
  }

  test("payload checksum is deterministic and producer/seq sensitive") {
    val a = TShim.sirin_payload_checksum(1.toULong, 2.toULong)
    assertEquals(a, TShim.sirin_payload_checksum(1.toULong, 2.toULong))
    assertNotEquals(a, TShim.sirin_payload_checksum(2.toULong, 1.toULong))
  }
