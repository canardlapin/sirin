package sirin.graphics

final case class WrappingLimits(maxLines: Int = 1024, maxCandidates: Int = 4096):
  require(maxLines > 0 && maxLines <= 65536 && maxCandidates > 0, "invalid wrapping budgets")

final case class PlacedTextLine(layout: TextLineLayout, origin: Point):
  def range: Utf8Range = layout.range
  def baseline: Double = origin.y + layout.baseline

/** Unicode-opportunity word wrapping of one paragraph, without trimming,
  * emergency breaks or hyphenation. Actual bounds can exceed maxWidth for an
  * unbreakable span; hasOverflow reports that separately from glyph coverage.
  */
final class WrappedParagraphLayout private[graphics] (
    val analysis: UnicodeAnalysis, val requestedFonts: Vector[ResolvedFont],
    val options: ParagraphOptions, val maxWidth: Double, val limits: WrappingLimits,
    val lines: Vector[PlacedTextLine]
):
  require(maxWidth.isFinite && maxWidth >= 0 && lines.nonEmpty && lines.size <= limits.maxLines,
    "invalid wrapped paragraph width/line count")
  val source: TextSource = analysis.source
  private val ends = Vector(0) ++ lines.map(_.range.end.value)
  private val tops = Vector(0.0) ++ lines.scanLeft(0.0)((y, line) => y + line.layout.height).drop(1)
  require(lines.indices.forall { i =>
    val line = lines(i)
    (line.layout.analysis eq analysis) && line.layout.requestedFonts == requestedFonts &&
      line.layout.options == options && line.range.start.value == ends(i) &&
      (line.range.end.value > ends(i) || (source.byteSize == 0 && lines.size == 1)) &&
      line.origin == Point(0, tops(i))
  } && ends.last == source.byteSize, "wrapped lines must partition source and have contiguous vertical placement")
  val width: Double = lines.map(_.layout.width).max
  val height: Double = lines.map(_.layout.height).sum
  require(width.isFinite && height.isFinite, "wrapped paragraph metrics overflow")
  val hasOverflow: Boolean = lines.exists(_.layout.width > maxWidth)
  val issues: Vector[TextIssue] = lines.flatMap(_.layout.issues)
  val isComplete: Boolean = lines.forall(_.layout.isComplete)
  val advanceBounds: Rect = Rect(0, 0, width, height)
  lazy val geometry: TextGeometry = TextGeometry.fromLines(analysis, lines)
  val inkBounds: Option[Rect] = lines.flatMap { placed =>
    placed.layout.inkBounds.map(r => Rect(r.x + placed.origin.x, r.y + placed.origin.y, r.width, r.height))
  }.reduceOption { (a, b) =>
    val x = math.min(a.x, b.x)
    val y = math.min(a.y, b.y)
    Rect(x, y, math.max(a.x + a.width, b.x + b.width) - x, math.max(a.y + a.height, b.y + b.height) - y)
  }
  def record(canvas: Canvas2D, origin: Point, paint: Paint): Unit =
    lines.foreach(p => p.layout.record(canvas, Point(origin.x + p.origin.x, origin.y + p.origin.y), paint))
