// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

import scala.util.NotGiven

/** Cooperative cancellation for worker code. Cancellation only saves work. */
trait Cancellation:
  def cancelled: Boolean

/** Work that runs on a worker thread. It receives no `UiTxn` and must not
  * touch mounted state (VISION §5.4). Enforced at runtime; the `NotGiven`
  * guard additionally rejects a `Work { ... }` literal written where a
  * transaction is in lexical scope, since its body could capture it.
  */
opaque type Work[+O] = Cancellation => O

object Work:
  def apply[O](body: Cancellation ?=> O)(using NotGiven[UiTxn]): Work[O] = c => body(using c)

/** Freshness policies (VISION §5.4). */
enum Freshness:
  case LatestOnly, Progressive, EveryInvocation, Serial, SnapshotAssociated

/** Delivery policies for streamed messages (VISION §5.4). */
enum Delivery:
  case CoalesceLatest, Ordered, CompleteStream

enum Outcome[+R]:
  case Succeeded(value: R)
  case Failed(error: Throwable)
  case Cancelled

/** An accepted completion. `current` is false only under `Progressive`,
  * when the input moved on after the request was issued.
  */
final case class Completed[+I, +O](input: I, outcome: Outcome[O], current: Boolean)

/** The UI-side state of an input-driven task. */
enum TaskState[+I, +O]:
  case Idle
  case Pending(input: I, previous: Option[Completed[I, O]])
  case Done(result: Completed[I, O])

/** Launches independent or queued invocations (`EveryInvocation`, `Serial`). */
trait TaskLauncher[-I]:
  def launch(input: I)(using UiTxn): Unit
  def inFlight: Signal[Int]
