// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

enum Role:
  case Button, Checkbox, TextInput, Label, Status, Alert, Form
  case List, ListItem, Group, Dialog, Menu, MenuItem, Toolbar
  case Canvas, GraphicNode

enum SemanticAction:
  case Activate, Focus, Dismiss, Increment, Decrement
  case Custom(name: String)

enum Live:
  case Off, Polite, Assertive

/** Accessibility properties (VISION §8): role, name, value, state, and
  * relationships. Actions come from the control or element that owns the node.
  */
final case class Semantics(
    role: Role,
    name: Option[String] = None,
    description: Option[String] = None,
    value: Option[String] = None,
    selected: Option[Boolean] = None,
    invalid: Boolean = false,
    busy: Boolean = false,
    live: Live = Live.Off,
    labelledBy: Option[NodeRef] = None,
    describedBy: Option[NodeRef] = None,
    positionInSet: Option[(Int, Int)] = None
)

/** An identifier for a part inside a custom element (hit target or semantic node). */
opaque type PartId = Long

object PartId:
  def apply(n: Long): PartId = n
  extension (p: PartId) def value: Long = p

/** A semantic child that a custom element exposes. */
final case class SemanticNode(
    part: PartId,
    semantics: Semantics,
    bounds: Rect,
    actions: Set[SemanticAction]
)
