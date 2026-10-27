// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

/** The live, read-only props of a mounted component (VISION §4). When the
  * parent supplies a new value under the same identity, this signal updates
  * and local state is kept.
  */
trait Inputs[+P] extends Signal[P]

/** A stable global key for explicit reparenting (VISION §5.2). */
opaque type GlobalKey = String

object GlobalKey:
  def apply(name: String): GlobalKey = name

/** A component definition. Its identity as a "component type" is this
  * value: define components once (a `val` in an object, or once per mount
  * inside a parent's body), never inside a function that re-runs.
  */
final class Component[P] private (val name: String, val body: (Scope, Inputs[P]) => View):
  /** Places this component with `props`. The body runs once per mount. */
  def apply(props: P): View = ???

  /** Places this component under a global key, so state survives a move to
    * a different owner.
    */
  def apply(props: P, key: GlobalKey): View = ???

object Component:
  def apply[P](name: String)(body: (Scope, Inputs[P]) => View): Component[P] =
    new Component(name, body)
