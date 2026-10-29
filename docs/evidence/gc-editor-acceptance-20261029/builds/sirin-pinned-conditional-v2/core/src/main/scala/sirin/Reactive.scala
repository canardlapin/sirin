// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

import scala.util.NotGiven

/** A lawful equality used to stop propagation (VISION §5.1). The default
  * rejects arrays, whose `==` is reference identity.
  */
trait Equality[A]:
  def same(x: A, y: A): Boolean

object Equality:
  given universal[A](using NotGiven[A <:< Array[?]]): Equality[A] = (x, y) => x == y
  def by[A, K](key: A => K): Equality[A]                          = (x, y) => key(x) == key(y)

/** A monotonically increasing value revision. */
opaque type Revision = Long

object Revision:
  val initial: Revision        = 0L
  def apply(n: Long): Revision = n
  given Ordering[Revision]     = Ordering.Long
  extension (r: Revision)
    def value: Long                   = r
    def next: Revision                = r + 1
    def isAfter(o: Revision): Boolean = r > o
    def max(o: Revision): Revision    = math.max(r, o)

final case class Versioned[+A](value: A, revision: Revision)

/** The value of a derivation, which may have failed (VISION §5.1: failure is data). */
enum Value[+A]:
  case Ok(value: A)
  case Failed(error: Throwable)

/** The write and read context of one transaction (VISION §5.1). Reads inside
  * a transaction see staged writes, including speculative derived reads.
  */
trait UiTxn:
  /** Rejects the whole transaction: every staged write is discarded. */
  def require(condition: Boolean, reason: => String): Unit

  /** `require(false, reason)` for expression positions. */
  def reject(reason: String): Nothing

object UiTxn:
  def require(condition: Boolean, reason: => String)(using txn: UiTxn): Unit =
    txn.require(condition, reason)
  def reject(reason: String)(using txn: UiTxn): Nothing = txn.reject(reason)

/** A handler body: runs inside a transaction. */
type Action = UiTxn ?=> Unit

/** A current value that changes. Dependencies are explicit through
  * composition; reads never register dependencies.
  */
trait Signal[+A]:
  /** Reads the staged value of this transaction. Reading a `Failed`
    * derivation throws, which poisons the transaction; use `attempt`.
    */
  def get(using UiTxn): A
  def map[B](f: A => B)(using Equality[B]): Signal[B]
  def zip[B](that: Signal[B]): Signal[(A, B)]

  /** A topology edit: the inner signal is attached between passes. */
  def flatMap[B](f: A => Signal[B]): Signal[B]
  def attempt: Signal[Value[A]]

object Signal:
  def const[A](value: A): Signal[A] = ???
  def always: Signal[Boolean]       = const(true)

  extension [A, B](pair: (Signal[A], Signal[B]))
    def mapN[C](f: (A, B) => C)(using Equality[C]): Signal[C] =
      pair._1.zip(pair._2).map(f.tupled)

  extension [A, B, C](triple: (Signal[A], Signal[B], Signal[C]))
    def mapN[D](f: (A, B, C) => D)(using Equality[D]): Signal[D] =
      triple._1.zip(triple._2).zip(triple._3).map { case ((a, b), c) => f(a, b, c) }

  extension (s: Signal[Boolean])
    def unary_! : Signal[Boolean]               = s.map(b => !b)
    def &&(t: Signal[Boolean]): Signal[Boolean] = s.zip(t).map(_ && _)

/** A signal that can be written inside a `UiTxn`. */
trait Cell[A] extends Signal[A]:
  def set(value: A)(using UiTxn): Unit
  def update(f: A => A)(using UiTxn): Unit

  /** The value together with the revision of the write that produced it. */
  def versioned: Signal[Versioned[A]]

/** An occurrence, not a persistent value. */
trait Event[+A]:
  def map[B](f: A => B): Event[B]
  def filter(p: A => Boolean): Event[A]

trait EventSource[A] extends Event[A]:
  def emit(value: A)(using UiTxn): Unit
