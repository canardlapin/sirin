// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

/** Logical pixels shared with standalone drawing, without a core/graphics dependency. */
type Lp = sirin.units.Lp
val Lp: sirin.units.Lp.type = sirin.units.Lp
export sirin.units.syntax.*

final case class Point(x: Lp, y: Lp):
  def +(d: Point): Point = Point(x + d.x, y + d.y)
  def -(d: Point): Point = Point(x - d.x, y - d.y)

object Point:
  val origin: Point = Point(Lp.zero, Lp.zero)

final case class Size(width: Lp, height: Lp)

final case class Rect(origin: Point, size: Size):
  def contains(p: Point): Boolean =
    p.x.compare(origin.x) >= 0 && p.y.compare(origin.y) >= 0 && p.x.compare(origin.x + size.width) < 0 &&
      p.y.compare(origin.y + size.height) < 0
  def center: Point = Point(origin.x + size.width / 2, origin.y + size.height / 2)

/** Box constraints (VISION §8): constraints go down, sizes come up. */
final case class Constraints(minWidth: Lp, maxWidth: Lp, minHeight: Lp, maxHeight: Lp):
  def biggest: Size = Size(maxWidth, maxHeight)
  def smallest: Size = Size(minWidth, minHeight)
  def loosen: Constraints = copy(minWidth = Lp.zero, minHeight = Lp.zero)

/** sRGB color, premultiplied at the renderer boundary (VISION §7). */
opaque type Color = Int

object Color:
  def rgba(r: Int, g: Int, b: Int, a: Int = 255): Color =
    ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)
  extension (c: Color)
    def argb: Int = c

/** Finite affine paint transform, composed by the standalone graphics backend. */
final case class Transform2D(a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double):
  require(Vector(a, b, c, d, tx, ty).forall(_.isFinite), "transform coefficients must be finite")
  def apply(point: Point): Point = Point(
    (a * point.x.toDouble + c * point.y.toDouble + tx).lp,
    (b * point.x.toDouble + d * point.y.toDouble + ty).lp
  )
object Transform2D:
  val identity: Transform2D = Transform2D(1, 0, 0, 1, 0, 0)
  def camera(origin: Point, scale: Double): Transform2D =
    require(scale.isFinite && scale > 0, "camera scale must be positive and finite")
    Transform2D(scale, 0, 0, scale, origin.x.toDouble, origin.y.toDouble)
