// Provisional A-0b skeleton: public shape only, not implemented.
package sirin

trait Inputs[+P] extends Signal[P]

opaque type GlobalKey = String
object GlobalKey:
  def apply(name: String): GlobalKey = name

/** A stable process-local definition token, separate from owner and key. */
trait Component[P]:
  def apply(props: P): View
  def apply(props: Signal[P]): View

object Component:
  def apply[P](name: String)(body: (Scope, Inputs[P]) => View): Component[P] = ???
  def withDeps[D <: AnyRef, P](name: String)(body: (D, Scope, Inputs[P]) => View): DependentComponent[D, P] = ???
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
  def apply(ref: NodeRef): View = ???
