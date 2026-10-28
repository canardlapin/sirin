package sirin.graphics

opaque type GraphemeIndex = Int
object GraphemeIndex:
  def apply(value: Int): GraphemeIndex =
    require(value >= 0, "grapheme index must be nonnegative")
    value
  extension (index: GraphemeIndex) def value: Int = index

final case class Utf8Range(start: Utf8Offset, end: Utf8Offset):
  require(end.value >= start.value, "text range must be ordered")

enum ParagraphDirection:
  case Auto, LeftToRight, RightToLeft

enum BidiDirectionClass:
  case LeftToRight, RightToLeft, Mixed

enum WordKind:
  case NonWord, Number, Letter

final case class WordSegment(range: Utf8Range, kind: WordKind)
final case class BidiParagraph(range: Utf8Range, direction: BidiDirectionClass)

/** Owned Unicode analysis, not positioned text. Offsets are in original UTF-8;
  * bidi levels are raw resolved levels per byte, before line-dependent L1/L2.
  * Segmentation vectors include structural 0/end sentinels (one 0 for empty text).
  * In particular line boundary 0 is not a usable wrapping opportunity.
  */
final class UnicodeAnalysis private[graphics] (
    val source: TextSource, val graphemeBoundaries: Vector[Utf8Offset],
    val wordBoundaries: Vector[Utf8Offset], val words: Vector[WordSegment],
    val lineBoundaries: Vector[Utf8Offset], val bidiLevels: Vector[Int],
    val paragraphs: Vector[BidiParagraph], val requestedDirection: ParagraphDirection,
    val engine: String, val unicodeVersion: String
):
  private def validBoundaries(values: Vector[Utf8Offset]): Boolean =
    values.nonEmpty && values.head.value == 0 && values.last.value == source.byteSize &&
      values.forall(v => source.utf16Offset(v).nonEmpty) &&
      values.iterator.zip(values.iterator.drop(1)).forall((a, b) => a.value < b.value)
  require(validBoundaries(graphemeBoundaries) && validBoundaries(wordBoundaries) &&
    validBoundaries(lineBoundaries), "invalid Unicode segmentation boundaries")
  require(words.map(_.range) == wordBoundaries.zip(wordBoundaries.drop(1)).map(Utf8Range.apply),
    "word classification does not cover word intervals")
  require(bidiLevels.size == source.byteSize && bidiLevels.forall(v => v >= 0 && v <= 126),
    "invalid byte-indexed bidi levels")
  private val paragraphEnds = Vector(0) ++ paragraphs.map(_.range.end.value)
  require(paragraphs.indices.forall { i =>
    val r = paragraphs(i).range
    r.start.value == paragraphEnds(i) && r.end.value > r.start.value &&
      source.utf16Offset(r.start).nonEmpty && source.utf16Offset(r.end).nonEmpty
  } && paragraphEnds.last == source.byteSize, "bidi paragraphs do not partition the source")

  val graphemeCount: Int = graphemeBoundaries.size - 1
  val graphemeUtf16Boundaries: Vector[Utf16Offset] = graphemeBoundaries.map(source.utf16Offset(_).get)

  def graphemeRange(index: GraphemeIndex): Option[Utf8Range] =
    if index.value >= graphemeCount then None
    else Some(Utf8Range(graphemeBoundaries(index.value), graphemeBoundaries(index.value + 1)))

  /** End-of-text is a valid boundary, but continuation bytes/cluster interiors
    * are not promoted to editing positions. No caret coordinates are invented.
    */
  def isGraphemeBoundary(offset: Utf8Offset): Boolean =
    // Binary search avoids a per-query scan or an extra large hash table.
    var low = 0
    var high = graphemeBoundaries.size - 1
    while low <= high do
      val mid = low + (high - low) / 2
      val value = graphemeBoundaries(mid).value
      if value == offset.value then return true
      if value < offset.value then low = mid + 1 else high = mid - 1
    false
