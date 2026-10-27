// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

import scala.concurrent.duration.FiniteDuration

/** A reference to a mounted node, used for focus, popup anchoring, and
  * accessibility relationships. Owned by the scope that created it; it
  * attaches to at most one node at a time through `View.ref`.
  */
trait NodeRef:
  /** Requests focus. Applied after the transaction publishes. */
  def focus()(using UiTxn): Unit
  def hasFocus: Signal[Boolean]
  def containsFocus: Signal[Boolean]
  def part(id: PartId): SemanticTarget
  def focusedPart: Signal[Option[PartId]]
  def focusPart(id: PartId)(using UiTxn): Unit
  def focusRing: Signal[Boolean]

/** Owns subscriptions, tasks, commands, animations, and resource leases.
  * Everything created through a scope is released when it is disposed;
  * disposal is immediate on the UI side (VISION §5.2).
  */
trait Scope:
  def cell[A](initial: A)(using Equality[A]): Cell[A]
  def keyed[K, A](initial: Seq[A])(key: A => K): KeyedCell[K, A]
  def events[A]: EventSource[A]
  def ref(): NodeRef

  /** Runs once after initial settle, then once per unequal committed change. */
  def effect[A](signal: Signal[A])(run: A => Action): Unit
  def on[A](event: Event[A])(run: A => Action): Unit

  /** A deadline-driven signal: follows `signal` after `quiet` without change. */
  def debounce[A](signal: Signal[A], quiet: FiniteDuration): Signal[A]

  /** Superseding input work; obsolete publication is checked independently of cancellation. */
  def latest[I, O](input: Signal[I], policy: LatestPolicy = LatestPolicy.LatestOnly)(
      work: I => Work[O]
  ): Signal[TaskState[I, O]]

  def progress[P](delivery: Delivery, capacity: Capacity)(receive: P => Action): ProgressChannel[P]

  /** A synchronous command. */
  def action[A](id: String, label: String, enabled: Signal[Boolean] = Signal.always,
      enabledFor: Signal[A => Boolean] = Signal.const((_: A) => true))(
      run: A => Action
  ): Command[A, Unit]

  /** An asynchronous command. `start` runs in the dispatching transaction and
    * captures the snapshot `S`; `complete` reconciles on the UI thread.
    */
  def command[A, S, R](
      id: String,
      label: String,
      policy: WorkPolicy,
      enabled: CommandStatus[R] => Signal[Boolean] = (_: CommandStatus[R]) => Signal.always,
      enabledFor: Signal[A => Boolean] = Signal.const((_: A) => true)
  )(start: A => UiTxn ?=> Job[S, R])(complete: (S, CommandResult[R]) => Action): Command[A, R]

  /** A presentation value that retargets from its current value and
    * velocity when `target` changes (VISION §8.2).
    */
  def animate(target: Signal[Double], transition: Transition = Transition.standard): Presentation[Double]

  /** Retains panel ownership here while moving placement to a named outlet. */
  def reparent(key: GlobalKey, content: View): Reparentable

  /** A nested scope, disposed with this one or earlier by `dispose`. */
  def child(name: String): Scope
  def dispose()(using UiTxn): Unit

  /** Registers release of an external (non-UI) resource. */
  def onDispose(release: () => Unit): Unit
