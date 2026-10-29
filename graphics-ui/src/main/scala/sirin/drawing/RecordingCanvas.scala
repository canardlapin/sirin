package sirin.drawing

import sirin as ui
import sirin.graphics as g
import sirin.text.{LayoutUnits, ResolvedTextLayout, DocumentTextLayout}

/** Synchronous core Canvas -> public graphics recording. By-name core scopes
  * share this lexical handle while their active graphics target is replaced.
  */
final class RecordingCanvas private (initialTarget: g.Canvas2D, initialTheme: ui.Theme, initialText: PreparedText) extends ui.Canvas:
  private case class State(target: g.Canvas2D, theme: ui.Theme, text: PreparedText)
  private val owner = Thread.currentThread()
  private var state: Option[State] = Some(State(initialTarget, initialTheme, initialText))
  private def current: State =
    require(Thread.currentThread() eq owner, "recording canvas used from a different thread")
    state.getOrElse(throw new IllegalArgumentException("recording canvas is closed"))
  private def close(): Unit = state = None
  private def within(target: g.Canvas2D)(body: => Unit): Unit =
    val previous = current
    state = Some(previous.copy(target = target))
    try body finally state = Some(previous)

  override def theme: ui.Theme = current.theme
  override def textServices: ui.TextServices = current.text
  override def fill(rect: ui.Rect, color: ui.Color, radius: ui.Lp): Unit =
    current.target.fill(g.Path.roundedRectangle(rectangle(rect), radius.toDouble), paint(color))
  override def stroke(rect: ui.Rect, color: ui.Color, width: ui.Lp, radius: ui.Lp): Unit =
    current.target.stroke(g.Path.roundedRectangle(rectangle(rect), radius.toDouble), paint(color),
      g.StrokeStyle(width = width.toDouble))
  override def line(from: ui.Point, to: ui.Point, color: ui.Color, width: ui.Lp): Unit =
    val target = current.target
    val a = point(from)
    val b = point(to)
    val path = g.Path.build { p => p.moveTo(a.x, a.y); p.lineTo(b.x, b.y) }
    target.stroke(path, paint(color), g.StrokeStyle(width = width.toDouble))
  override def text(layout: ui.TextLayout, at: ui.Point, color: ui.Color): Unit =
    val target = current.target
    // Both adapters record the same retained paragraphs used for their geometry.
    target.transformed(g.Affine.identity) { child => layout match
      case result: ResolvedTextLayout => result.record(child,at,color)
      case result: DocumentTextLayout => result.record(child,at,color)
      case _ => throw new IllegalArgumentException("recording requires a retained text layout")
    }
  override def text(content: String, at: ui.Point, style: ui.TextStyle, color: ui.Color): Unit =
    text(current.text.requireLayout(TextRequest(content, style)), at, color)
  override def withOpacity(alpha: Double)(body: => Unit): Unit =
    current.target.layer(alpha)(child => within(child)(body))
  override def withClip(rect: ui.Rect)(body: => Unit): Unit =
    current.target.clipped(g.Path.rectangle(rectangle(rect)))(child => within(child)(body))
  override def withOffset(by: ui.Point)(body: => Unit): Unit =
    val target = current.target
    val at = point(by)
    target.transformed(g.Affine.translation(at.x, at.y))(child => within(child)(body))

  private def point(value: ui.Point): g.Point = LayoutUnits.point(value)
  private def rectangle(value: ui.Rect): g.Rect = LayoutUnits.rect(value)
  private def paint(value: ui.Color): g.Paint = g.Paint.Solid(LayoutUnits.color(value))

object RecordingCanvas:
  def record(theme: ui.Theme, text: PreparedText = PreparedText.empty)(body: ui.Canvas => Unit): g.Drawing =
    g.Drawing.record { target =>
      val canvas = new RecordingCanvas(target, theme, text)
      try body(canvas) finally canvas.close()
    }
