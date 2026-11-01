package sirin.host

import sirin.*

class AppearancePayloadSuite extends munit.FunSuite:
  test("a palette change retires the older frame generation without changing the captured tree") {
    val tree = Node(NodeId(Vector("root")), Content.Empty)
    val dark = FramePayload(tree, 100, 80, 100, 80)
    val light = dark.copy(theme = ThemeSnapshot(ColorScheme.Light))
    val gate = new FrameCoordinator
    val first = gate.request(dark).get
    assert(gate.request(dark).isEmpty)
    val second = gate.request(light).get
    assert(second.payload.tree eq first.payload.tree)
    assert(!gate.accepts(first))
    assert(gate.accepts(second))
    assertEquals(first.payload.theme.scheme, ColorScheme.Dark)
    val third = gate.request(dark).get
    assert(!gate.accepts(second))
    assert(!gate.accepts(first))
    assert(gate.accepts(third))
  }
