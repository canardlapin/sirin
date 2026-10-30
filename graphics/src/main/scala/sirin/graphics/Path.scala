package sirin.graphics

import scala.collection.mutable.ArrayBuffer
import scala.annotation.targetName
import sirin.units.Lp

enum FillRule:
  case NonZero,
    EvenOdd

enum PathSegment:
  case MoveTo(to: Point)
  case LineTo(to: Point)
  case QuadraticTo(control: Point, to: Point)
  case CubicTo(control1: Point, control2: Point, to: Point)
  case Close

/** Immutable geometry, shared by fills, strokes, clips and instance batches. Multiple subpaths are preserved. Closing
  * returns the current point to the subpath start; a later line/curve may continue from that point.
  */
final case class Path(segments: Vector[PathSegment]):
  Path.validate(segments)

object Path:
  private def validate(segments: Vector[PathSegment]): Unit =
    var hasCurrent = false
    segments.foreach {
      case PathSegment.MoveTo(_) =>
        hasCurrent = true
      case _ =>
        require(hasCurrent, "a path must moveTo before drawing or closing")
    }

  val empty: Path = Path(Vector.empty)

  def rectangle(rect: Rect): Path = build { p =>
    p.moveTo(rect.x, rect.y)
    p.lineTo(rect.x + rect.width, rect.y)
    p.lineTo(rect.x + rect.width, rect.y + rect.height)
    p.lineTo(rect.x, rect.y + rect.height)
    p.close()
  }

  /** Four cubic approximations of circular corners, with tangent continuity. Radius is clamped to half the shorter
    * side; zero/degenerate dimensions use the exact rectangle path. This is not an exact circular-arc primitive.
    */
  @targetName("logicalRoundedRectangle")
  def roundedRectangle(rect: Rect, radius: Lp): Path = roundedRectangle(rect, radius.toDouble)

  def roundedRectangle(rect: Rect, radius: Double): Path =
    require(radius.isFinite && radius >= 0, "corner radius must be finite and nonnegative")
    val r = math.min(radius, math.min(rect.width / 2, rect.height / 2))
    if r == 0 then
      rectangle(rect)
    else
      build { p =>
        val x = rect.x
        val y = rect.y
        val right = x + rect.width
        val bottom = y + rect.height
        val k = r * 0.5522847498307936
        p.moveTo(x + r, y)
        p.lineTo(right - r, y)
        p.cubicTo(Point(right - r + k, y), Point(right, y + r - k), Point(right, y + r))
        p.lineTo(right, bottom - r)
        p.cubicTo(Point(right, bottom - r + k), Point(right - r + k, bottom), Point(right - r, bottom))
        p.lineTo(x + r, bottom)
        p.cubicTo(Point(x + r - k, bottom), Point(x, bottom - r + k), Point(x, bottom - r))
        p.lineTo(x, y + r)
        p.cubicTo(Point(x, y + r - k), Point(x + r - k, y), Point(x + r, y))
        p.close()
      }

  def build(body: Builder => Unit): Path =
    val builder = new Builder
    try
      body(builder)
      Path(builder.segments.toVector)
    finally
      builder.open = false
      builder.segments.clear()

  /** A lexical construction capability; retaining it does not permit mutation of a finished path. Not safe for
    * concurrent use.
    */
  final class Builder private[Path] ():
    private[Path] val segments = ArrayBuffer.empty[PathSegment]
    private[Path] var open = true
    private var hasCurrent = false

    private def add(segment: PathSegment): Unit =
      require(open, "path builder is closed")
      segment match
        case PathSegment.MoveTo(_) =>
          hasCurrent = true
        case _ =>
          require(hasCurrent, "a path must moveTo before drawing or closing")
      segments += segment

    @targetName("logicalMoveTo")
    def moveTo(x: Lp, y: Lp): Unit = moveTo(x.toDouble, y.toDouble)
    @targetName("logicalLineTo")
    def lineTo(x: Lp, y: Lp): Unit = lineTo(x.toDouble, y.toDouble)
    def moveTo(x: Double, y: Double): Unit = add(PathSegment.MoveTo(Point(x, y)))
    def lineTo(x: Double, y: Double): Unit = add(PathSegment.LineTo(Point(x, y)))
    def quadraticTo(control: Point, to: Point): Unit = add(PathSegment.QuadraticTo(control, to))
    def cubicTo(control1: Point, control2: Point, to: Point): Unit = add(PathSegment.CubicTo(control1, control2, to))
    def close(): Unit = add(PathSegment.Close)
