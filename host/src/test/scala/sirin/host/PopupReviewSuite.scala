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
    val innerAction = Activation(false, _ => ())
    val inner = withParent.copy(keys = withParent.keys :+ KeyBinding(child.id, chord, innerAction))
    assertEquals(KeyboardRouting.local(inner, Some(child.id), chord), Some(innerAction))
  }

  private def button(id: Long, layer: Option[Long] = None): Target = Target(
    NodeId(Vector(id)), TargetKind.Button(id), Box(0, 0, 20, 20), Box(0, 0, 20, 20),
    true, Vector(id), Vector.empty, Vector.empty, layer = layer
  )

  test("an empty modal blocks keys and focus requests and cancels a Space press across opening and closing") {
    val interaction = new Interaction
    val target = button(1)
    interaction.install(InteractionSnapshot(1, Vector(target)))
    assert(interaction.focusRef(1))
    interaction.handle(HostInput.KeyDown(Key.Space, Mods.none))
    val modal = PopupLayer(10, Box(30, 30, 20, 20), true)
    interaction.install(InteractionSnapshot(2, Vector(target), Vector(modal)))
    assertEquals(interaction.state.focused, Some(target.id), "stored focus survives")
    assertEquals(interaction.inputFocus, None)
    assert(!interaction.focusRef(1))
    assertEquals(interaction.handle(HostInput.KeyDown(Key.Enter, Mods.none)).activate, None)
    interaction.handle(HostInput.KeyDown(Key.Tab, Mods.none))
    assertEquals(interaction.inputFocus, None)
    assertEquals(interaction.handle(HostInput.KeyUp(Key.Space, Mods.none)).activate, None)
    interaction.handle(HostInput.KeyDown(Key.Space, Mods.none))
    interaction.install(InteractionSnapshot(3, Vector(target)))
    assertEquals(interaction.handle(HostInput.KeyUp(Key.Space, Mods.none)).activate, None)
    assertEquals(interaction.handle(HostInput.KeyDown(Key.Enter, Mods.none)).activate, Some(1L))
  }

  test("Tab leaves a blocked lower popup for the active modal, with Shift-Tab contained") {
    val interaction = new Interaction
    val lower = button(1, Some(10))
    val upper = button(2, Some(20))
    val layers = Vector(
      PopupLayer(10, Box(0, 0, 20, 20), false),
      PopupLayer(20, Box(30, 0, 20, 20), true)
    )
    interaction.install(InteractionSnapshot(1, Vector(lower, upper), layers))
    interaction.state = interaction.state.copy(focused = Some(lower.id))
    assertEquals(interaction.handle(HostInput.KeyDown(Key.Enter, Mods.none)).activate, None)
    interaction.handle(HostInput.KeyDown(Key.Tab, Mods.none))
    assertEquals(interaction.state.focused, Some(upper.id))
    interaction.handle(HostInput.KeyDown(Key.Tab, Mods.Shift))
    assertEquals(interaction.state.focused, Some(upper.id))
    assertEquals(interaction.handle(HostInput.KeyDown(Key.Enter, Mods.none)).activate, Some(2L))
  }

  test("a non-modal popup leaves base input and programmatic focus available") {
    val interaction = new Interaction
    interaction.install(InteractionSnapshot(
      1, Vector(button(1)), Vector(PopupLayer(10, Box(30, 30, 20, 20), false))
    ))
    assert(interaction.focusRef(1))
    assertEquals(interaction.handle(HostInput.KeyDown(Key.Enter, Mods.none)).activate, Some(1L))
  }
