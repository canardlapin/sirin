package sirin.text

import sirin as ui
import sirin.graphics as g

/** Pure UI adapter over one retained shared result. No native services, tree,
  * callbacks or second measurement/shaping engine enter this object.
  */
final class ResolvedTextLayout private (
    val resolved: g.ParagraphLayout | g.WrappedParagraphLayout
) extends ui.TextLayout:
  private def analysis: g.UnicodeAnalysis = resolved match
    case p: g.ParagraphLayout => p.analysis
    case p: g.WrappedParagraphLayout => p.analysis
  private def geometry: g.TextGeometry = resolved match
    case p: g.ParagraphLayout => p.geometry
    case p: g.WrappedParagraphLayout => p.geometry
  override val text: String = analysis.source.text
  override val size: ui.Size = resolved match
    case p: g.ParagraphLayout => ui.Size(ui.Lp(p.width), ui.Lp(p.height))
    case p: g.WrappedParagraphLayout => ui.Size(ui.Lp(p.width), ui.Lp(p.height))
  override val inkBounds: Option[ui.Rect] = (resolved match
    case p: g.ParagraphLayout => p.inkBounds
    case p: g.WrappedParagraphLayout => p.inkBounds).map(LayoutUnits.rect)
  override val isComplete: Boolean = resolved match
    case p: g.ParagraphLayout => p.isComplete
    case p: g.WrappedParagraphLayout => p.isComplete
  override val hasOverflow: Boolean = resolved match
    case _: g.ParagraphLayout => false
    case p: g.WrappedParagraphLayout => p.hasOverflow

  private def convert(caret: g.TextCaret): ui.TextCaret =
    val offset = analysis.source.utf16Offset(caret.position.offset).get
    ui.TextCaret(ui.TextPosition(offset.value, LayoutUnits.affinity(caret.position.affinity)),
      caret.lineIndex, LayoutUnits.rect(caret.bounds), LayoutUnits.kind(caret.kind))

  override def caret(position: ui.TextPosition): Option[ui.TextCaret] =
    geometry.caretUtf16(g.Utf16Offset(position.utf16Offset), LayoutUnits.affinity(position.affinity)).map(convert)
  override def caretsAt(utf16Offset: Int): Vector[ui.TextCaret] =
    if utf16Offset < 0 then Vector.empty
    else analysis.source.utf8Offset(g.Utf16Offset(utf16Offset))
      .map(offset => geometry.caretsAt(offset).map(convert)).getOrElse(Vector.empty)
  override def hit(at: ui.Point): ui.TextHit =
    val hit = geometry.hit(LayoutUnits.point(at))
    ui.TextHit(convert(hit.primary), hit.alternatives.map(convert))
  override def selection(fromUtf16: Int, untilUtf16: Int): Vector[ui.TextSelectionRegion] =
    geometry.selectionUtf16(g.Utf16Offset(fromUtf16), g.Utf16Offset(untilUtf16))
      .map(r => ui.TextSelectionRegion(r.lineIndex, LayoutUnits.rect(r.bounds), r.interpolated))

  /** Canvas.text implementations can delegate here. Glyph IDs, face identity,
    * origins and painter order come directly from the retained shared result.
    */
  def record(canvas: g.Canvas2D, at: ui.Point, color: ui.Color): Unit =
    val origin = LayoutUnits.point(at)
    val paint = g.Paint.Solid(LayoutUnits.color(color))
    resolved match
      case p: g.ParagraphLayout => p.record(canvas, origin, paint)
      case p: g.WrappedParagraphLayout => p.record(canvas, origin, paint)

object ResolvedTextLayout:
  def apply(paragraph: g.ParagraphLayout): ResolvedTextLayout = new ResolvedTextLayout(paragraph)
  def apply(paragraph: g.WrappedParagraphLayout): ResolvedTextLayout = new ResolvedTextLayout(paragraph)
