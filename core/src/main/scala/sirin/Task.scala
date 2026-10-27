// Provisional A-0b skeleton: contracts and authoring shape, not an engine.
package sirin

import scala.util.NotGiven

trait Cancellation:
  def cancelled: Boolean

/** Worker code has no UI ownership. Runtime thread/scope checks remain
  * authoritative; this guard catches literals capturing a lexical UiTxn.
  */
opaque type Work[+O] = Cancellation => O
object Work:
  def apply[O](body: Cancellation ?=> O)(using NotGiven[UiTxn]): Work[O] = c => body(using c)

opaque type Capacity = Int
object Capacity:
  def apply(value: Int): Capacity =
    require(value > 0, "capacity must be finite and positive")
    value
  extension (c: Capacity) def value: Int = c

enum Concurrency:
  case Parallel, RefuseWhileRunning, Supersede
  /** Number waiting, in addition to the one executing. Full queues refuse. */
  case Serial(capacity: Capacity)

enum Acceptance:
  case LatestOnly, Progressive, EveryInvocation, SnapshotAssociated

final case class WorkPolicy(concurrency: Concurrency, acceptance: Acceptance)
object WorkPolicy:
  val preview: WorkPolicy = WorkPolicy(Concurrency.Supersede, Acceptance.LatestOnly)
  val download: WorkPolicy = WorkPolicy(Concurrency.Parallel, Acceptance.EveryInvocation)
  def save(capacity: Capacity): WorkPolicy = WorkPolicy(Concurrency.Serial(capacity), Acceptance.SnapshotAssociated)

/** The only acceptance choices meaningful for input-driven latest work. */
enum LatestPolicy:
  case LatestOnly, Progressive

enum Delivery:
  case CoalesceLatest, Ordered, CompleteStream

enum Outcome[+R]:
  case Succeeded(value: R)
  case Failed(error: Throwable)
  case Cancelled

final case class Completed[+I, +O](input: I, outcome: Outcome[O], current: Boolean)

enum TaskState[+I, +O]:
  case Idle
  case Pending(input: I, previous: Option[Completed[I, O]])
  case Done(result: Completed[I, O])

  def shown: Option[O] = this match
    case Pending(_, previous) => previous.flatMap(r => success(r.outcome))
    case Done(result)         => success(result.outcome)
    case Idle                 => None
  def pending: Boolean = this match
    case Pending(_, _) => true
    case _             => false
  def stale: Boolean = this match
    case Pending(_, previous) => previous.isDefined
    case Done(result)         => !result.current
    case Idle                 => false
  def error: Option[Throwable] = this match
    case Done(Completed(_, Outcome.Failed(e), _)) => Some(e)
    case _                                       => None
  private def success(outcome: Outcome[O]): Option[O] = outcome match
    case Outcome.Succeeded(value) => Some(value)
    case _                        => None

enum Offered:
  case Accepted, Full, Closed, Cancelled

/** A worker/foreign-producer endpoint. tryEmit never blocks. emit waits for
  * capacity cooperatively, returning Cancelled/Closed on cancellation/disposal.
  * Ordered/CompleteStream never silently drop accepted items while delivery is live. Latest may replace.
  * A foreign endpoint must be implemented by the C shim, never Scala callback.
  */
trait Progress[-P]:
  def tryEmit(value: P)(using NotGiven[UiTxn]): Offered
  def emit(value: P)(using Cancellation, NotGiven[UiTxn]): Offered

enum ChannelState:
  case Open
  case Closing(pending: Int)
  case Drained
  case Cancelled(abandoned: Int)

/** Scope-owned delivery. Graceful close rejects offers, then drains accepted
  * items while the owner lives. Cancellation/disposal explicitly abandons pending
  * items. Close/cancel are staged; scope disposal immediately forbids
  * publication and wakes blocked producers. UI offer reserves capacity in the
  * current transaction and releases that reservation if the attempt rolls back.
  */
trait ProgressChannel[P]:
  def producer: Progress[P]
  def offer(value: P)(using UiTxn): Offered
  def close()(using UiTxn): Unit
  def cancelDelivery()(using UiTxn): Unit
  def state: Signal[ChannelState]
