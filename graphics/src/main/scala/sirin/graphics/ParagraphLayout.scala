package sirin.graphics

final case class ParagraphLimits(
    maxInputBytes: Int = 65536, maxItems: Int = 4096, maxFaces: Int = 16,
    maxShapeCalls: Int = 8192, maxShapedBytes: Long = 16777216, maxShapedGlyphs: Int = 262144,
    maxBracketDepth: Int = 256
):
  require(maxInputBytes > 0 && maxInputBytes <= 1048576 && maxItems > 0 && maxItems <= 65536 &&
    maxFaces > 0 && maxFaces <= 64 && maxShapeCalls > 0 && maxShapedBytes > 0 && maxShapedGlyphs > 0 && maxBracketDepth > 0,
    "invalid paragraph budgets")

final case class ParagraphOptions(
    language: String = "und", direction: ParagraphDirection = ParagraphDirection.Auto,
    standardLigatures: Boolean = true, limits: ParagraphLimits = ParagraphLimits()
):
  // Share language validation with the explicit run API.
  ShapingOptions(Script.Common, TextDirection.LeftToRight, language, standardLigatures)

enum TextIssueReason:
  case MissingGlyphs, UnsupportedVariationSequence

final case class TextIssue(range: Utf8Range, reason: TextIssueReason)
final case class ResolvedTextRun(run: ShapedRun, level: Int):
  require(level >= 0 && level <= 126, "invalid text run level")
  require(run.options.direction == (if level % 2 == 0 then TextDirection.LeftToRight else TextDirection.RightToLeft),
    "shaping direction must match resolved level")

final case class PlacedTextRun(resolved: ResolvedTextRun, origin: Point)

/** A horizontal line with global source mappings and line-local positions.
  * Used by both unwrapped and wrapped paragraph results. Its source retains
  * paragraph analysis; its shaping context is bounded to this line.
  */
final class TextLineLayout private[graphics] (
    val analysis: UnicodeAnalysis, val range: Utf8Range, val bidi: Option[BidiLine], val items: Vector[ScriptItem],
    val requestedFonts: Vector[ResolvedFont], val options: ParagraphOptions,
    val logicalRuns: Vector[ResolvedTextRun], val runs: Vector[PlacedTextRun],
    val issues: Vector[TextIssue], val width: Double, val height: Double, val baseline: Double
):
  val source: TextSource = analysis.source
  private def partitions(ranges: Vector[Utf8Range]): Boolean =
    val ends = Vector(range.start.value) ++ ranges.map(_.end.value)
    ranges.indices.forall(i => ranges(i).start.value == ends(i) && ranges(i).end.value > ends(i)) &&
      ends.last == range.end.value
  require(partitions(items.map(_.range)) && partitions(logicalRuns.map(_.run.range)),
    "line items and logical runs must partition the source range")
  require(source.utf16Offset(range.start).nonEmpty && source.utf16Offset(range.end).nonEmpty &&
    analysis.isGraphemeBoundary(range.start) && analysis.isGraphemeBoundary(range.end),
    "line range must use source grapheme boundaries")
  require(bidi.forall(b => (b.analysis eq analysis) && b.range == range) &&
    (range.start == range.end || bidi.nonEmpty), "line bidi result must match its source range")
  require(requestedFonts.nonEmpty && width.isFinite && width >= 0 && height.isFinite && height > 0 &&
    baseline.isFinite && baseline >= 0 && baseline <= height, "invalid paragraph metrics")
  require(runs.map(_.resolved).toSet == logicalRuns.toSet && runs.size == logicalRuns.size,
    "placed runs must retain every resolved run")
  require(logicalRuns.forall(r => (r.run.source eq analysis.source) && r.run.context == range && requestedFonts.contains(r.run.font)),
    "line runs must retain source, line context and admitted fonts")
  val isComplete: Boolean = issues.isEmpty && !logicalRuns.exists(_.run.hasMissingGlyphs)
  val advanceBounds: Rect = Rect(0, 0, width, height)
  lazy val geometry: TextGeometry = TextGeometry.fromLines(analysis, Vector(PlacedTextLine(this, Point(0, 0))))
  val inkBounds: Option[Rect] = runs.flatMap { placed =>
    placed.resolved.run.inkBounds.map(r => Rect(r.x + placed.origin.x, r.y + placed.origin.y, r.width, r.height))
  }.reduceOption { (a, b) =>
    val x = math.min(a.x, b.x)
    val y = math.min(a.y, b.y)
    Rect(x, y, math.max(a.x + a.width, b.x + b.width) - x, math.max(a.y + a.height, b.y + b.height) - y)
  }

  /** Exact recording: no font discovery, fallback, shaping or measurement. */
  def record(canvas: Canvas2D, origin: Point, paint: Paint): Unit =
    runs.foreach(p => canvas.glyphs(p.resolved.run, Point(origin.x + p.origin.x, origin.y + p.origin.y), paint))

/** One unwrapped paragraph, retaining the same line representation and kernel
  * as wrapped layout, including shared editing geometry.
  */
final class ParagraphLayout private[graphics] (val line: TextLineLayout):
  require(line.range == Utf8Range(Utf8Offset(0), Utf8Offset(line.source.byteSize)),
    "unwrapped paragraph must cover its full source")
  val analysis: UnicodeAnalysis = line.analysis
  val source: TextSource = line.source
  val bidi: Option[BidiLine] = line.bidi
  val items: Vector[ScriptItem] = line.items
  val requestedFonts: Vector[ResolvedFont] = line.requestedFonts
  val options: ParagraphOptions = line.options
  val logicalRuns: Vector[ResolvedTextRun] = line.logicalRuns
  val runs: Vector[PlacedTextRun] = line.runs
  val issues: Vector[TextIssue] = line.issues
  val width: Double = line.width
  val height: Double = line.height
  val baseline: Double = line.baseline
  val isComplete: Boolean = line.isComplete
  val advanceBounds: Rect = line.advanceBounds
  val inkBounds: Option[Rect] = line.inkBounds
  def geometry: TextGeometry = line.geometry
  def record(canvas: Canvas2D, origin: Point, paint: Paint): Unit = line.record(canvas, origin, paint)
