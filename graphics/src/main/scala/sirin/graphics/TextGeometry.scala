package sirin.graphics

/** Affinity is logical: Upstream uses the preceding grapheme's trailing edge;
  * Downstream uses the following grapheme's leading edge. At a wrap these can
  * be on different lines, and at a bidi boundary at different x coordinates.
  */
enum CaretAffinity:
  case Upstream, Downstream

final case class TextPosition(offset: Utf8Offset, affinity: CaretAffinity)

enum CaretEdgeKind:
  case ClusterEdge, FontLigature, Interpolated

/** Zero-width line-height bar in layout coordinates, not glyph ink bounds. */
final case class TextCaret(position: TextPosition, lineIndex: Int, bounds: Rect, kind: CaretEdgeKind)

final case class GraphemeGeometry(range: Utf8Range, leading: TextCaret, trailing: TextCaret):
  def bounds: Rect = Rect(math.min(leading.bounds.x, trailing.bounds.x), leading.bounds.y,
    math.abs(trailing.bounds.x - leading.bounds.x), leading.bounds.height)

/** Collocated/equidistant stops remain visible instead of losing bidi or
  * zero-width-control ambiguity. Primary ties prefer downstream, then source
  * order. A hit always clamps to one line and the nearest horizontal stop.
  */
final case class TextHit(primary: TextCaret, alternatives: Vector[TextCaret])
final case class TextSelectionRegion(lineIndex: Int, bounds: Rect, interpolated: Boolean)

/** Editing geometry derived lazily from the exact stored line runs. No native
  * handles, layout calls, font lookup or reshaping are needed by its queries.
  * All public source endpoints must be grapheme boundaries; invalid caret
  * queries return None, and invalid selections reject rather than snap.
  */
final class TextGeometry private (
    val analysis: UnicodeAnalysis, val range: Utf8Range,
    val graphemes: Vector[GraphemeGeometry], val carets: Vector[TextCaret],
    private val lineBoxes: Vector[Rect]
):
  val source: TextSource = analysis.source
  private val byPosition = carets.map(c => c.position -> c).toMap
  private val byOffset = carets.groupBy(_.position.offset)
  private val byLine = carets.groupBy(_.lineIndex).view.mapValues { values =>
    values.groupBy(_.bounds.x).toVector.sortBy(_._1)
  }.toMap

  def caret(position: TextPosition): Option[TextCaret] = byPosition.get(position)
  def caretsAt(offset: Utf8Offset): Vector[TextCaret] = byOffset.getOrElse(offset, Vector.empty)
  def caretUtf16(offset: Utf16Offset, affinity: CaretAffinity): Option[TextCaret] =
    source.utf8Offset(offset).flatMap(o => caret(TextPosition(o, affinity)))

  def hit(at: Point): TextHit =
    // Half-open vertical line bands: a shared boundary selects the lower line.
    var lo = 0
    var hi = lineBoxes.size
    while lo < hi do
      val mid = lo + (hi - lo) / 2
      if lineBoxes(mid).y + lineBoxes(mid).height <= at.y then lo = mid + 1 else hi = mid
    val line = math.min(lo, lineBoxes.size - 1)
    val stops = byLine(line)
    lo = 0
    hi = stops.size
    while lo < hi do
      val mid = lo + (hi - lo) / 2
      if stops(mid)._1 < at.x then lo = mid + 1 else hi = mid
    val indices = Vector(lo - 1, lo).filter(i => i >= 0 && i < stops.size)
    val distance = indices.map(i => math.abs(stops(i)._1 - at.x)).min
    val nearest = indices.filter(i => math.abs(stops(i)._1 - at.x) == distance).flatMap(i => stops(i)._2)
      .sortBy(c => (if c.position.affinity == CaretAffinity.Downstream then 0 else 1, c.position.offset.value))
    TextHit(nearest.head, nearest.tail)

  /** Logical selection as a union of visual advance regions, possibly
    * discontiguous within a bidi line. Zero-width controls add no filled area.
    * Regions use full line height, include spaces and never use ink overhang.
    */
  def selection(selected: Utf8Range): Vector[TextSelectionRegion] =
    require(selected.start.value >= range.start.value && selected.end.value <= range.end.value &&
      analysis.isGraphemeBoundary(selected.start) && analysis.isGraphemeBoundary(selected.end),
      "selection endpoints must be grapheme boundaries within this layout")
    val cells = graphemes.iterator.filter(g => g.range.start.value >= selected.start.value &&
      g.range.end.value <= selected.end.value).map { g =>
      TextSelectionRegion(g.leading.lineIndex, g.bounds,
        g.leading.kind == CaretEdgeKind.Interpolated || g.trailing.kind == CaretEdgeKind.Interpolated)
    }.filter(_.bounds.width > 0).toVector.sortBy(r => (r.lineIndex, r.bounds.x))
    val merged = scala.collection.mutable.ArrayBuffer.empty[TextSelectionRegion]
    cells.foreach { cell =>
      if merged.nonEmpty && merged.last.lineIndex == cell.lineIndex &&
          cell.bounds.x <= merged.last.bounds.x + merged.last.bounds.width then
        val previous = merged.last
        val end = math.max(previous.bounds.x + previous.bounds.width, cell.bounds.x + cell.bounds.width)
        merged(merged.size - 1) = previous.copy(bounds = previous.bounds.copy(width = end - previous.bounds.x),
          interpolated = previous.interpolated || cell.interpolated)
      else merged += cell
    }
    merged.toVector

  def selectionUtf16(from: Utf16Offset, until: Utf16Offset): Vector[TextSelectionRegion] =
    val start = source.utf8Offset(from).getOrElse(throw new IllegalArgumentException("invalid UTF-16 start"))
    val end = source.utf8Offset(until).getOrElse(throw new IllegalArgumentException("invalid UTF-16 end"))
    selection(Utf8Range(start, end))

object TextGeometry:
  private case class GlyphPen(glyph: PositionedGlyph, index: Int, before: Double, after: Double)

  private[graphics] def fromLines(analysis: UnicodeAnalysis, lines: Vector[PlacedTextLine]): TextGeometry =
    require(lines.nonEmpty, "editing geometry needs at least one line")
    val indices = analysis.graphemeBoundaries.zipWithIndex.toMap
    val all = Vector.newBuilder[GraphemeGeometry]
    val emptyCarets = Vector.newBuilder[TextCaret]
    lines.zipWithIndex.foreach { (placedLine, lineIndex) =>
      val line = placedLine.layout
      def caret(offset: Utf8Offset, affinity: CaretAffinity, x: Double, kind: CaretEdgeKind): TextCaret =
        TextCaret(TextPosition(offset, affinity), lineIndex,
          Rect(placedLine.origin.x + x, placedLine.origin.y, 0, line.height), kind)
      if line.range.start == line.range.end then
        CaretAffinity.values.foreach(a => emptyCarets += caret(line.range.start, a, 0, CaretEdgeKind.ClusterEdge))
      line.runs.foreach { placed =>
        val run = placed.resolved.run
        val boundaries = analysis.graphemeBoundaries.slice(indices(run.range.start), indices(run.range.end) + 1)
        val boundarySet = boundaries.toSet
        var pen = 0.0
        val pens = run.glyphs.zipWithIndex.map { (glyph, index) =>
          require(glyph.advance.y == 0, "editing geometry supports horizontal glyph advances only")
          val before = pen
          pen += glyph.advance.x
          GlyphPen(glyph, index, before, pen)
        }
        // Intersection merges any HarfBuzz clusters that split one ICU grapheme.
        // It never creates a caret inside an extended grapheme or UTF encoding.
        val atoms = ((pens.map(_.glyph.cluster).filter(boundarySet) :+ run.range.start) :+ run.range.end)
          .distinct.sortBy(_.value)
        val groups = pens.groupBy(_.glyph.cluster).toVector.sortBy(_._1.value).iterator.buffered
        val rtl = run.options.direction == TextDirection.RightToLeft
        atoms.zip(atoms.drop(1)).foreach { (start, end) =>
          val glyphs = Vector.newBuilder[GlyphPen]
          while groups.hasNext && groups.head._1.value < end.value do glyphs ++= groups.next()._2
          val atomGlyphs = glyphs.result()
          val logicalStart = if atomGlyphs.isEmpty then (if rtl then run.advance.x else 0)
            else if rtl then atomGlyphs.maxBy(_.index).after else atomGlyphs.minBy(_.index).before
          val logicalEnd = if atomGlyphs.isEmpty then logicalStart
            else if rtl then atomGlyphs.minBy(_.index).before else atomGlyphs.maxBy(_.index).after
          val bs = analysis.graphemeBoundaries.slice(indices(start), indices(end) + 1)
          val count = bs.size - 1
          val fontCarets = if atomGlyphs.size == 1 && count > 1 &&
              sourceScalars(analysis.source, start, end) == count then
            val g = atomGlyphs.head.glyph
            g.ligatureCarets.map(_ + g.position.x).sorted
          else Vector.empty
          val usable = fontCarets.size == count - 1 && count > 1 &&
            fontCarets.headOption.exists(_ > math.min(logicalStart, logicalEnd)) &&
            fontCarets.lastOption.exists(_ < math.max(logicalStart, logicalEnd)) &&
            fontCarets.zip(fontCarets.drop(1)).forall((a, b) => a < b)
          val interior = if usable then (if rtl then fontCarets.reverse else fontCarets)
            else Vector.tabulate(count - 1)(i => logicalStart + (logicalEnd - logicalStart) * ((i + 1).toDouble / count))
          val xs = (Vector(logicalStart) ++ interior) :+ logicalEnd
          def kind(i: Int): CaretEdgeKind =
            if i == 0 || i == count then CaretEdgeKind.ClusterEdge
            else if usable then CaretEdgeKind.FontLigature else CaretEdgeKind.Interpolated
          (0 until count).foreach { i =>
            all += GraphemeGeometry(Utf8Range(bs(i), bs(i + 1)),
              caret(bs(i), CaretAffinity.Downstream, placed.origin.x + xs(i), kind(i)),
              caret(bs(i + 1), CaretAffinity.Upstream, placed.origin.x + xs(i + 1), kind(i + 1)))
          }
        }
      }
    }
    val graphemes = all.result().sortBy(_.range.start.value)
    val range = Utf8Range(lines.head.range.start, lines.last.range.end)
    val expected = analysis.graphemeBoundaries.slice(indices(range.start), indices(range.end) + 1)
    require(graphemes.map(_.range) == expected.zip(expected.drop(1)).map(Utf8Range.apply),
      "editing cells must partition the line source at grapheme boundaries")
    val edges = graphemes.flatMap(g => Vector(g.leading, g.trailing)) ++ emptyCarets.result()
    // At the outer source edges both affinities use the only adjacent edge.
    val aliases = if graphemes.isEmpty then Vector.empty else Vector(
      graphemes.head.leading.copy(position = TextPosition(range.start, CaretAffinity.Upstream)),
      graphemes.last.trailing.copy(position = TextPosition(range.end, CaretAffinity.Downstream)))
    val carets = (edges ++ aliases)
      .sortBy(c => (c.position.offset.value, c.position.affinity.ordinal, c.lineIndex))
    new TextGeometry(analysis, range, graphemes, carets,
      lines.map(p => Rect(p.origin.x, p.origin.y, p.layout.width, p.layout.height)))

  private def sourceScalars(source: TextSource, start: Utf8Offset, end: Utf8Offset): Int =
    source.text.codePointCount(source.utf16Offset(start).get.value, source.utf16Offset(end).get.value)
