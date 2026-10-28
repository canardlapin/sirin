package sirin.drawing

import sirin as ui
import sirin.graphics as g
import java.util.concurrent.atomic.AtomicReference

class RecordingCanvasSuite extends munit.FunSuite:
  private given ui.Committed = new ui.Committed {}
  private val ink = ui.Color.rgba(17, 83, 241, 193)
  private val theme = new ui.Theme:
    def color(role: ui.ColorRole): ui.Color = if role == ui.ColorRole.Accent then ink else ui.Color.rgba(0, 0, 0)
    def reducedMotion: Boolean = true
  private def point(x: Double, y: Double): ui.Point = ui.Point(ui.Lp(x), ui.Lp(y))
  private def rect(x: Double = 0, y: Double = 0, w: Double = 40, h: Double = 20): ui.Rect =
    ui.Rect(point(x, y), ui.Size(ui.Lp(w), ui.Lp(h)))
  private val paint = g.Paint.Solid(g.Color.rgba8(17, 83, 241, 193))

  test("public core primitives lower to exact logical paths, centered strokes and butt-capped lines") {
    val drawing = RecordingCanvas.record(theme) { c =>
      assert(c.theme eq theme)
      c.fill(rect(-0.25, 8.5), c.theme.color(ui.ColorRole.Accent))
      c.stroke(rect(), ink, ui.Lp(1.5), ui.Lp(100))
      c.line(point(1.25, 2.5), point(30.75, 4.5), ink, ui.Lp(0.75))
    }
    val commands = drawing.commands
    assertEquals(commands(0), g.DrawCommand.Fill(g.Path.rectangle(g.Rect(-0.25, 8.5, 40, 20)), paint, g.FillRule.NonZero))
    assertEquals(commands(1), g.DrawCommand.Stroke(g.Path.roundedRectangle(g.Rect(0, 0, 40, 20), 10), paint,
      g.StrokeStyle(width = 1.5)))
    val g.DrawCommand.Stroke(path, actualPaint, style) = commands(2): @unchecked
    assertEquals(path.segments, Vector(g.PathSegment.MoveTo(g.Point(1.25, 2.5)), g.PathSegment.LineTo(g.Point(30.75, 4.5))))
    assertEquals(actualPaint, paint)
    assertEquals(style.cap, g.LineCap.Butt)
    assertEquals(style.width, 0.75)
  }

  test("by-name scopes preserve painter order, clip establishment, local offsets and isolated opacity") {
    val drawing = RecordingCanvas.record(theme) { c =>
      c.withOffset(point(10, 20)) {
        c.withClip(rect(w = 30, h = 40)) {
          c.withOpacity(0.5) {
            c.withOffset(point(2, 3)) { c.fill(rect(w = 5, h = 6), ink) }
            c.fill(rect(w = 7, h = 8), ink)
          }
        }
      }
      c.fill(rect(w = 9, h = 10), ink)
    }
    assertEquals(drawing.commands, Vector(
      g.DrawCommand.BeginTransform(g.Affine.translation(10, 20)),
      g.DrawCommand.BeginClip(g.Path.rectangle(g.Rect(0, 0, 30, 40)), g.FillRule.NonZero),
      g.DrawCommand.BeginLayer(0.5), g.DrawCommand.BeginTransform(g.Affine.translation(2, 3)),
      g.DrawCommand.Fill(g.Path.rectangle(g.Rect(0, 0, 5, 6)), paint, g.FillRule.NonZero), g.DrawCommand.EndTransform,
      g.DrawCommand.Fill(g.Path.rectangle(g.Rect(0, 0, 7, 8)), paint, g.FillRule.NonZero), g.DrawCommand.EndLayer,
      g.DrawCommand.EndClip, g.DrawCommand.EndTransform,
      g.DrawCommand.Fill(g.Path.rectangle(g.Rect(0, 0, 9, 10)), paint, g.FillRule.NonZero)))
  }

  test("caught scope failures remove their commands and restore the enclosing target") {
    val boom = new IllegalStateException("scope failed")
    val drawing = RecordingCanvas.record(theme) { c =>
      intercept[IllegalStateException](c.withOffset(point(10, 20)) {
        c.withClip(rect()) { c.withOpacity(0.5) { c.fill(rect(), ink); throw boom } }
      })
      c.withOffset(point(3, 4)) {
        intercept[IllegalStateException](c.withOpacity(0.5) { c.fill(rect(), ink); throw boom })
        c.fill(rect(w = 8, h = 9), ink)
      }
      c.fill(rect(w = 1, h = 2), ink)
    }
    assertEquals(drawing.commands.size, 4)
    assertEquals(drawing.commands.head, g.DrawCommand.BeginTransform(g.Affine.translation(3, 4)))
    assertEquals(drawing.commands(2), g.DrawCommand.EndTransform)
    assert(drawing.commands.last.isInstanceOf[g.DrawCommand.Fill])
  }

  test("canvas handles expire on success and failure, including theme and text-service access") {
    var escaped: Option[ui.Canvas] = None
    val drawing = RecordingCanvas.record(theme) { c => escaped = Some(c); c.fill(rect(), ink) }
    assertEquals(drawing.commands.size, 1)
    val c = escaped.get
    intercept[IllegalArgumentException](c.fill(rect(), ink))
    intercept[IllegalArgumentException](c.theme)
    intercept[IllegalArgumentException](c.textServices)
    intercept[IllegalArgumentException](c.withOpacity(0.5)(()))
    intercept[IllegalStateException](RecordingCanvas.record(theme) { c => escaped = Some(c); throw new IllegalStateException("failed") })
    intercept[IllegalArgumentException](escaped.get.withOffset(point(1, 2))(()))
  }

  test("foreign Scala-thread access fails before recording and the owner can continue") {
    val error = new AtomicReference[Throwable]()
    val drawing = RecordingCanvas.record(theme) { c =>
      val thread = new Thread(() =>
        try c.fill(rect(), ink)
        catch case e: Throwable => error.set(e))
      thread.start()
      thread.join()
      assert(error.get().isInstanceOf[IllegalArgumentException])
      c.fill(rect(), ink)
    }
    assertEquals(drawing.commands.size, 1)
  }

  test("invalid inputs and unprepared text reject without producing partial recordings") {
    val drawing = RecordingCanvas.record(theme) { c =>
      for radius <- Vector(-1.0, Double.NaN, Double.PositiveInfinity) do
        intercept[IllegalArgumentException](c.fill(rect(), ink, ui.Lp(radius)))
      intercept[IllegalArgumentException](c.fill(rect(w = -1), ink))
      intercept[IllegalArgumentException](c.stroke(rect(), ink, ui.Lp.zero))
      intercept[IllegalArgumentException](c.line(point(Double.NaN, 0), point(1, 1), ink, ui.Lp(1)))
      intercept[IllegalArgumentException](c.withOpacity(1.1)(c.fill(rect(), ink)))
      intercept[IllegalArgumentException](c.withOffset(point(Double.PositiveInfinity, 0))(c.fill(rect(), ink)))
      val missing = intercept[TextNotPrepared](c.text("label", point(0, 0), ui.TextStyle.Label, ink))
      assertEquals(missing.request, TextRequest("label", ui.TextStyle.Label))
      intercept[TextNotPrepared](c.textServices.layout("label", ui.TextStyle.Label, ui.Lp(100)))
    }
    assertEquals(drawing.commands, Vector.empty)
    for width <- Vector(Double.NaN, -1.0, Double.NegativeInfinity) do
      intercept[IllegalArgumentException](TextRequest("x", ui.TextStyle.Body, ui.Lp(width)))
    intercept[IllegalArgumentException](TextPreparationLimits(maxRequests = 0))
  }
