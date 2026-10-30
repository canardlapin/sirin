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
    def path(node: Node, target: NodeId): Option[Vector[NodeId]] =
      if node.id == target then
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
            case Content.Scroll(_, child) =>
              Vector(child)
            case Content.Virtual(_, _, _, _, children) =>
              children
            case _ =>
              Vector.empty
        children
          .iterator
          .map(path(_, target))
          .collectFirst { case Some(found) =>
            node.id +: found
          }
    val ancestry = focused.flatMap(path(captured.tree, _)).getOrElse(Vector(captured.tree.id))
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
