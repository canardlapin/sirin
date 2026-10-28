package sirin.text

import sirin as ui
import sirin.graphics as g

class LayoutUnitsSuite extends munit.FunSuite:
  test("logical coordinates preserve fractional and large origins without display conversion") {
    val point = ui.Point(ui.Lp(-0.125), ui.Lp(1e12 + 0.25))
    assertEquals(LayoutUnits.point(point), g.Point(-0.125, 1e12 + 0.25))
    val rect = LayoutUnits.rect(g.Rect(-0.125, 1e12 + 0.25, 0, 32.75))
    assertEquals(rect.origin, point)
    assertEquals(rect.size, ui.Size(ui.Lp.zero, ui.Lp(32.75)))
    intercept[IllegalArgumentException](LayoutUnits.point(ui.Point(ui.Lp(Double.NaN), ui.Lp.zero)))
  }

  test("ARGB converts all unsigned channels including transparent RGB without premultiplication") {
    assertEquals(LayoutUnits.color(ui.Color.rgba(17, 83, 241, 193)), g.Color(17 / 255.0, 83 / 255.0, 241 / 255.0, 193 / 255.0))
    assertEquals(LayoutUnits.color(ui.Color.rgba(17, 83, 241, 0)), g.Color(17 / 255.0, 83 / 255.0, 241 / 255.0, 0))
    assertEquals(LayoutUnits.color(ui.Color.rgba(255, 255, 255, 255)), g.Color(1, 1, 1, 1))
  }
