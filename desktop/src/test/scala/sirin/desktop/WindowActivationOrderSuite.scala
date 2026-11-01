package sirin.desktop

class WindowActivationOrderSuite extends munit.FunSuite:
  test("closing the active window returns to the most recently active surviving window") {
    val order = new WindowActivationOrder
    order.observe(Vector(1L, 2L, 3L), Some(2L))
    order.observe(Vector(1L, 2L, 3L), Some(1L))
    order.observe(Vector(1L, 2L, 3L), Some(3L))
    assertEquals(order.afterRetirement(Vector(1L, 2L), true, None, false), Some(1L))
    assertEquals(order.afterRetirement(Vector(2L), true, None, false), Some(2L))
    assertEquals(order.afterRetirement(Vector.empty, true, None, false), None)
  }

  test("background retirement, application deactivation and Quit never steal activation") {
    val order = new WindowActivationOrder
    order.observe(Vector(1L, 2L), Some(2L))
    assertEquals(order.afterRetirement(Vector(2L), false, Some(2L), false), None)
    order.observe(Vector(1L, 2L), None)
    assertEquals(order.afterRetirement(Vector(1L), false, None, false), None)
    assertEquals(order.afterRetirement(Vector(1L), true, None, true), None)
  }

  test("native or newly created window activation wins and retired IDs are pruned") {
    val order = new WindowActivationOrder
    order.observe(Vector(1L, 2L), Some(2L))
    assertEquals(order.afterRetirement(Vector(1L, 3L), true, Some(3L), false), None)
    assertEquals(order.afterRetirement(Vector(1L), true, None, false), Some(1L))
    assertEquals(order.afterRetirement(Vector.empty, true, None, false), None)
    assertEquals(order.afterRetirement(Vector(4L, 5L), true, None, false), Some(5L))
  }
