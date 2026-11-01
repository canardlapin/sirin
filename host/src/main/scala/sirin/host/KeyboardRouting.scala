package sirin.host

import sirin.*

final case class KeyBinding(anchor: NodeId, chord: Chord, activation: Activation)

object KeyboardRouting:
  def canonical(chord: Chord): Chord =
    chord.key match
      case Key.Character(c) =>
        chord.copy(key = Key.Character(c.toLower))
      case _ =>
        chord

  /** Matching disabled bindings consume the key and dispatch the shared refusal. */
  def local(captured: CapturedTree, focused: Option[NodeId], chord: Chord): Option[Activation] =
    def path(node: Node, target: NodeId): Option[Vector[NodeId]] = route(node, _.id == target)
    def route(node: Node, found: Node => Boolean): Option[Vector[NodeId]] =
      if found(node) then
        Some(Vector(node.id))
      else
        val children =
          node.content match
            case Content.Reference(_, child) =>
              Vector(child)
            case Content.Styled(_, child) =>
              Vector(child)
            case Content.CustomLayout(_, children) =>
              children.map(_._2)
            case Content.Stack(_, _, _, children) =>
              children
            case _ =>
              Vector.empty
        children
          .iterator
          .map(route(_, found))
          .collectFirst { case Some(steps) =>
            node.id +: steps
          }
    // A popup belongs to its originator: focus inside it bubbles through its content, then its anchor's ancestry.
    def within(id: NodeId): Option[Vector[NodeId]] =
      captured.tree.content match
        case Content.Layered(base, popups) =>
          // Anchors can only be presented in the base or an earlier layer. Decreasing the prefix also bounds
          // malformed cyclic anchor references without dropping shortcuts inside the focused popup itself.
          def lineage(found: Node => Boolean, before: Int): Option[Vector[NodeId]] =
            route(base, found).orElse(
              popups.iterator.take(before).zipWithIndex.map { case ((frame, content), index) =>
                route(content, found).map { inside =>
                  val anchor = lineage(
                    node => node.content match
                      case Content.Reference(ref, _) => ref == frame.anchor
                      case _ => false,
                    index
                  )
                  anchor.getOrElse(Vector.empty) ++ inside
                }
              }.collectFirst { case Some(steps) => steps }
            )
          lineage(_.id == id, popups.size)
        case _ =>
          path(captured.tree, id)
    val ancestry = focused.flatMap(within).getOrElse(Vector(captured.tree.id))
    captured
      .keys
      .zipWithIndex
      .filter((binding, _) => canonical(binding.chord) == canonical(chord) && ancestry.contains(binding.anchor))
      .sortBy((binding, index) => (-ancestry.lastIndexOf(binding.anchor), index))
      .headOption
      .map(_._1.activation)

  def command(
      captured: CapturedTree,
      focused: Option[NodeId],
      chord: Chord,
      window: CapturedMenus,
      application: CapturedMenus
  ): Option[Activation] = local(captured, focused, chord)
    .orElse(window.shortcut(chord))
    .orElse(application.shortcut(chord))
