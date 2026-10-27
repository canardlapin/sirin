// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

opaque type CommandId = String

object CommandId:
  def apply(name: String): CommandId = name
  extension (id: CommandId) def name: String = id

/** The snapshot an asynchronous command captured, and the work to run on it. */
final case class Job[+S, +R](snapshot: S, work: Work[R])

trait Invocation[+R]:
  def outcome: Signal[Option[Outcome[R]]]

enum Dispatched[+R]:
  case Refused(reason: String)
  case Started(invocation: Invocation[R])

/** A typed operation with enablement, a completion policy, and an
  * asynchronous result (VISION §4, §5.4). All bindings (button, menu item,
  * shortcut, script) go through `dispatch`, the single dispatch boundary,
  * which re-checks enablement.
  */
trait Command[-A, +R]:
  def id: CommandId
  def label: String
  def freshness: Freshness
  def enabled: Signal[Boolean]
  def running: Signal[Boolean]
  def dispatch(arg: A)(using UiTxn): Dispatched[R]

object Command:
  extension [A, R](command: Command[A, R])
    /** Fixes the argument from a signal, for argument-less bindings. */
    def withArg(arg: Signal[A]): Command[Unit, R] = ???
