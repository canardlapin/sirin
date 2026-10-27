package sirin.probe.threads

import scala.collection.mutable
import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import MsgLayout.Msg

/** Main-thread side of a mailbox: drains copies and verifies every message.
  *
  * @param contiguous each producer's sequence numbers must arrive exactly
  *   0, 1, 2, ...; otherwise they must only be strictly increasing (messages to
  *   unregistered consumers are dropped, leaving gaps).
  */
final class Consumer(val mb: Ptr[Byte], contiguous: Boolean):
  private val batch     = 256
  private val buf       = stdlib.malloc((sizeOf[Msg] * batch).toUSize).asInstanceOf[Ptr[Msg]]
  private val remaining = stdlib.malloc(sizeOf[CUnsignedLongLong].toUSize).asInstanceOf[Ptr[CUnsignedLongLong]]
  private val next      = mutable.HashMap.empty[Long, Long]

  var received: Long         = 0L
  var badPayload: Long       = 0L
  var orderViolations: Long  = 0L
  var deadHandleHits: Long   = 0L
  /** When set, every drained message must be addressed to one of these
    * currently registered handles (fixed size, so the check itself does not
    * grow a long-lived structure on the GC heap).
    */
  var liveHandles: Array[Long] = null

  def receivedFrom(producer: Long): Long = next.getOrElse(producer, 0L)

  /** Drains until the mailbox is empty; returns messages received. */
  def drainAll(): Long =
    var total = 0L
    var more  = true
    while more do
      val n = TShim.sirin_mbox_drain(mb, buf, batch.toCSize, remaining).toInt
      var i = 0
      while i < n do
        check(buf + i)
        i += 1
      total += n
      more = n == batch || (!remaining).toLong > 0
    received += total
    total

  private def check(m: Ptr[Msg]): Unit =
    val consumer = m._1.toLong
    val len      = m._2.toInt
    val producer = m._3.toLong
    val seq      = m._4.toLong
    val words    = m.at6.asInstanceOf[Ptr[Long]]
    if liveHandles != null && !liveHandles.contains(consumer) then deadHandleHits += 1
    if len != 24 || words(0) != producer || words(1) != seq ||
      words(2) != TShim.sirin_payload_checksum(producer.toULong, seq.toULong).toLong
    then badPayload += 1
    val expected = next.getOrElse(producer, 0L)
    if (contiguous && seq != expected) || (!contiguous && seq < expected) then orderViolations += 1
    next(producer) = seq + 1

  def stats(): Array[Long] =
    val out = stackalloc[CUnsignedLongLong](Mbox.StN)
    TShim.sirin_mbox_stats(mb, out)
    Array.tabulate(Mbox.StN)(i => out(i).toLong)

  def free(): Unit =
    stdlib.free(buf.asInstanceOf[Ptr[Byte]])
    stdlib.free(remaining.asInstanceOf[Ptr[Byte]])
