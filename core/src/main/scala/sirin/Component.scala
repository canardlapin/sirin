// Immutable component descriptions, interpreted by a host.
package sirin

trait Inputs[+P] extends Signal[P]

object Inputs:
  private[sirin] def from[A](signal: Signal[A]): Inputs[A] = new Signal.Input(signal, _ => signal)
  private[sirin] def from[A](delivered: Signal[A], speculative: Signal.Reader => Signal[A]): Inputs[A] =
    new Signal.Input(delivered, speculative)

opaque type GlobalKey = String
object GlobalKey:
  def apply(name: String): GlobalKey = name

/** A stable process-local definition token, separate from owner and key. */
trait Component[P]:
  def apply(props: P): View
  def apply(props: Signal[P]): View

object Component:
  private[sirin] final class Token[P](val equality: Equality[P])
  private[sirin] final class Template[P](val name: String, val token: Token[P],
      val dependencies: Option[AnyRef], val equality: Equality[P], val build: (Scope, Inputs[P]) => View) extends Component[P]:
    def apply(props: P): View = Placement(this, Signal.const(props))
    def apply(props: Signal[P]): View = Placement(this, props)
  private[sirin] final case class Placement[P](template: Template[P], props: Signal[P]) extends View

  def apply[P](name: String)(body: (Scope, Inputs[P]) => View)(using equality: Equality[P]): Component[P] =
    new Template(name, new Token(equality), None, equality, body)
  def withDeps[D <: AnyRef, P](name: String)(body: (D, Scope, Inputs[P]) => View)(using equality: Equality[P]): DependentComponent[D, P] =
    val token = new Token(equality)
    new DependentComponent[D, P]:
      def bind(dependencies: D): Component[P] =
        new Template(name, token, Some(dependencies), equality, (scope, inputs) => body(dependencies, scope, inputs))
  extension (component: Component[Unit])
    def view: View = component(())

/** Binding preserves the definition token. Different dependency reference
  * identity at the same placement remounts; changing data belongs in Inputs.
  */
trait DependentComponent[D <: AnyRef, P]:
  def bind(dependencies: D): Component[P]

/** A stable scope owns this panel; outlets only determine its placement.
  * At most one placement per key. Missing/disposed outlet hides the panel
  * without transferring ownership; owner disposal retires the panel.
  */
trait Reparentable:
  def at(outlet: Signal[NodeRef]): View

object DockOutlet:
  private[sirin] final case class Description(ref: NodeRef) extends View
  def apply(ref: NodeRef): View = Description(ref)

private[sirin] final case class RetainedPlacement(panel: Reparentable, outlet: Signal[NodeRef]) extends View
