package sirin.engine

import sirin.*

/** Host-side viewport; changing it stages structure, never performs layout or painting. */
final case class RowViewport(offset: Lp, height: Lp):
  require(offset.toDouble.isFinite && offset.toDouble >= 0 && height.toDouble.isFinite && height.toDouble >= 0,
    "finite nonnegative viewport required")
object RowViewport:
  val empty: RowViewport = RowViewport(Lp.zero, Lp.zero)
  private[engine] def bounds(viewport: RowViewport, rowHeight: Lp, overscan: Int): IndexRange =
    if viewport.height.toDouble == 0 then IndexRange(0, 0)
    else
      val first = math.floor(viewport.offset.toDouble / rowHeight.toDouble).toLong.min(Int.MaxValue.toLong)
      val end = math.ceil((viewport.offset.toDouble + viewport.height.toDouble) / rowHeight.toDouble).toLong.min(Int.MaxValue.toLong)
      IndexRange((first - overscan.toLong).max(0L).toInt, (end + overscan.toLong).min(Int.MaxValue.toLong).toInt)
