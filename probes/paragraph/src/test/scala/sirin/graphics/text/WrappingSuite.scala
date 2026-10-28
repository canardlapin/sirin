package sirin.graphics.text

import sirin.graphics.*
import sirin.graphics.unicode.NativeUnicode
import java.nio.file.{Files, Path as FilePath}
import java.util.concurrent.atomic.AtomicReference

class WrappingSuite extends munit.FunSuite:
  private val latin = ShapingOptions(Script.Latin, TextDirection.LeftToRight, "und")
  private val names = Vector("NotoSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf",
    "NotoSansHebrew-Regular.ttf", "NotoSansDevanagari-Regular.ttf")
  private def services[A](body: (Vector[NativeFont], NativeUnicode) => A): A =
    val unicode = NativeUnicode.open()
    val roots = Vector(FilePath.of("graphics/src/test/resources/fonts"), FilePath.of("../../graphics/src/test/resources/fonts"))
    val faces = names.map { name =>
      val path = roots.map(_.resolve(name)).find(Files.exists(_)).getOrElse(fail(s"missing pinned font $name"))
      NativeFont.open(FontData.copy(Files.readAllBytes(path)), 24)
    }
    try body(faces, unicode)
    finally
      faces.foreach(_.close())
      unicode.close()
  private def range(start: Int, end: Int): Utf8Range = Utf8Range(Utf8Offset(start), Utf8Offset(end))
  private def contents(layout: WrappedParagraphLayout): Vector[String] = layout.lines.map { line =>
    layout.source.text.substring(layout.source.utf16Offset(line.range.start).get.value,
      layout.source.utf16Offset(line.range.end).get.value)
  }

  test("exact word wrap chooses legal furthest-fitting lines and preserves ligatures/global source clusters") {
    services { (faces, unicode) =>
      val width = faces.head.shape("office ", latin).advance.x
      val layout = NativeParagraph.wrap("office office office", faces, unicode, width)
      assertEquals(contents(layout), Vector("office ", "office ", "office"))
      assertEquals(layout.lines.map(_.range), Vector(range(0, 7), range(7, 14), range(14, 20)))
      assert(layout.isComplete && !layout.hasOverflow)
      layout.lines.foreach { placed =>
        val line = placed.layout
        val text = contents(layout)(layout.lines.indexOf(placed))
        val reference = faces.head.shape(text, latin)
        assertEquals(line.width, reference.advance.x)
        assertEquals(line.logicalRuns.flatMap(_.run.glyphs).map(_.id), reference.glyphs.map(_.id))
        assertEquals(line.logicalRuns.flatMap(_.run.glyphs).map(_.cluster.value),
          reference.glyphs.map(_.cluster.value + line.range.start.value))
        assert(line.logicalRuns.forall(r => (r.run.source eq layout.source) && r.run.context == line.range))
        assert(layout.analysis.lineBoundaries.contains(line.range.end))
        assert(layout.analysis.isGraphemeBoundary(line.range.end))
      }
      assertEquals(layout.width, layout.lines.map(_.layout.width).max)
      assertEquals(layout.height, layout.lines.map(_.layout.height).sum)
      assertEquals(layout.lines(1).origin.y, layout.lines.head.layout.height)
    }
  }

  test("a fitting wrapped paragraph matches the unwrapped engine on real mixed-script text") {
    services { (faces, unicode) =>
      val text = "office κόσμος سلام דָבר क्षि"
      val single = NativeParagraph.layout(text, faces, unicode)
      val wrapped = NativeParagraph.wrap(text, faces, unicode, single.width)
      assertEquals(wrapped.lines.size, 1)
      val line = wrapped.lines.head.layout
      assertEquals(line.width, single.width)
      assertEquals(line.height, single.height)
      assertEquals(line.baseline, single.baseline)
      assertEquals(line.items, single.items)
      assertEquals(line.issues, single.issues)
      assertEquals(line.runs.map(_.origin), single.runs.map(_.origin))
      assertEquals(line.logicalRuns.map(_.run.glyphs), single.logicalRuns.map(_.run.glyphs))
      assert(wrapped.isComplete && !wrapped.hasOverflow)
    }
  }

  test("line-bounded context changes Arabic edge forms while clusters remain source-global") {
    services { (faces, _) =>
      val options = ShapingOptions(Script.Arabic, TextDirection.RightToLeft, "ar")
      val source = TextSource("بب")
      val first = faces(1).shapeRange(source, range(0, 2), options)
      val isolated = faces(1).shape("ب", options)
      val bounded = faces(1).shapeRange(source, range(0, 2), options, range(0, 2))
      val later = faces(1).shapeRange(source, range(2, 4), options, range(2, 4))
      assertNotEquals(first.glyphs.map(_.id), bounded.glyphs.map(_.id))
      assertEquals(bounded.glyphs.map(_.id), isolated.glyphs.map(_.id))
      assertEquals(later.glyphs.map(_.id), isolated.glyphs.map(_.id))
      assertEquals(later.glyphs.map(_.cluster.value), Vector(2))
      assertEquals(later.context, range(2, 4))
      intercept[IllegalArgumentException](faces(1).shapeRange(source, range(0, 4), options, range(0, 2)))
      intercept[IllegalArgumentException](faces(1).shapeRange(source, range(2, 4), options, range(1, 4)))
    }
  }

  test("wrapped bidi retains paragraph base/context and resets embedded trailing whitespace per line") {
    services { (faces, unicode) =>
      val prefix = "a \u202Bאב  "
      val text = prefix + "גד\u202C z"
      val width = NativeParagraph.layout(prefix, faces, unicode).width
      val wrapped = NativeParagraph.wrap(text, faces, unicode, width)
      val end = TextSource(prefix).byteSize
      assertEquals(wrapped.lines.head.range.end.value, end)
      assertEquals(wrapped.analysis.bidiLevels(end - 1), 1)
      val first = wrapped.lines.head.layout
      assertEquals(first.bidi.get.codepoints.takeRight(2).map(_.level), Vector(Some(0), Some(0)))
      assertEquals(first.logicalRuns.last.level, 0)
      assert(wrapped.lines.forall(_.layout.bidi.get.paragraphLevel == 0))
      assert(wrapped.lines.drop(1).exists(_.layout.items.exists(_.script == Script.Hebrew)))
      assert(wrapped.lines.forall(p => p.layout.logicalRuns.forall(_.run.context == p.range)))
    }
  }

  test("paired bracket script assignment survives a wrap across different writing systems") {
    services { (faces, unicode) =>
      val text = "abc (देव देव) xyz"
      val wrapped = NativeParagraph.wrap(text, faces, unicode, 0)
      val open = TextSource(text.take(text.indexOf('('))).byteSize
      val close = TextSource(text.take(text.indexOf(')'))).byteSize
      val openingLine = wrapped.lines.indexWhere(p => p.range.start.value <= open && open < p.range.end.value)
      val closingLine = wrapped.lines.indexWhere(p => p.range.start.value <= close && close < p.range.end.value)
      assertNotEquals(openingLine, closingLine)
      for (index, offset) <- Vector((openingLine, open), (closingLine, close)) do
        val item = wrapped.lines(index).layout.items.find(i => i.range.start.value <= offset && offset < i.range.end.value).get
        assertEquals(item.script, Script.Latin)
      assert(wrapped.isComplete && wrapped.hasOverflow)
    }
  }

  test("unbreakable spans overflow explicitly; NBSP/word joiner and zero-width opportunities retain source") {
    services { (faces, unicode) =>
      for text <- Vector("office", "office\u00A0office", "a\u2060b") do
        val layout = NativeParagraph.wrap(text, faces, unicode, 0)
        assertEquals(layout.lines.size, 1)
        assertEquals(contents(layout), Vector(text))
        assert(layout.hasOverflow && layout.isComplete)
        assert(layout.width > layout.maxWidth)
      val zero = NativeParagraph.wrap("a\u200Bb", faces, unicode, 0)
      assertEquals(contents(zero), Vector("a\u200B", "b"))
      assertEquals(zero.lines.map(_.range), Vector(range(0, 4), range(4, 5)))
      val empty = NativeParagraph.wrap("", faces, unicode, 0)
      assertEquals(empty.lines.size, 1)
      assertEquals(empty.width, 0.0)
      assert(empty.height > 0 && empty.isComplete && !empty.hasOverflow)
      assertEquals(empty.lines.head.range, range(0, 0))
    }
  }

  test("combining marks and emoji sequences never split inside graphemes; missing ranges stay global") {
    services { (faces, unicode) =>
      val layout = NativeParagraph.wrap("e\u0301 👩🏽‍💻 🇨🇦", faces, unicode, 0)
      assert(contents(layout).mkString == layout.source.text)
      assert(!layout.isComplete && layout.issues.nonEmpty)
      layout.lines.foreach { line =>
        assert(layout.analysis.isGraphemeBoundary(line.range.start))
        assert(layout.analysis.isGraphemeBoundary(line.range.end))
        assert(layout.analysis.lineBoundaries.contains(line.range.end))
        assert(line.layout.logicalRuns.forall(_.run.glyphs.forall(g => layout.source.utf16Offset(g.cluster).nonEmpty)))
        assert(line.layout.issues.forall(i => i.range.start.value >= line.range.start.value && i.range.end.value <= line.range.end.value))
      }
      assert(layout.lines.exists(_.range.start.value > 0))
    }
  }

  test("stored wrapped placement records exact runs after native services close") {
    val layout = services { (faces, unicode) => NativeParagraph.wrap("office سلام office", faces, unicode, 80) }
    val origin = Point(3.25, 7.5)
    val paint = Paint.Solid(Color(0, 0, 0))
    val drawing = Drawing.record(c => layout.record(c, origin, paint))
    val expected = layout.lines.flatMap(p => p.layout.runs.map(r =>
      (r.resolved.run, Point((origin.x + p.origin.x) + r.origin.x, (origin.y + p.origin.y) + r.origin.y))))
    assertEquals(drawing.commands.size, expected.size)
    drawing.commands.zip(expected).foreach { (command, data) =>
      command match
        case DrawCommand.Glyphs(run, at, recordedPaint) =>
          assert(run eq data._1)
          assertEquals(at, data._2)
          assertEquals(recordedPaint, paint)
        case other => fail(s"unexpected recorded command $other")
    }
    assert(layout.isComplete && layout.lines.size > 1 && layout.inkBounds.nonEmpty)
    assertEquals(DrawingResources.inspect(drawing).toOption.get.fonts.toSet, expected.map(_._1.font.data).toSet)
  }

  test("candidate, shared shaping, line and total run budgets reject rather than return partial layout") {
    services { (faces, unicode) =>
      for width <- Vector(-1.0, Double.NaN, Double.PositiveInfinity) do
        intercept[IllegalArgumentException](NativeParagraph.wrap("a", faces, unicode, width))
      intercept[IllegalArgumentException](WrappingLimits(maxLines = 0))
      intercept[IllegalArgumentException](WrappingLimits(maxCandidates = 0))
      intercept[IllegalArgumentException](NativeParagraph.wrap("one two", faces, unicode, 0, limits = WrappingLimits(maxLines = 1)))
      intercept[IllegalArgumentException](NativeParagraph.wrap("one two", faces, unicode, 0, limits = WrappingLimits(maxCandidates = 1)))
      intercept[IllegalArgumentException](NativeParagraph.wrap("one two", faces, unicode, 0,
        options = ParagraphOptions(limits = ParagraphLimits(maxShapeCalls = 2))))
      intercept[IllegalArgumentException](NativeParagraph.wrap("one two", faces, unicode, 0,
        options = ParagraphOptions(limits = ParagraphLimits(maxItems = 1))))
      for text <- Vector("a\tb", "a\nb", "soft\u00ADhyphen") do
        intercept[IllegalArgumentException](NativeParagraph.wrap(text, faces, unicode, 80))
      val error = new AtomicReference[Throwable]()
      val thread = new Thread(() =>
        try { NativeParagraph.wrap("a", faces, unicode, 80); () }
        catch case problem: Throwable => error.set(problem))
      thread.start()
      thread.join()
      assert(error.get().isInstanceOf[IllegalArgumentException])
      unicode.close()
      intercept[IllegalArgumentException](NativeParagraph.wrap("", faces, unicode, 0))
    }
  }
