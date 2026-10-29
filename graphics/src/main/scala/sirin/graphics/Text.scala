package sirin.graphics

import java.nio.charset.StandardCharsets

opaque type Utf8Offset = Int
object Utf8Offset:
  def apply(value: Int): Utf8Offset =
    require(value >= 0, "UTF-8 offset must be nonnegative")
    value
  extension (offset: Utf8Offset) def value: Int = offset

opaque type Utf16Offset = Int
object Utf16Offset:
  def apply(value: Int): Utf16Offset =
    require(value >= 0, "UTF-16 offset must be nonnegative")
    value
  extension (offset: Utf16Offset) def value: Int = offset

/** Well-formed Unicode source with codepoint-boundary offset conversion.
  * These boundaries are not grapheme boundaries, carets or selection geometry.
  */
final class TextSource private (
    val text: String, private val bytes: Array[Byte], private val boundaries: Map[Int, Int]
):
  def byteSize: Int = bytes.length
  def utf8Copy: Array[Byte] = bytes.clone()
  def utf16Offset(offset: Utf8Offset): Option[Utf16Offset] = boundaries.get(offset.value).map(Utf16Offset(_))
  private lazy val utf8Boundaries: Map[Int, Int] = boundaries.map((a, b) => b -> a)
  def utf8Offset(offset: Utf16Offset): Option[Utf8Offset] = utf8Boundaries.get(offset.value).map(Utf8Offset(_))

object TextSource:
  def apply(text: String): TextSource =
    val boundaries = Map.newBuilder[Int, Int]
    var utf16 = 0
    var utf8 = 0L
    while utf16 < text.length do
      boundaries += utf8.toInt -> utf16
      val c = text.charAt(utf16)
      if Character.isHighSurrogate(c) then
        require(utf16 + 1 < text.length && Character.isLowSurrogate(text.charAt(utf16 + 1)),
          "unpaired high surrogate")
        utf16 += 2
        utf8 += 4
      else
        require(!Character.isLowSurrogate(c), "unpaired low surrogate")
        utf16 += 1
        utf8 += (if c < 0x80 then 1 else if c < 0x800 then 2 else 3)
      require(utf8 <= Int.MaxValue, "UTF-8 source exceeds supported array size")
    boundaries += utf8.toInt -> utf16
    new TextSource(text, text.getBytes(StandardCharsets.UTF_8), boundaries.result())

/** Owned font-file bytes. Copying does not validate the font format; a native
  * service must successfully open the bytes before producing a ResolvedFont.
  */
final class FontData private (private val bytes: Array[Byte]):
  def byteSize: Int = bytes.length
  def bytesCopy: Array[Byte] = bytes.clone()

object FontData:
  def copy(bytes: Array[Byte]): FontData =
    require(bytes.nonEmpty, "font bytes must not be empty")
    new FontData(bytes.clone())

enum TextDirection:
  case LeftToRight, RightToLeft

/** Canonical ISO 15924 tag; Unicode services supply the actual script values. */
final case class Script(tag: String):
  require(tag.length == 4 && tag.head >= 'A' && tag.head <= 'Z' &&
    tag.tail.forall(c => c >= 'a' && c <= 'z'), "script must be a canonical four-letter ISO 15924 tag")

object Script:
  val Latin = Script("Latn")
  val Greek = Script("Grek")
  val Arabic = Script("Arab")
  val Hebrew = Script("Hebr")
  val Common = Script("Zyyy")
  val Inherited = Script("Zinh")
  val Unknown = Script("Zzzz")

final case class ShapingOptions(
    script: Script, direction: TextDirection, language: String, standardLigatures: Boolean = true
):
  require(language.nonEmpty && language.length <= 64 && language.forall(c =>
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'),
    "language must be a nonempty ASCII language tag")

final case class FontMetrics(ascent: Double, descent: Double, lineGap: Double):
  require(ascent.isFinite && ascent >= 0 && descent.isFinite && descent >= 0 && lineGap.isFinite,
    "invalid font metrics")

/** Exact static face and logical size opened by the native font service.
  * Retains font bytes, not a native pointer or an OS family lookup.
  */
final class ResolvedFont private[graphics] (
    val data: FontData, val faceIndex: Int, val size: Double, val glyphCount: Int,
    val unitsPerEm: Int, val metrics: FontMetrics, val shaperVersion: String, val rasterizerVersion: String
):
  require(faceIndex >= 0 && size.isFinite && size > 0 && glyphCount > 0 && unitsPerEm > 0,
    "invalid resolved font metadata")

/** Glyph position and ink are y-down, relative to the run's baseline origin.
  * Ink bounds here are relative to the glyph origin at position.
  * Ligature carets are bounded GDEF x coordinates relative to that same glyph
  * origin. They are not source editing stops until combined with grapheme and
  * cluster mappings by TextGeometry. Empty also covers unusable/absent data.
  */
final case class PositionedGlyph(
    id: Int, cluster: Utf8Offset, position: Point, advance: Point, ink: Option[Rect],
    ligatureCarets: Vector[Double] = Vector.empty
):
  require(id >= 0, "glyph ID must be nonnegative")
  require(ligatureCarets.size <= 64 && ligatureCarets.forall(_.isFinite), "invalid ligature carets")

/** One explicit horizontal script/direction run. Not a paragraph layout or bidi
  * itemizer. Painting retains these positions; it does not reshape the source.
  */
final class ShapedRun private[graphics] (
    val source: TextSource, val font: ResolvedFont, val options: ShapingOptions,
    val glyphs: Vector[PositionedGlyph], val range: Utf8Range, val context: Utf8Range
):
  require(context.start.value <= range.start.value && context.end.value >= range.end.value &&
    source.utf16Offset(context.start).nonEmpty && source.utf16Offset(context.end).nonEmpty,
    "shaping context must contain the item and use source codepoint boundaries")
  require(source.utf16Offset(range.start).nonEmpty && source.utf16Offset(range.end).nonEmpty,
    "shaped range must use source codepoint boundaries")
  require(glyphs.forall(g => g.id < font.glyphCount && g.cluster.value >= range.start.value && g.cluster.value < range.end.value &&
    source.utf16Offset(g.cluster).nonEmpty), "glyph IDs/clusters outside resolved font/source")
  val hasMissingGlyphs: Boolean = glyphs.exists(_.id == 0)
  val advance: Point = glyphs.foldLeft(Point(0, 0))((p, g) => Point(p.x + g.advance.x, p.y + g.advance.y))
  val inkBounds: Option[Rect] = glyphs.flatMap { g =>
    g.ink.map(r => Rect(r.x + g.position.x, r.y + g.position.y, r.width, r.height))
  }.reduceOption { (a, b) =>
    val x = math.min(a.x, b.x)
    val y = math.min(a.y, b.y)
    Rect(x, y, math.max(a.x + a.width, b.x + b.width) - x, math.max(a.y + a.height, b.y + b.height) - y)
  }

/** Owned grayscale coverage from FreeType. No atlas, texture or foreign pointer.
  * Pixel top-left is (left, -top) relative to the device-space glyph origin.
  */
final class GlyphRaster private[graphics] (
    val font: ResolvedFont, val glyphId: Int, val deviceScale: Double, val subpixelX: Double,
    val width: Int, val height: Int, val left: Int, val top: Int, private val pixels: Array[Byte]
):
  require(width >= 0 && height >= 0 && width.toLong * height == pixels.length, "invalid glyph raster extent")
  def byteSize: Int = pixels.length
  def coverageCopy: Array[Byte] = pixels.clone()
