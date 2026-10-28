package sirin.graphics.text

import sirin as ui
import sirin.graphics as g
import sirin.drawing.{PreparedText, RecordingCanvas, TextRequest, TextPreparationLimits, TextNotPrepared}
import sirin.graphics.unicode.NativeUnicode
import java.nio.file.{Files, Path as FilePath}

class CanvasBridgeSuite extends munit.FunSuite:
  private given ui.Committed = new ui.Committed {}
  private val ink = ui.Color.rgba(17, 83, 241, 193)
  private val theme = new ui.Theme:
    def color(role: ui.ColorRole): ui.Color = if role == ui.ColorRole.Selection then ui.Color.rgba(40, 80, 100, 64) else ink
    def reducedMotion: Boolean = true
  private def point(x: Double, y: Double): ui.Point = ui.Point(ui.Lp(x), ui.Lp(y))
  private def services[A](body: (Vector[NativeFont], NativeUnicode) => A): A =
    val unicode = NativeUnicode.open()
    val roots = Vector(FilePath.of("graphics/src/test/resources/fonts"), FilePath.of("../../graphics/src/test/resources/fonts"))
    val faces = Vector("NotoSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf", "NotoSansHebrew-Regular.ttf").map { name =>
      val path = roots.map(_.resolve(name)).find(Files.exists(_)).getOrElse(fail(s"missing pinned font $name"))
      NativeFont.open(g.FontData.copy(Files.readAllBytes(path)), 24)
    }
    try body(faces, unicode)
    finally
      faces.foreach(_.close())
      unicode.close()
  private def native(faces: Vector[NativeFont], unicode: NativeUnicode): NativeTextServices =
    new NativeTextServices(Map(ui.TextStyle.Body -> NativeTextStyle(faces), ui.TextStyle.Label -> NativeTextStyle(faces)), unicode)

  test("preparation deduplicates real requests; paint and lookup survive native closure without calling the preparer") {
    var calls = 0
    val a = TextRequest("office אבג", ui.TextStyle.Label)
    val b = TextRequest("سلام office", ui.TextStyle.Body, ui.Lp(80))
    val prepared = services { (faces, unicode) =>
      val actual = native(faces, unicode)
      val counted = new ui.TextServices:
        def layout(text: String, style: ui.TextStyle, width: ui.Lp)(using ui.Committed): ui.TextLayout =
          calls += 1
          actual.layout(text, style, width)
      PreparedText.prepare(Vector(a, b, a), counted)
    }
    assertEquals(calls, 2)
    assertEquals(prepared.requests, Vector(a, b))
    val drawing = RecordingCanvas.record(theme, prepared) { c =>
      c.withOffset(point(10.25, -0.125)) { c.withClip(ui.Rect(point(0, 0), ui.Size(ui.Lp(200), ui.Lp(300)))) {
        c.withOpacity(0.5) {
          c.text(a.content, point(0, 0), a.style, ink)
          val wrapped = c.textServices.layout(b.content, b.style, b.maxWidth)
          assert(wrapped eq prepared.get(b).get)
          c.text(wrapped, point(0, 50), ink)
        }
      }}
    }
    assertEquals(calls, 2)
    val emitted = drawing.commands.collect { case g.DrawCommand.Glyphs(run, _, paint) =>
      assertEquals(paint, g.Paint.Solid(g.Color.rgba8(17, 83, 241, 193)))
      run
    }
    val expected = Vector(a, b).flatMap { request => prepared.get(request).get.resolved match
      case p: g.ParagraphLayout => p.runs.map(_.resolved.run)
      case p: g.WrappedParagraphLayout => p.lines.flatMap(_.layout.runs.map(_.resolved.run))
    }
    assertEquals(emitted.size, expected.size)
    assert(emitted.zip(expected).forall((x, y) => x eq y))
    assertEquals(g.DrawingResources.inspect(drawing).toOption.get.fonts.toSet, expected.map(_.font.data).toSet)
  }

  test("a public Element measures, highlights and paints the same resolved multilingual text") {
    val request = TextRequest("abc אבג xyz", ui.TextStyle.Label)
    val prepared = services((faces, unicode) => PreparedText.prepare(Vector(request), native(faces, unicode)))
    val label = prepared.get(request).get
    val element = new ui.Element[ui.TextLayout, ui.TextLayout, Unit, Unit, Unit]:
      override def measure(state: ui.TextLayout, constraints: ui.Constraints)(using ui.Committed): ui.Size =
        ui.Size(state.size.width.min(constraints.maxWidth), state.size.height.min(constraints.maxHeight))
      def paint(state: ui.TextLayout, presentation: Unit, size: ui.Size, c: ui.Canvas)(using ui.Committed): Unit =
        c.fill(ui.Rect(point(0, 0), size), c.theme.color(ui.ColorRole.Surface), ui.Lp(5))
        c.withClip(ui.Rect(point(0, 0), size)) {
          state.selection(0, 5).foreach(region => c.fill(region.bounds, c.theme.color(ui.ColorRole.Selection)))
          c.text(state, point(0, 0), c.theme.color(ui.ColorRole.OnSurface))
        }
    val size = element.measure(label, ui.Constraints(ui.Lp.zero, ui.Lp(300), ui.Lp.zero, ui.Lp(100)))
    assertEquals(size, label.size)
    val drawing = RecordingCanvas.record(theme, prepared)(element.paint(label, (), size, _))
    assertEquals(drawing.commands.count { case g.DrawCommand.Fill(_, _, _) => true; case _ => false }, 3)
    val glyphs = drawing.commands.collect { case g.DrawCommand.Glyphs(run, _, _) => run }
    val original = label.resolved.asInstanceOf[g.ParagraphLayout]
    assertEquals(glyphs.size, original.runs.size)
    assert(glyphs.zip(original.runs.map(_.resolved.run)).forall((x, y) => x eq y))
    assert(label.caret(4, ui.TextAffinity.Upstream).get.bounds != label.caret(4, ui.TextAffinity.Downstream).get.bounds)
  }

  test("resolved association validates source, width and conflicting aliases") {
    services { (faces, unicode) =>
      val service = native(faces, unicode)
      val a = TextRequest("office", ui.TextStyle.Label)
      val unwrapped = service.layout(a.content, a.style, a.maxWidth)
      val other = service.layout("other", a.style, a.maxWidth)
      assert(PreparedText.fromResolved(Vector(a -> unwrapped, a -> unwrapped)).get(a).get eq unwrapped)
      intercept[IllegalArgumentException](PreparedText.fromResolved(Vector(a -> other)))
      intercept[IllegalArgumentException](PreparedText.fromResolved(Vector(a -> unwrapped, a -> other)))
      intercept[IllegalArgumentException](PreparedText.fromResolved(Vector(a.copy(maxWidth = ui.Lp(100)) -> unwrapped)))
      val wrapped = service.layout(a.content, a.style, ui.Lp(40))
      intercept[IllegalArgumentException](PreparedText.fromResolved(Vector(a.copy(maxWidth = ui.Lp(50)) -> wrapped)))
      assert(PreparedText.fromResolved(Vector(a.copy(maxWidth = ui.Lp(40)) -> wrapped)).get(a.copy(maxWidth = ui.Lp(40))).nonEmpty)
    }
  }

  test("input budgets reject before service calls; final glyph/font budgets include all retained requested faces") {
    services { (faces, unicode) =>
      var calls = 0
      val actual = native(faces, unicode)
      val counted = new ui.TextServices:
        def layout(text: String, style: ui.TextStyle, width: ui.Lp)(using ui.Committed): ui.TextLayout =
          calls += 1
          actual.layout(text, style, width)
      val request = TextRequest("office", ui.TextStyle.Label)
      intercept[IllegalArgumentException](PreparedText.prepare(Vector(request, request), counted, TextPreparationLimits(maxRequests = 1)))
      intercept[IllegalArgumentException](PreparedText.prepare(Vector(request), counted, TextPreparationLimits(maxUtf16Units = 1)))
      assertEquals(calls, 0)
      intercept[IllegalArgumentException](PreparedText.prepare(Vector(request), counted, TextPreparationLimits(maxGlyphs = 1)))
      assertEquals(calls, 1)
      val layout = actual.layout(request.content, request.style, request.maxWidth)
      // Only Latin paints, but the result retains all requested fallback fonts.
      intercept[IllegalArgumentException](PreparedText.fromResolved(Vector(request -> layout),
        TextPreparationLimits(maxFontBytes = faces.head.font.data.byteSize)))
      val total = faces.map(_.font.data.byteSize.toLong).sum
      val otherKey = request.copy(style = ui.TextStyle.Body)
      assertEquals(PreparedText.fromResolved(Vector(request -> layout, otherKey -> layout),
        TextPreparationLimits(maxFontBytes = total)).requests.size, 2)
    }
  }

  test("missing paint-time requests and invalid text draws roll back scopes without fallback") {
    val key = TextRequest("office", ui.TextStyle.Label)
    val prepared = services((faces, unicode) => PreparedText.prepare(Vector(key), native(faces, unicode)))
    val drawing = RecordingCanvas.record(theme, prepared) { c =>
      intercept[TextNotPrepared](c.withOpacity(0.5) {
        c.text(key.content, point(0, 0), key.style, ink)
        c.textServices.layout(key.content, key.style, ui.Lp(100))
        ()
      })
      intercept[IllegalArgumentException](c.text(prepared.get(key).get, point(Double.NaN, 0), ink))
      c.text(prepared.get(key).get, point(5.25, 6.5), ink)
    }
    assertEquals(drawing.commands.head, g.DrawCommand.BeginTransform(g.Affine.identity))
    assertEquals(drawing.commands.last, g.DrawCommand.EndTransform)
    val glyphs = drawing.commands.collect { case g.DrawCommand.Glyphs(run, origin, _) => (run, origin) }
    assertEquals(glyphs.size, 1)
    assertEquals(glyphs.head._2, g.Point(5.25, 6.5 + prepared.get(key).get.resolved.asInstanceOf[g.ParagraphLayout].baseline))
  }
