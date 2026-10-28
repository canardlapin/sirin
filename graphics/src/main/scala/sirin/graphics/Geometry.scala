package sirin.graphics

/** Finite, Double-precision coordinates. Units are chosen by the drawing's host;
  * recording never converts to device pixels or snaps coordinates.
  */
final case class Point(x: Double, y: Double):
  require(x.isFinite && y.isFinite, "point coordinates must be finite")

final case class Rect(x: Double, y: Double, width: Double, height: Double):
  require(x.isFinite && y.isFinite, "rectangle origin must be finite")
  require(width.isFinite && width >= 0 && height.isFinite && height >= 0,
    "rectangle dimensions must be finite and nonnegative")
  require((x + width).isFinite && (y + height).isFinite, "rectangle edges must be finite")

/** Matrix [a c tx; b d ty; 0 0 1], acting on column vectors.
  * `first.andThen(second)` applies first, then second.
  */
final case class Affine(
    a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double
):
  require(Vector(a, b, c, d, tx, ty).forall(_.isFinite), "affine coefficients must be finite")

  def apply(point: Point): Point =
    Point(a * point.x + c * point.y + tx, b * point.x + d * point.y + ty)

  def andThen(next: Affine): Affine = Affine(
    next.a * a + next.c * b, next.b * a + next.d * b,
    next.a * c + next.c * d, next.b * c + next.d * d,
    next.a * tx + next.c * ty + next.tx, next.b * tx + next.d * ty + next.ty
  )

  /** None for a singular matrix or an inverse whose computation is nonfinite.
    * Scaling avoids determinant overflow/underflow for uniformly large/small
    * transforms. This is Double arithmetic, not an exact singularity predicate.
    */
  def inverse: Option[Affine] =
    val scale = math.max(math.max(math.abs(a), math.abs(b)), math.max(math.abs(c), math.abs(d)))
    if scale == 0 then None
    else
      val na = a / scale
      val nb = b / scale
      val nc = c / scale
      val nd = d / scale
      val det = na * nd - nb * nc
      if det == 0 then None
      else
        val ia = (nd / det) / scale
        val ib = (-nb / det) / scale
        val ic = (-nc / det) / scale
        val id = (na / det) / scale
        val ix = -(ia * tx + ic * ty)
        val iy = -(ib * tx + id * ty)
        if Vector(ia, ib, ic, id, ix, iy).forall(_.isFinite) then Some(Affine(ia, ib, ic, id, ix, iy))
        else None

object Affine:
  val identity: Affine = Affine(1, 0, 0, 1, 0, 0)
  def translation(x: Double, y: Double): Affine = Affine(1, 0, 0, 1, x, y)
  def scale(x: Double, y: Double): Affine = Affine(x, 0, 0, y, 0, 0)

  /** Positive angles turn clockwise in a y-down coordinate space. */
  def rotation(radians: Double): Affine =
    require(radians.isFinite, "rotation must be finite")
    val cos = math.cos(radians)
    val sin = math.sin(radians)
    Affine(cos, sin, -sin, cos, 0, 0)
