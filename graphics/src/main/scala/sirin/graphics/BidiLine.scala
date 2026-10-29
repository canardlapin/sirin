package sirin.graphics

/** A source codepoint after line-dependent UAX #9 L1. None means removed by
  * X9, not missing text: its original source range remains in logical order.
  */
final case class BidiCodepoint(range: Utf8Range, level: Option[Int]):
  require(level.forall(v => v >= 0 && v <= 126), "invalid resolved bidi level")

/** Source-preserving L1/L2 result for an explicitly chosen line in one paragraph.
  * Visual order lists UTF-8 codepoint starts, excluding X9 removals. It is not
  * glyph order or an instruction to reverse text before shaping. L3 combining
  * behavior and L4 mirroring remain the responsibility of the shaping pipeline.
  * X9 omissions do not delete joiners/controls from shaping context or source.
  */
final class BidiLine private[graphics] (
    val analysis: UnicodeAnalysis, val paragraphIndex: Int, val range: Utf8Range,
    val paragraphLevel: Int, val codepoints: Vector[BidiCodepoint],
    val visualOrder: Vector[Utf8Offset]
):
  require(paragraphIndex >= 0 && paragraphIndex < analysis.paragraphs.size, "invalid bidi paragraph index")
  private val paragraphRange = analysis.paragraphs(paragraphIndex).range
  require(range.start.value >= paragraphRange.start.value && range.end.value <= paragraphRange.end.value,
    "bidi line must be contained in its paragraph")
  require(paragraphLevel == 0 || paragraphLevel == 1, "invalid paragraph base level")
  private val source = analysis.source
  require(source.utf16Offset(range.start).nonEmpty && source.utf16Offset(range.end).nonEmpty,
    "bidi line must use codepoint boundaries")
  private val ends = Vector(range.start.value) ++ codepoints.map(_.range.end.value)
  require(codepoints.indices.forall { i =>
    val r = codepoints(i).range
    val start = source.utf16Offset(r.start)
    val end = source.utf16Offset(r.end)
    r.start.value == ends(i) && r.end.value > r.start.value && end.nonEmpty && start.nonEmpty &&
      end.get.value == start.get.value + Character.charCount(source.text.codePointAt(start.get.value))
  } && ends.last == range.end.value, "bidi codepoints must exactly partition the line")
  require(visualOrder.sortBy(_.value) == codepoints.filter(_.level.nonEmpty).map(_.range.start),
    "bidi visual order must permute the non-X9 source codepoints")
