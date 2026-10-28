package sirin.graphics.text

import sirin.graphics.*
import sirin.graphics.unicode.NativeUnicode
import scala.collection.mutable.ArrayBuffer

/** Shared line engine; caller owns native services, results own immutable data. */
object NativeParagraph:
  def layout(text: String, faces: Vector[NativeFont], unicode: NativeUnicode,
      options: ParagraphOptions = ParagraphOptions()): ParagraphLayout =
    val engine = new Engine(text, faces, unicode, options)
    new ParagraphLayout(engine.line(engine.fullRange))

  /** Exact furthest-fitting Unicode opportunity. If none fits, retain the first
    * unbreakable span and report overflow. Work budgets cover every candidate.
    */
  def wrap(text: String, faces: Vector[NativeFont], unicode: NativeUnicode, maxWidth: Double,
      options: ParagraphOptions = ParagraphOptions(), limits: WrappingLimits = WrappingLimits()): WrappedParagraphLayout =
    require(maxWidth.isFinite && maxWidth >= 0, "wrapping width must be finite and nonnegative")
    require(!text.contains('\u00AD'), "discretionary soft hyphens require a hyphenation policy")
    val engine = new Engine(text, faces, unicode, options)
    val breaks = engine.analysis.lineBoundaries.filter(engine.analysis.isGraphemeBoundary)
    val placed = ArrayBuffer.empty[PlacedTextLine]
    var candidates = 0
    var chosenRuns = 0
    var start = Utf8Offset(0)
    var y = 0.0
    while placed.isEmpty || start.value < engine.analysis.source.byteSize do
      require(placed.size < limits.maxLines, "paragraph exceeds wrapped line budget")
      var first: Option[TextLineLayout] = None
      var best: Option[TextLineLayout] = None
      val ends = if engine.analysis.source.byteSize == 0 then Vector(Utf8Offset(0)) else breaks.filter(_.value > start.value)
      // Contextual substitution/kerning can make widths non-monotonic. An
      // overflowing candidate does not prove that a later one cannot fit.
      ends.foreach { end =>
        require(candidates < limits.maxCandidates, "paragraph exceeds candidate line budget")
        candidates += 1
        val candidate = engine.line(Utf8Range(start, end))
        if first.isEmpty then first = Some(candidate)
        if candidate.width <= maxWidth then best = Some(candidate)
      }
      val selected = best.orElse(first).getOrElse(throw new IllegalArgumentException("no legal grapheme-aligned line end"))
      chosenRuns += selected.logicalRuns.size
      require(chosenRuns <= options.limits.maxItems, "paragraph exceeds total resolved run budget")
      placed += PlacedTextLine(selected, Point(0, y))
      y += selected.height
      require(y.isFinite, "wrapped paragraph height overflow")
      start = selected.range.end
    new WrappedParagraphLayout(engine.analysis, faces.map(_.font), options, maxWidth, limits, placed.toVector)

  private final class Engine(text: String, faces: Vector[NativeFont], unicode: NativeUnicode, options: ParagraphOptions):
    private val limits = options.limits
    require(faces.nonEmpty && faces.size <= limits.maxFaces && faces.distinct.size == faces.size,
      "paragraph requires a nonempty bounded distinct list of supplied faces")
    faces.foreach(_.checkOpen())
    require(text.length <= limits.maxInputBytes, "paragraph exceeds input code-unit budget")
    require(!text.exists(c => "\t\n\r\u000B\u000C\u0085\u2028\u2029".contains(c)),
      "single paragraph layout does not support tabs or hard line separators")
    val analysis: UnicodeAnalysis = unicode.analyze(text, options.direction)
    private val source = analysis.source
    require(source.byteSize <= limits.maxInputBytes, "paragraph exceeds input UTF-8 budget")
    require(analysis.paragraphs.size <= 1, "layout requires a single paragraph")
    val fullRange: Utf8Range = Utf8Range(Utf8Offset(0), Utf8Offset(source.byteSize))
    private val properties = unicode.properties(source)
    private val scripts = if text.isEmpty then Vector.empty else
      ScriptItemization(analysis, unicode.resolveLine(analysis, fullRange), properties, limits.maxBracketDepth)
    require(scripts.size <= limits.maxItems, "paragraph exceeds script item budget")
    private var shapeCalls = 0
    private var shapedBytes = 0L
    private var shapedGlyphs = 0

    private def shape(face: NativeFont, range: Utf8Range, item: ScriptItem, context: Utf8Range): ShapedRun =
      require(shapeCalls < limits.maxShapeCalls && source.byteSize <= limits.maxShapedBytes - shapedBytes,
        "paragraph exceeds shaping work budget")
      shapeCalls += 1
      shapedBytes += source.byteSize // full owned source is still copied per attempt
      val run = face.shapeRange(source, range, ShapingOptions(item.script,
        if item.level % 2 == 0 then TextDirection.LeftToRight else TextDirection.RightToLeft,
        options.language, options.standardLigatures), context)
      require(run.glyphs.size <= limits.maxShapedGlyphs - shapedGlyphs, "paragraph exceeds shaped glyph budget")
      shapedGlyphs += run.glyphs.size
      run

    private def lineItems(bidi: BidiLine): Vector[ScriptItem] =
      val result = ArrayBuffer.empty[ScriptItem]
      val boundaries = analysis.graphemeBoundaries.filter(o => o.value >= bidi.range.start.value && o.value <= bidi.range.end.value)
      var scriptIndex = 0
      var cp = 0
      boundaries.zip(boundaries.drop(1)).foreach { (start, end) =>
        while scripts(scriptIndex).range.end.value <= start.value do scriptIndex += 1
        val first = cp
        while cp < bidi.codepoints.size && bidi.codepoints(cp).range.start.value < end.value do cp += 1
        val levels = bidi.codepoints.slice(first, cp).flatMap(_.level).distinct
        require(levels.size <= 1, "bidi level boundary inside a grapheme is unsupported")
        val level = levels.headOption.getOrElse(bidi.paragraphLevel)
        val script = scripts(scriptIndex).script // paragraph script/bracket context persists across wraps
        if result.nonEmpty && result.last.script == script && result.last.level == level then
          result(result.size - 1) = result.last.copy(range = Utf8Range(result.last.range.start, end))
        else result += ScriptItem(Utf8Range(start, end), script, level)
      }
      result.toVector

    def line(range: Utf8Range): TextLineLayout =
      val bidi = if text.isEmpty then None else Some(unicode.resolveLine(analysis, range))
      val items = bidi.map(lineItems).getOrElse(Vector.empty)
      require(items.size <= limits.maxItems, "line exceeds script item budget")
      val logical = ArrayBuffer.empty[ResolvedTextRun]
      val issues = ArrayBuffer.empty[TextIssue]
      items.foreach { item =>
        val attempts = ArrayBuffer.empty[ShapedRun]
        var complete: Option[ShapedRun] = None
        var faceIndex = 0
        while faceIndex < faces.size && complete.isEmpty do
          val run = shape(faces(faceIndex), item.range, item, range)
          attempts += run
          if !run.hasMissingGlyphs then complete = Some(run)
          faceIndex += 1
        complete match
          case Some(run) => logical += ResolvedTextRun(run, item.level)
          case None =>
            var boundaries = analysis.graphemeBoundaries.filter(o => o.value >= item.range.start.value && o.value <= item.range.end.value)
            attempts.foreach { run =>
              val starts = (Vector(item.range.start) ++ run.glyphs.map(_.cluster)).distinct.sortBy(_.value)
              val allowed = starts.toSet + item.range.end
              boundaries = boundaries.filter(allowed.contains)
            }
            val chosen = ArrayBuffer.empty[(Utf8Range, Int)]
            boundaries.zip(boundaries.drop(1)).foreach { (start, end) =>
              val atom = Utf8Range(start, end)
              var selected = -1
              var f = 0
              while f < faces.size && selected < 0 do
                if !shape(faces(f), atom, item, range).hasMissingGlyphs then selected = f
                f += 1
              if selected < 0 then selected = 0
              if chosen.nonEmpty && chosen.last._2 == selected then
                chosen(chosen.size - 1) = (Utf8Range(chosen.last._1.start, end), selected)
              else chosen += ((atom, selected))
            }
            chosen.foreach { (atom, f) => logical += ResolvedTextRun(shape(faces(f), atom, item, range), item.level) }
      }
      require(logical.size <= limits.maxItems, "line exceeds resolved run budget")
      logical.foreach { resolved =>
        val run = resolved.run
        val starts = (Vector(run.range.start) ++ run.glyphs.map(_.cluster)).distinct.sortBy(_.value)
        val ends = starts.zip(starts.drop(1) :+ run.range.end).toMap
        run.glyphs.filter(_.id == 0).map(_.cluster).distinct.foreach { start =>
          issues += TextIssue(Utf8Range(start, ends(start)), TextIssueReason.MissingGlyphs)
        }
      }
      properties.filter(p => p.variationSelector && p.range.start.value >= range.start.value && p.range.end.value <= range.end.value)
        .foreach(p => issues += TextIssue(p.range, TextIssueReason.UnsupportedVariationSequence))
      val fonts = if logical.nonEmpty then logical.map(_.run.font).toVector else Vector(faces.head.font)
      val ascent = fonts.map(_.metrics.ascent).max
      val descent = fonts.map(_.metrics.descent).max
      val leading = math.max(0.0, fonts.map(_.metrics.lineGap).max)
      val baseline = ascent + leading / 2
      val placed = ArrayBuffer.empty[PlacedTextRun]
      var x = 0.0
      unicode.visualOrder(logical.map(_.level).toVector).foreach { index =>
        val resolved = logical(index)
        require(resolved.run.advance.x >= 0 && resolved.run.advance.y == 0, "only forward horizontal run advances are supported")
        placed += PlacedTextRun(resolved, Point(x, baseline))
        x += resolved.run.advance.x
        require(x.isFinite, "paragraph advance overflow")
      }
      new TextLineLayout(analysis, range, bidi, items, faces.map(_.font), options, logical.toVector,
        placed.toVector, issues.toVector, x, ascent + descent + leading, baseline)
