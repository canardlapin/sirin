package sirin.host

import sirin.*

class PopupReviewSuite extends munit.FunSuite:
  test("a nested popup bubbles shortcuts through the parent popup and base anchor ancestry") {
    val chord = Chord(Key.Character('r'), Mods.Primary)
    val baseAction = Activation(true, _ => ())
    val parentAction = Activation(true, _ => ())
    val baseButton = Node(NodeId(Vector("base-button")), Content.Empty)
    val base = Node(NodeId(Vector("base")), Content.Reference(1L, baseButton))
    val parentButton = Node(NodeId(Vector("parent-button")), Content.Empty)
    val parent = Node(NodeId(Vector("parent")), Content.Reference(2L, parentButton))
    val child = Node(NodeId(Vector("child")), Content.Empty)
    val root = Node(
      NodeId(Vector("window")),
      Content.Layered(
        base,
        Vector(
          PopupFrame(10L, 1L, Placement.Below, false) -> parent,
          PopupFrame(20L, 2L, Placement.Below, false) -> child
        )
      )
    )
    val baseOnly = CapturedTree(root, Map.empty, Vector(KeyBinding(base.id, chord, baseAction)))
    assertEquals(KeyboardRouting.local(baseOnly, Some(parentButton.id), chord), Some(baseAction))
    val withParent = baseOnly.copy(keys = baseOnly.keys :+ KeyBinding(parent.id, chord, parentAction))
    assertEquals(KeyboardRouting.local(withParent, Some(child.id), chord), Some(parentAction))
    assertEquals(KeyboardRouting.local(baseOnly, Some(child.id), chord), Some(baseAction))
  }
