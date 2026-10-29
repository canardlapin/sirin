package sirin.graphics

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class GeometrySuite extends munit.ScalaCheckSuite:
  private val coordinate = Gen.choose(-10000.0, 10000.0)
  private val point = for
    x <- coordinate
    y <- coordinate
  yield Point(x, y)
  private val affine = for
    a <- Gen.choose(-10.0, 10.0)
    b <- Gen.choose(-10.0, 10.0)
    c <- Gen.choose(-10.0, 10.0)
    d <- Gen.choose(-10.0, 10.0)
    tx <- coordinate
    ty <- coordinate
  yield Affine(a, b, c, d, tx, ty)

  private def near(actual: Point, expected: Point, tolerance: Double = 1e-8): Unit =
    assertEqualsDouble(actual.x, expected.x, tolerance)
    assertEqualsDouble(actual.y, expected.y, tolerance)

  test("matrix convention, translation/scale order and clockwise y-down rotation") {
    val p = Point(2, 3)
    assertEquals(Affine(2, 3, 4, 5, 6, 7)(p), Point(22, 28))
    assertEquals(Affine.translation(10, 20).andThen(Affine.scale(2, 3))(p), Point(24, 69))
    assertEquals(Affine.scale(2, 3).andThen(Affine.translation(10, 20))(p), Point(14, 29))
    near(Affine.rotation(math.Pi / 2)(Point(1, 0)), Point(0, 1))
  }

  property("composition agrees with applying transforms sequentially") {
    forAll(point, affine, affine) { (p, first, second) =>
      near(first.andThen(second)(p), second(first(p)))
    }
  }

  property("inversion round trips translated, rotated, reflected and scaled points") {
    forAll(point, coordinate, coordinate, Gen.choose(0.1, 10.0)) { (p, x, y, scale) =>
      val matrix = Affine(1, 0.7, -0.2, 1, 0, 0).andThen(Affine.scale(scale, -scale))
        .andThen(Affine.rotation(0.3))
        .andThen(Affine.translation(x, y))
      near(matrix.inverse.get(matrix(p)), p)
    }
  }

  test("singular inversion is explicit and uniform extreme scales do not lose the determinant") {
    assertEquals(Affine.scale(0, 1).inverse, None)
    assertEquals(Affine(1, 2, 2, 4, 0, 0).inverse, None)
    for scale <- Vector(1e-150, 1e150) do
      val inverse = Affine.scale(scale, scale).inverse.get
      near(inverse(Affine.scale(scale, scale)(Point(3, 7))), Point(3, 7))
    assertEquals(Affine.scale(java.lang.Double.MIN_VALUE, 1).inverse, None)
  }

  test("large origins retain small offsets before GPU preparation") {
    val local = Point(0.125, 0.25)
    val origin = Affine.translation(1e13, -1e13)
    assertEquals(origin.inverse.get(origin(local)), local)
  }

  test("nonfinite coordinates and overflowing operations fail explicitly") {
    for value <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
      intercept[IllegalArgumentException](Point(value, 0))
      intercept[IllegalArgumentException](Affine.translation(0, value))
      intercept[IllegalArgumentException](Affine.rotation(value))
      intercept[IllegalArgumentException](Rect(0, 0, value, 1))
    intercept[IllegalArgumentException](Rect(0, 0, -1, 1))
    intercept[IllegalArgumentException](Rect(Double.MaxValue, 0, Double.MaxValue, 1))
    intercept[IllegalArgumentException](Affine.scale(Double.MaxValue, 1)(Point(2, 0)))
  }
