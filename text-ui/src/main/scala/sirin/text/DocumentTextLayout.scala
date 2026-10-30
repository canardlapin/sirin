package sirin.text

import sirin as ui
import sirin.graphics as g

/** Hard-line document geometry composed from exact retained paragraph results.
  * Separators stay in source offsets (CRLF is one grapheme); no glyph is invented
  * for a line break. Recording and every query share these same placements.
  */
final class DocumentTextLayout private (val text: String,val parts: Vector[DocumentTextLayout.Part]) extends ui.TextLayout:
  private val stops=parts.flatMap(p => p.layout.graphemeBoundaries.map(_+p.start)).distinct.sorted
  override def graphemeBoundaries: Vector[Int]=stops
  val size: ui.Size=ui.Size(ui.Lp(parts.map(_.layout.size.width.toDouble).max),ui.Lp(parts.last.y+parts.last.layout.size.height.toDouble))
  val isComplete: Boolean=parts.forall(_.layout.isComplete)
  val hasOverflow: Boolean=parts.exists(_.layout.hasOverflow)
  val inkBounds: Option[ui.Rect]=
    val boxes=parts.flatMap(p => p.layout.inkBounds.map(r => ui.Rect(ui.Point(r.origin.x,ui.Lp(r.origin.y.toDouble+p.y)),r.size)))
    if boxes.isEmpty then None else
      val x=boxes.map(_.origin.x.toDouble).min;val y=boxes.map(_.origin.y.toDouble).min
      Some(ui.Rect(ui.Point(ui.Lp(x),ui.Lp(y)),ui.Size(ui.Lp(boxes.map(r => r.origin.x.toDouble+r.size.width.toDouble).max-x),ui.Lp(boxes.map(r => r.origin.y.toDouble+r.size.height.toDouble).max-y))))
  private def convert(p: DocumentTextLayout.Part,c: ui.TextCaret): ui.TextCaret = c.copy(
    position=c.position.copy(utf16Offset=c.position.utf16Offset+p.start),lineIndex=c.lineIndex+p.line,
    bounds=c.bounds.copy(origin=ui.Point(c.bounds.origin.x,ui.Lp(c.bounds.origin.y.toDouble+p.y))))
  def caret(position: ui.TextPosition): Option[ui.TextCaret] = parts.find(p => position.utf16Offset>=p.start && position.utf16Offset<=p.end)
    .flatMap(p => p.layout.caret(position.copy(utf16Offset=position.utf16Offset-p.start)).map(convert(p,_)))
  def caretsAt(offset: Int): Vector[ui.TextCaret]=parts.filter(p => offset>=p.start && offset<=p.end).flatMap(p => p.layout.caretsAt(offset-p.start).map(convert(p,_)))
  def hit(at: ui.Point): ui.TextHit =
    val p=parts.find(p => at.y.toDouble<p.y+p.layout.size.height.toDouble).getOrElse(parts.last)
    val result=p.layout.hit(ui.Point(at.x,ui.Lp(at.y.toDouble-p.y)))
    ui.TextHit(convert(p,result.primary),result.alternatives.map(convert(p,_)))
  def selection(from: Int,until: Int): Vector[ui.TextSelectionRegion] =
    require(from<=until && stops.contains(from) && stops.contains(until),"document selection must use grapheme boundaries")
    parts.flatMap { p =>
      val a=math.max(from,p.start);val b=math.min(until,p.end)
      val textRegions=if a<b then p.layout.selection(a-p.start,b-p.start).map(r => r.copy(lineIndex=r.lineIndex+p.line,
        bounds=r.bounds.copy(origin=ui.Point(r.bounds.origin.x,ui.Lp(r.bounds.origin.y.toDouble+p.y))))) else Vector.empty
      val separator=if p.separatorEnd>p.end && from<=p.end && until>=p.separatorEnd then
        val c=p.layout.caretsAt(p.layout.text.length).head
        Vector(ui.TextSelectionRegion(c.lineIndex+p.line,ui.Rect(ui.Point(c.bounds.origin.x,ui.Lp(c.bounds.origin.y.toDouble+p.y)),ui.Size(ui.Lp(1),c.bounds.size.height)),false))
      else Vector.empty
      textRegions++separator
    }
  def record(canvas: g.Canvas2D,at: ui.Point,color: ui.Color): Unit = parts.foreach(p => p.layout.record(canvas,ui.Point(at.x,ui.Lp(at.y.toDouble+p.y)),color))

object DocumentTextLayout:
  final case class Part(start: Int,end: Int,separatorEnd: Int,y: Double,line: Int,layout: ResolvedTextLayout)
  def hasBreaks(text: String): Boolean=text.exists(c => "\n\r\u000B\u000C\u0085\u2028\u2029".contains(c))
  /** The paragraph sources `apply` resolves, in order, under the same budget. */
  def paragraphs(text: String): Vector[String] =
    val result=Vector.newBuilder[String]
    var start=0;var i=0;var count=0
    def append(end: Int,separatorEnd: Int): Unit =
      count+=1;require(count<=256,"document paragraph budget")
      result+=text.substring(start,end);start=separatorEnd
    while i<text.length do
      val c=text.charAt(i)
      if "\n\r\u000B\u000C\u0085\u2028\u2029".contains(c) then
        val next=if c=='\r' && i+1<text.length && text.charAt(i+1)=='\n' then i+2 else i+1
        append(i,next);i=next
      else i+=1
    append(text.length,text.length)
    result.result()
  def apply(text: String)(resolve: String => ResolvedTextLayout): DocumentTextLayout =
    val parts=Vector.newBuilder[Part]
    var start=0;var i=0;var y=0.0;var line=0;var count=0
    def append(end: Int,separatorEnd: Int): Unit =
      count+=1;require(count<=256,"document paragraph budget")
      val layout=resolve(text.substring(start,end))
      parts+=Part(start,end,separatorEnd,y,line,layout)
      y+=layout.size.height.toDouble
      line+=layout.graphemeBoundaries.flatMap(layout.caretsAt).map(_.lineIndex).maxOption.getOrElse(0)+1
      start=separatorEnd
    while i<text.length do
      val c=text.charAt(i)
      if "\n\r\u000B\u000C\u0085\u2028\u2029".contains(c) then
        val next=if c=='\r' && i+1<text.length && text.charAt(i+1)=='\n' then i+2 else i+1
        append(i,next);i=next
      else i+=1
    append(text.length,text.length)
    new DocumentTextLayout(text,parts.result())
