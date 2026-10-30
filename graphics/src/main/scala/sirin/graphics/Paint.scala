package sirin.graphics

/** Unpremultiplied, tagged sRGB components in [0, 1]. Conversion to the compositing space and premultiplication belong
  * to backend preparation.
  */
final case class Color(red: Double, green: Double, blue: Double, alpha: Double = 1):
  require(
    Vector(red, green, blue, alpha).forall(v => v.isFinite && v >= 0 && v <= 1),
    "sRGB components must be finite and in [0, 1]"
  )

object Color:
  def rgba8(red: Int, green: Int, blue: Int, alpha: Int = 255): Color =
    require(Vector(red, green, blue, alpha).forall(v => v >= 0 && v <= 255), "byte components must be in [0, 255]")
    Color(red / 255.0, green / 255.0, blue / 255.0, alpha / 255.0)

final case class ColorStop(offset: Double, color: Color):
  require(offset.isFinite && offset >= 0 && offset <= 1, "stop offset must be in [0, 1]")

enum Extend:
  case Clamp,
    Repeat,
    Reflect

sealed trait Paint
object Paint:
  final case class Solid(color: Color) extends Paint

  /** Points are in paint coordinates, mapped into local drawing coordinates by paintToLocal. Stops are ordered;
    * coincident stops preserve supplied order for hard transitions. Colors interpolate in linear light, premultiplied.
    */
  final case class LinearGradient(
      start: Point,
      end: Point,
      stops: Vector[ColorStop],
      paintToLocal: Affine = Affine.identity,
      extend: Extend = Extend.Clamp
  ) extends Paint:
    require(start != end, "gradient endpoints must differ")
    require(stops.size >= 2, "a gradient needs at least two stops")
    require(
      stops.iterator.zip(stops.iterator.drop(1)).forall((a, b) => a.offset <= b.offset),
      "gradient stops must be ordered"
    )
    require(paintToLocal.inverse.nonEmpty, "paint transform must be invertible")

enum LineCap:
  case Butt,
    Round,
    Square

enum LineJoin:
  case Miter,
    Round,
    Bevel

final case class StrokeStyle(
    width: Double = 1,
    cap: LineCap = LineCap.Butt,
    join: LineJoin = LineJoin.Miter,
    miterLimit: Double = 4,
    dashes: Vector[Double] = Vector.empty,
    dashOffset: Double = 0
):
  require(width.isFinite && width > 0, "stroke width must be finite and positive")
  require(miterLimit.isFinite && miterLimit >= 1, "miter limit must be finite and >= 1")
  require(dashOffset.isFinite, "dash offset must be finite")
  require(dashes.forall(v => v.isFinite && v >= 0), "dashes must be finite and nonnegative")
  require(
    dashes.isEmpty || {
      val period = dashes.sum
      period > 0 && (
        period * (
          if dashes.size % 2 == 0 then
            1
          else
            2
        )
      ).isFinite
    },
    "normalized dash period must be finite and positive"
  )

  /** An odd-length pattern repeats twice before returning to its first on-segment. */
  def normalizedDashes: Vector[Double] =
    if dashes.size % 2 == 0 then
      dashes
    else
      dashes ++ dashes
