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
  def apply(n: Long): Revision =
    require(n >= 0, "negative revision")
    n
  given Ordering[Revision]     = Ordering.Long
  extension (r: Revision)
    def value: Long                   = r
    def next: Revision                = Math.addExact(r, 1L)
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
  /** Refuses the innermost attempt, or the whole transaction if none exists. */
  def require(condition: Boolean, reason: => String): Unit

  /** Rolls back all staging on refusal. Defects crossing this boundary poison
    * the enclosing transaction even if subsequently caught by its caller.
    */
  def attempt[A](body: UiTxn ?=> A): Attempt[A]
  def refuse(reason: String): Nothing
  def reject(reason: String): Nothing = refuse(reason)

enum Attempt[+A]:
  case Accepted(value: A)
  case Refused(reason: String)

/** Read-only phase capability. Values arrive through declared phase inputs;
  * this capability deliberately has no arbitrary Signal.get overload.
  */
trait Committed

object UiTxn:
  def require(condition: Boolean, reason: => String)(using txn: UiTxn): Unit =
    txn.require(condition, reason)
  def reject(reason: String)(using txn: UiTxn): Nothing = txn.refuse(reason)
  def refuse(reason: String)(using txn: UiTxn): Nothing = txn.refuse(reason)
  def attempt[A](body: UiTxn ?=> A)(using txn: UiTxn): Attempt[A] = txn.attempt(body)

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

  extension [A, B, C, D](quad: (Signal[A], Signal[B], Signal[C], Signal[D]))
    def mapN[E](f: (A, B, C, D) => E)(using Equality[E]): Signal[E] =
      quad._1.zip(quad._2).zip(quad._3.zip(quad._4)).map { case ((a, b), (c, d)) => f(a, b, c, d) }

  extension (s: Signal[Boolean])
    def unary_! : Signal[Boolean]               = s.map(b => !b)
    def &&(t: Signal[Boolean]): Signal[Boolean] = s.zip(t).map(_ && _)

/** A signal that can be written inside a `UiTxn`. */
trait Cell[A] extends Signal[A]:
  def set(value: A)(using UiTxn): Unit
  def update(f: A => A)(using UiTxn): Unit

  /** Staged reads use a candidate revision: intermediate values may share it.
    * Use snapshot, not staged versioned.get, for durable revision association.
    */
  def versioned: Signal[Versioned[A]]

  /** Captures committed value/revision. Refuses if this cell has unequal
    * staged writes, so an intermediate value cannot label a later commit.
    */
  def snapshot(using UiTxn): Versioned[A]

/** An occurrence, not a persistent value. */
trait Event[+A]:
  def map[B](f: A => B): Event[B]
  def filter(p: A => Boolean): Event[A]

trait EventSource[A] extends Event[A]:
  def emit(value: A)(using UiTxn): Unit
