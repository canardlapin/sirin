package sirin.graphics

class ValuesSuite extends munit.FunSuite:
  private val red = Color.rgba8(255, 0, 0)
  private val blue = Color.rgba8(0, 0, 255)

  test("paths preserve subpaths, holes and exact curve control points") {
    val path = Path.build { p =>
      p.moveTo(0, 0)
      p.lineTo(10, 0)
      p.quadraticTo(Point(15, 5), Point(10, 10))
      p.cubicTo(Point(5, 15), Point(-5, 15), Point(0, 0))
      p.close()
      p.moveTo(3, 3)
      p.lineTo(4, 3)
      p.lineTo(3, 4)
      p.close()
    }
    assertEquals(path.segments, Vector(
      PathSegment.MoveTo(Point(0, 0)), PathSegment.LineTo(Point(10, 0)),
      PathSegment.QuadraticTo(Point(15, 5), Point(10, 10)),
      PathSegment.CubicTo(Point(5, 15), Point(-5, 15), Point(0, 0)), PathSegment.Close,
      PathSegment.MoveTo(Point(3, 3)), PathSegment.LineTo(Point(4, 3)),
      PathSegment.LineTo(Point(3, 4)), PathSegment.Close
    ))
    assertEquals(Path.empty.segments, Vector.empty)
  }

  test("rectangle preserves fractional coordinates and explicit closure") {
    assertEquals(Path.rectangle(Rect(0.25, 0.5, 2, 3)).segments, Vector(
      PathSegment.MoveTo(Point(0.25, 0.5)), PathSegment.LineTo(Point(2.25, 0.5)),
      PathSegment.LineTo(Point(2.25, 3.5)), PathSegment.LineTo(Point(0.25, 3.5)), PathSegment.Close
    ))
  }

  test("path construction rejects missing current points, including through copy") {
    intercept[IllegalArgumentException](Path(Vector(PathSegment.Close)))
    intercept[IllegalArgumentException](Path.build(_.lineTo(1, 1)))
    intercept[IllegalArgumentException](Path.empty.copy(segments = Vector(PathSegment.LineTo(Point(1, 2)))))
  }

  test("escaped builders cannot change finished paths and close after failure") {
    var saved: Option[Path.Builder] = None
    val path = Path.build { p => saved = Some(p); p.moveTo(1, 2) }
    intercept[IllegalArgumentException](saved.get.lineTo(3, 4))
    assertEquals(path.segments, Vector(PathSegment.MoveTo(Point(1, 2))))
    intercept[RuntimeException] {
      Path.build { p => saved = Some(p); throw new RuntimeException("failed path") }
    }
    intercept[IllegalArgumentException](saved.get.moveTo(0, 0))
  }

  test("sRGB alpha remains unpremultiplied and invalid colors are not masked or clamped") {
    assertEquals(Color.rgba8(255, 0, 0, 0), Color(1, 0, 0, 0))
    assertEquals(Color.rgba8(0, 255, 0, 255), Color(0, 1, 0, 1))
    intercept[IllegalArgumentException](Color.rgba8(256, 0, 0))
    intercept[IllegalArgumentException](Color(0, 0, 0, Double.NaN))
    intercept[IllegalArgumentException](Color(0, -0.01, 0))
  }

  test("gradient stops and paint coordinates retain hard transitions and anchoring") {
    val stops = Vector(ColorStop(0, red), ColorStop(0.5, red), ColorStop(0.5, blue), ColorStop(1, blue))
    val gradient = Paint.LinearGradient(Point(0, 0), Point(10, 0), stops,
      Affine.translation(3, 7), Extend.Reflect)
    assertEquals(gradient.stops, stops)
    assertEquals(gradient.paintToLocal(Point(0, 0)), Point(3, 7))
    intercept[IllegalArgumentException](gradient.copy(stops = stops.reverse))
    intercept[IllegalArgumentException](gradient.copy(stops = Vector(stops.head)))
    intercept[IllegalArgumentException](gradient.copy(end = gradient.start))
    intercept[IllegalArgumentException](gradient.copy(paintToLocal = Affine.scale(0, 1)))
    intercept[IllegalArgumentException](ColorStop(Double.NaN, red))
  }

  test("dash normalization preserves phase, zero-length segments and odd pattern period") {
    val style = StrokeStyle(width = 0.25, dashes = Vector(3, 0, 2), dashOffset = -4)
    assertEquals(style.normalizedDashes, Vector(3.0, 0.0, 2.0, 3.0, 0.0, 2.0))
    assertEquals(style.dashOffset, -4.0)
    assertEquals(StrokeStyle().normalizedDashes, Vector.empty)
    intercept[IllegalArgumentException](style.copy(dashes = Vector(0, 0)))
    intercept[IllegalArgumentException](style.copy(dashes = Vector(-1, 2)))
    intercept[IllegalArgumentException](style.copy(dashes = Vector(Double.MaxValue)))
    intercept[IllegalArgumentException](style.copy(width = 0))
    intercept[IllegalArgumentException](style.copy(miterLimit = 0.5))
  }
