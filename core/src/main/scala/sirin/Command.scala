// Provisional A-0b skeleton: public shape only, not implemented.
package sirin

opaque type CommandId = String
object CommandId:
  def apply(name: String): CommandId = name
  extension (id: CommandId) def name: String = id

final case class Job[+S, +R](snapshot: S, work: Work[R])

opaque type InvocationId = Long
object InvocationId:
  def apply(value: Long): InvocationId = value

final case class CommandResult[+R](invocation: InvocationId, outcome: Outcome[R], current: Boolean)

/** Stable read-only summary supplied explicitly while defining a command. */
trait CommandStatus[+R]:
  def running: Signal[Int]
  def lastResult: Signal[Option[CommandResult[R]]]
  /** Shared by all dispatch bindings; next accepted dispatch clears refusal. */
  def lastRefusal: Signal[Option[String]]

/** Resources retire after terminal delivery regardless of handle retention.
  * Observation belongs to an explicit scope; terminal data may be retained.
  */
trait Invocation[+R]:
  def observe(scope: Scope): Signal[Option[CommandResult[R]]]
  def cancel()(using UiTxn): Unit

enum Dispatched[+R]:
  case Refused(reason: String)
  case Started(invocation: Invocation[R])

trait Command[-A, +R] extends CommandStatus[R]:
  def id: CommandId
  def label: String
  def policy: WorkPolicy
  def enabled: Signal[Boolean]
  def enabledFor(arg: Signal[A]): Signal[Boolean]
  /** Rechecks general and argument enablement; start uses an implicit attempt.
    * Work launches only after successful outer publication.
    */
  def dispatch(arg: A)(using UiTxn): Dispatched[R]

object Command:
  extension [A, R](command: Command[A, R])
    /** Includes argument-specific enablement and rechecks it at dispatch. */
    def withArg(arg: Signal[A]): Command[Unit, R] = ???
