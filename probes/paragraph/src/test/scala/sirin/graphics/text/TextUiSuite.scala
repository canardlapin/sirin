package sirin.graphics.text

import sirin as ui
import sirin.graphics as g
import sirin.text.ResolvedTextLayout
import sirin.graphics.unicode.NativeUnicode
import java.nio.file.{Files, Path as FilePath}
import java.util.concurrent.atomic.AtomicReference

class TextUiSuite extends munit.FunSuite:
  // The public Committed marker is supplied at the test call site. These tests
  // exercise real text services, not mounted phase dispatch or a UI scheduler.
  private given ui.Committed = new ui.Committed {}
  private val up = ui.TextAffinity.Upstream
  private val down = ui.TextAffinity.Downstream
  private val names = Vector("NotoSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf",
    "NotoSansHebrew-Regular.ttf", "NotoSansDevanagari-Regular.ttf")
  private def services[A](body: (Vector[NativeFont], NativeUnicode) => A): A =
    val unicode = NativeUnicode.open()
    val roots = Vector(FilePath.of("graphics/src/test/resources/fonts"), FilePath.of("../../graphics/src/test/resources/fonts"))
    val faces = names.map { name =>
      val path = roots.map(_.resolve(name)).find(Files.exists(_)).getOrElse(fail(s"missing pinned font $name"))
      NativeFont.open(g.FontData.copy(Files.readAllBytes(path)), 24)
    }
    try body(faces, unicode)
    finally
      faces.foreach(_.close())
      unicode.close()
  private def point(x: Double, y: Double): ui.Point = ui.Point(ui.Lp(x), ui.Lp(y))
  private def bar(x: Double, y: Double, height: Double): ui.Rect =
    ui.Rect(point(x, y), ui.Size(ui.Lp.zero, ui.Lp(height)))

  test("core consumer uses the exact unwrapped result and real GDEF carets after native closure") {
    val original = services((faces, unicode) => NativeParagraph.layout("office", faces, unicode))
    val adapted = ResolvedTextLayout(original)
    val layout: ui.TextLayout = adapted
    assert(adapted.resolved eq original)
    assertEquals(layout.text, "office")
    assertEquals(layout.size, ui.Size(ui.Lp(original.width), ui.Lp(original.height)))
    assert(layout.inkBounds.nonEmpty && layout.isComplete && !layout.hasOverflow)
    val ligature = original.logicalRuns.head.run.glyphs.find(_.cluster.value == 1).get
    val caret = layout.caret(2, down).get
    assertEquals(caret.position, ui.TextPosition(2, down))
    assertEquals(caret.kind, ui.TextCaretKind.FontLigature)
    assertEquals(caret.bounds, bar(ligature.position.x + 7.5625, 0, original.height))
    assertEquals(layout.caret(-1, down), None)
    assertEquals(layout.caretsAt(-1), Vector.empty)
    assertEquals(layout.caret(99, up), None)
    assertEquals(layout.selection(2, 3).head.bounds.size.width.toDouble, 15.140625 - 7.5625)
  }

  test("UI wrapping retains line affinity, line heights, overflow and paragraph-local coordinates") {
    services { (faces, unicode) =>
      val width = NativeParagraph.layout("office ", faces, unicode).width
      val original = NativeParagraph.wrap("office office office", faces, unicode, width)
      val layout: ui.TextLayout = ResolvedTextLayout(original)
      val before = layout.caret(7, up).get
      val after = layout.caret(7, down).get
      assertEquals(before.lineIndex, 0)
      assertEquals(after.lineIndex, 1)
      assertEquals(before.bounds, bar(width, 0, original.lines.head.layout.height))
      assertEquals(after.bounds, bar(0, original.lines(1).origin.y, original.lines(1).layout.height))
      assertEquals(layout.caretsAt(7).map(_.lineIndex).toSet, Set(0, 1))
      assertEquals(layout.hit(after.bounds.origin).primary.lineIndex, 1)
      assertEquals(layout.selection(0, layout.text.length).map(_.lineIndex), Vector(0, 1, 2))
      assert(!layout.hasOverflow)
      val overflow: ui.TextLayout = ResolvedTextLayout(NativeParagraph.wrap("office", faces, unicode, 0))
      assert(overflow.hasOverflow && overflow.size.width.toDouble > 0)
    }
  }

  test("multibyte, surrogate, combining and emoji source mappings stay grapheme safe in core UTF-16") {
    services { (faces, unicode) =>
      val text = "é😀x\u0301 אבג 👩‍👩‍👧‍👦"
      val original = NativeParagraph.layout(text, faces, unicode)
      val layout: ui.TextLayout = ResolvedTextLayout(original)
      assert(!layout.isComplete)
      for i <- 0 to text.length do
        val allowed = original.analysis.graphemeUtf16Boundaries.contains(g.Utf16Offset(i))
        assertEquals(layout.caretsAt(i).nonEmpty, allowed, s"UTF-16 $i")
        assertEquals(layout.caret(i, down).nonEmpty, allowed, s"UTF-16 $i")
      original.geometry.carets.foreach { caret =>
        val utf16 = original.source.utf16Offset(caret.position.offset).get.value
        val affinity = if caret.position.affinity == g.CaretAffinity.Upstream then up else down
        val converted = layout.caret(utf16, affinity).get
        assertEquals(converted.bounds, bar(caret.bounds.x, caret.bounds.y, caret.bounds.height))
        val hit = layout.hit(converted.bounds.origin)
        assert((hit.primary +: hit.alternatives).contains(converted))
      }
      assertEquals(layout.caret(2, down), None) // surrogate interior
      assertEquals(layout.caret(4, down), None) // combining interior
      intercept[IllegalArgumentException](layout.selection(0, 2))
      intercept[IllegalArgumentException](layout.selection(0, 4))
      intercept[IllegalArgumentException](layout.selection(-1, text.length))
      intercept[IllegalArgumentException](layout.selection(text.length, 0))
      intercept[IllegalArgumentException](layout.selection(0, text.length + 1))
    }
  }

  test("bidi positions and hit alternatives are not collapsed; selections preserve visual gaps") {
    services { (faces, unicode) =>
      val original = NativeParagraph.layout("abc אבג xyz", faces, unicode)
      val layout: ui.TextLayout = ResolvedTextLayout(original)
      assert(layout.caret(4, up).get.bounds.origin.x.toDouble < layout.caret(4, down).get.bounds.origin.x.toDouble)
      assertEquals(layout.selection(0, 5).size, 2)
      val midpoint = NativeParagraph.layout("W", faces, unicode)
      val single: ui.TextLayout = ResolvedTextLayout(midpoint)
      val hit = single.hit(point(midpoint.width / 2, midpoint.height / 2))
      assertEquals(hit.primary.position, ui.TextPosition(0, down))
      assertEquals(hit.alternatives.size, 3)
      assertEquals((hit.primary +: hit.alternatives).map(_.position).toSet,
        Set(ui.TextPosition(0, up), ui.TextPosition(0, down), ui.TextPosition(1, up), ui.TextPosition(1, down)))
      val joined: ui.TextLayout = ResolvedTextLayout(NativeParagraph.layout("of\u200Dfice", faces, unicode))
      assertEquals(joined.caret(3, down).get.kind, ui.TextCaretKind.Interpolated)
      assert(joined.selection(3, 4).head.interpolated)
    }
  }

  test("UI recording preserves run identity/order, fractional origins, ARGB alpha and font dependencies") {
    val original = services((faces, unicode) => NativeParagraph.wrap("office אבג سلام", faces, unicode, 100))
    val layout = ResolvedTextLayout(original)
    val at = point(12.25, -0.125)
    val color = ui.Color.rgba(17, 83, 241, 193)
    val drawing = g.Drawing.record(layout.record(_, at, color))
    val emitted = drawing.commands.collect { case g.DrawCommand.Glyphs(run, origin, paint) => (run, origin, paint) }
    val expected = original.lines.flatMap(line => line.layout.runs.map(run =>
      (run.resolved.run, g.Point(12.25 + line.origin.x + run.origin.x, -0.125 + line.origin.y + run.origin.y))))
    assertEquals(emitted.size, expected.size)
    emitted.zip(expected).foreach { (actual, reference) =>
      assert(actual._1 eq reference._1)
      assertEquals(actual._2, reference._2)
      assertEquals(actual._3, g.Paint.Solid(g.Color.rgba8(17, 83, 241, 193)))
    }
    assertEquals(g.DrawingResources.inspect(drawing).toOption.get.fonts.toSet,
      expected.map(_._1.font.data).toSet)
    intercept[IllegalArgumentException](g.Drawing.record(layout.record(_, point(Double.NaN, 0), color)))
  }

  test("real TextServices honors explicit styles, finite wrapping, unbounded measurement and feature options") {
    services { (faces, unicode) =>
      val service = new NativeTextServices(Map(
        ui.TextStyle.Body -> NativeTextStyle(faces),
        ui.TextStyle.Mono -> NativeTextStyle(faces, g.ParagraphOptions(standardLigatures = false))), unicode)
      val api: ui.TextServices = service
      val unwrapped = api.layout("office", ui.TextStyle.Body, ui.Lp(Double.PositiveInfinity))
      assert(unwrapped.isComplete && !unwrapped.hasOverflow)
      assertEquals(unwrapped.caret(2, down).get.kind, ui.TextCaretKind.FontLigature)
      assertEquals(api.layout("office", ui.TextStyle.Mono, ui.Lp(Double.PositiveInfinity)).caret(2, down).get.kind,
        ui.TextCaretKind.ClusterEdge)
      val width = NativeParagraph.layout("office ", faces, unicode).width
      val wrapped = service.layout("office office", ui.TextStyle.Body, ui.Lp(width))
      assert(wrapped.resolved.isInstanceOf[g.WrappedParagraphLayout])
      assertEquals(wrapped.caret(7, up).get.lineIndex, 0)
      assertEquals(wrapped.caret(7, down).get.lineIndex, 1)
      assert(service.layout("office", ui.TextStyle.Body, ui.Lp.zero).hasOverflow)
      intercept[IllegalArgumentException](api.layout("office", ui.TextStyle.Caption, ui.Lp(100)))
      for invalid <- Vector(Double.NaN, -1.0, Double.NegativeInfinity) do
        intercept[IllegalArgumentException](api.layout("office", ui.TextStyle.Body, ui.Lp(invalid)))
      intercept[IllegalArgumentException](new NativeTextServices(Map.empty, unicode))
      intercept[IllegalArgumentException](NativeTextStyle(Vector.empty))
    }
  }

  test("native TextServices keeps caller ownership, thread confinement and actual budget failures") {
    services { (faces, unicode) =>
      val service = new NativeTextServices(Map(ui.TextStyle.Body -> NativeTextStyle(faces)), unicode)
      val error = new AtomicReference[Throwable]()
      val thread = new Thread(() =>
        try { service.layout("office", ui.TextStyle.Body, ui.Lp(100)); () }
        catch case e: Throwable => error.set(e))
      thread.start()
      thread.join()
      assert(error.get().isInstanceOf[IllegalArgumentException])
      val bounded = new NativeTextServices(Map(ui.TextStyle.Body -> NativeTextStyle(faces)), unicode,
        g.WrappingLimits(maxCandidates = 1))
      intercept[IllegalArgumentException](bounded.layout("a b c", ui.TextStyle.Body, ui.Lp(10)))
      val resolved = service.layout("office", ui.TextStyle.Body, ui.Lp(100))
      faces.head.close()
      intercept[IllegalArgumentException](service.layout("late", ui.TextStyle.Body, ui.Lp(100)))
      assert(resolved.caret(2, down).nonEmpty)
      assert(faces(1).shape("ب", g.ShapingOptions(g.Script.Arabic, g.TextDirection.RightToLeft, "ar")).glyphs.nonEmpty)
    }
  }

  test("empty core text and controls have real line heights and finite-input validation") {
    services { (faces, unicode) =>
      val service = new NativeTextServices(Map(ui.TextStyle.Body -> NativeTextStyle(faces)), unicode)
      val empty: ui.TextLayout = service.layout("", ui.TextStyle.Body, ui.Lp.zero)
      assertEquals(empty.size.width, ui.Lp.zero)
      assert(empty.size.height.toDouble > 0)
      assertEquals(empty.caretsAt(0).size, 2)
      assertEquals(empty.hit(point(100, 100)).primary.bounds.size.height, empty.size.height)
      assertEquals(empty.selection(0, 0), Vector.empty)
      val controls: ui.TextLayout = service.layout("\u202B\u202C\u200B", ui.TextStyle.Body, ui.Lp(100))
      assertEquals(controls.selection(0, controls.text.length), Vector.empty)
      intercept[IllegalArgumentException](empty.hit(point(Double.NaN, 0)))
      intercept[IllegalArgumentException](empty.hit(point(0, Double.PositiveInfinity)))
      unicode.close()
      intercept[IllegalArgumentException](service.layout("late", ui.TextStyle.Body, ui.Lp(100)))
    }
  }
