// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

/** Logical pixels. Device pixels (`Px`) belong to the backend-author API. */
opaque type Lp = Double

object Lp:
  val zero: Lp = 0.0
  def apply(value: Double): Lp = value

  extension (a: Lp)
    def toDouble: Double = a
    def +(b: Lp): Lp = a + b
    def -(b: Lp): Lp = a - b
    def *(k: Double): Lp = a * k
    def /(k: Double): Lp = a / k
    def max(b: Lp): Lp = math.max(a, b)
    def min(b: Lp): Lp = math.min(a, b)
    def compare(b: Lp): Int = java.lang.Double.compare(a, b)

extension (n: Int)
  def lp: Lp = Lp(n.toDouble)
extension (n: Double)
  def lp: Lp = Lp(n)

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
