package sirin

/** Logical preceding/following grapheme, not visual left/right. At wraps and
  * bidi boundaries the same source offset can have distinct caret locations.
  */
enum TextAffinity:
  case Upstream, Downstream

final case class TextPosition(utf16Offset: Int, affinity: TextAffinity):
  require(utf16Offset >= 0, "text position must be nonnegative")

enum TextCaretKind:
  case ClusterEdge, FontLigature, Interpolated

/** Zero-width, full-line-height logical-pixel bar local to the layout. */
final case class TextCaret(position: TextPosition, lineIndex: Int, bounds: Rect, kind: TextCaretKind)
final case class TextHit(primary: TextCaret, alternatives: Vector[TextCaret])
final case class TextSelectionRegion(lineIndex: Int, bounds: Rect, interpolated: Boolean)

/** One resolved result for measurement, paint and accessible/editing geometry.
  * Queries use original UTF-16 grapheme boundaries. Invalid carets return None
  * (caretsAt returns empty); selections reject invalid/reversed endpoints.
  * Hits clamp to nearest stops, preserving ties and their affinity explicitly.
  * These values do not implement editor commands, navigation or IME state.
  */
trait TextLayout:
  def text: String
  def size: Size
  def inkBounds: Option[Rect]
  /** Glyph/feature coverage, independent of inferred caret interiors. */
  def isComplete: Boolean
  def hasOverflow: Boolean
  def caret(position: TextPosition): Option[TextCaret]
  final def caret(utf16Offset: Int, affinity: TextAffinity): Option[TextCaret] =
    if utf16Offset < 0 then None else caret(TextPosition(utf16Offset, affinity))
  def caretsAt(utf16Offset: Int): Vector[TextCaret]
  /** Enumerates original UTF-16 grapheme stops; retained implementations override efficiently. */
  def graphemeBoundaries: Vector[Int]=(0 to text.length).iterator.filter(o => caretsAt(o).nonEmpty).toVector
  def hit(at: Point): TextHit
  def selection(fromUtf16: Int, untilUtf16: Int): Vector[TextSelectionRegion]

trait TextServices:
  /** Finite nonnegative width wraps; +infinity requests an unwrapped result.
    * Implementations preserve the phase capability and native owner policies.
    */
  def layout(text: String, style: TextStyle, maxWidth: Lp)(using Committed): TextLayout
