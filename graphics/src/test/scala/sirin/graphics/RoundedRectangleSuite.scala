package sirin.graphics

class RoundedRectangleSuite extends munit.FunSuite:
  test("zero, degenerate and oversized corner radii use defined rectangle/clamping semantics") {
    val rect = Rect(-2.5, 7.25, 40, 20)
    assertEquals(Path.roundedRectangle(rect, 0), Path.rectangle(rect))
    assertEquals(Path.roundedRectangle(rect, 1000), Path.roundedRectangle(rect, 10))
    assertEquals(Path.roundedRectangle(Rect(0, 0, 0, 10), 100), Path.rectangle(Rect(0, 0, 0, 10)))
    assertEquals(Path.roundedRectangle(rect, 5).segments.last, PathSegment.Close)
    assertEquals(Path.roundedRectangle(rect, 5).segments.count {
      case PathSegment.CubicTo(_, _, _) => true
      case _ => false
    }, 4)
  }

  test("corner cubic has circular endpoints/tangents and bounded sampled radial deviation") {
    val radius = 10.0
    val path = Path.roundedRectangle(Rect(0, 0, 100, 60), radius)
    val PathSegment.CubicTo(a, b, end) = path.segments(2): @unchecked
    val start = Point(90, 0)
    val center = Point(90, 10)
    assertEquals(end, Point(100, 10))
    assertEquals(a.y, start.y)
    assertEquals(b.x, end.x)
    assert(a.x > start.x && b.y < end.y)
    for i <- 0 to 1000 do
      val t = i / 1000.0
      val u = 1 - t
      val x = u*u*u*start.x + 3*u*u*t*a.x + 3*u*t*t*b.x + t*t*t*end.x
      val y = u*u*u*start.y + 3*u*u*t*a.y + 3*u*t*t*b.y + t*t*t*end.y
      val distance = math.hypot(x - center.x, y - center.y)
      assert(math.abs(distance - radius) <= radius * 0.0003)
    // This is a sampled construction check, not a continuous error proof or pixel golden.
  }

  test("negative and nonfinite corner radii reject") {
    for r <- Vector(-1.0, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
      intercept[IllegalArgumentException](Path.roundedRectangle(Rect(0, 0, 10, 10), r))
  }
