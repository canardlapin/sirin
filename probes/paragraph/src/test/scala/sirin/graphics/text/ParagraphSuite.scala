package sirin.graphics.text

import sirin.graphics.*
import sirin.graphics.unicode.NativeUnicode
import java.nio.file.{Files, Path as FilePath}
import java.util.concurrent.atomic.AtomicReference

class ParagraphSuite extends munit.FunSuite:
  private val names = Vector("NotoSans-Regular.ttf", "NotoNaskhArabic-Regular.ttf",
    "NotoSansHebrew-Regular.ttf", "NotoSansDevanagari-Regular.ttf")
  private def fontData(name: String): FontData =
    val roots = Vector(FilePath.of("graphics/src/test/resources/fonts"), FilePath.of("../../graphics/src/test/resources/fonts"))
    val path = roots.map(_.resolve(name)).find(Files.exists(_)).getOrElse(fail(s"missing pinned font $name"))
    FontData.copy(Files.readAllBytes(path))
  private def services[A](body: (Vector[NativeFont], NativeUnicode) => A): A =
    val unicode = NativeUnicode.open()
    val faces = names.map(n => NativeFont.open(fontData(n), 24))
    try body(faces, unicode)
    finally
      faces.foreach(_.close())
      unicode.close()
  private def range(start: Int, end: Int): Utf8Range = Utf8Range(Utf8Offset(start), Utf8Offset(end))
  private def itemize(text: String, unicode: NativeUnicode): Vector[ScriptItem] =
    val analysis = unicode.analyze(text)
    ScriptItemization(analysis, unicode.resolveLine(analysis, range(0, analysis.source.byteSize)), unicode.properties(analysis.source))
  private def substring(source: TextSource, r: Utf8Range): String =
    source.text.substring(source.utf16Offset(r.start).get.value, source.utf16Offset(r.end).get.value)

  test("Unicode properties and itemization resolve Common, Inherited, Script_Extensions and paired punctuation") {
    val unicode = NativeUnicode.open()
    try
      val paired = itemize("abc (देव) xyz", unicode)
      assertEquals(paired.map(_.script.tag), Vector("Latn", "Deva", "Latn"))
      val leading = itemize("\u0301κόσμος", unicode)
      assertEquals(leading.map(_.script), Vector(Script.Greek))
      val japanese = itemize("あーカ", unicode)
      assertEquals(japanese.map(_.script.tag), Vector("Hira", "Kana"))
      assertEquals(japanese.map(_.range), Vector(range(0, 6), range(6, 9)))
      val extension = unicode.properties(TextSource("ー")).head
      assertEquals(extension.primary, Script.Common)
      assertEquals(extension.extensions.map(_.tag).toSet, Set("Hira", "Kana"))
      assertEquals(itemize("a\u05B0", unicode).map(_.script), Vector(Script.Latin))
      assertEquals(itemize("\uE000", unicode).map(_.script), Vector(Script.Unknown))
      assert(unicode.properties(TextSource("\u200D")).head.defaultIgnorable)
      assertEquals(unicode.visualOrder(Vector(0, 1, 1, 2)), Vector(0, 3, 2, 1))
    finally unicode.close()
  }

  test("contextual shaping preserves Arabic joining and global ligature clusters") {
    services { (faces, _) =>
      val arabic = ShapingOptions(Script.Arabic, TextDirection.RightToLeft, "ar")
      val source = TextSource("بب")
      val full = faces(1).shapeRange(source, range(0, 4), arabic)
      val first = faces(1).shapeRange(source, range(0, 2), arabic)
      val second = faces(1).shapeRange(source, range(2, 4), arabic)
      assertEquals(first.glyphs.map(_.id), full.glyphs.filter(_.cluster.value == 0).map(_.id))
      assertEquals(second.glyphs.map(_.id), full.glyphs.filter(_.cluster.value == 2).map(_.id))
      assertNotEquals(first.glyphs.map(_.id), faces(1).shape("ب", arabic).glyphs.map(_.id))
      assert(second.source eq source)
      assertEquals(second.glyphs.map(_.cluster.value), Vector(2))
      val latin = ShapingOptions(Script.Latin, TextDirection.LeftToRight, "en")
      val context = TextSource("x office y")
      val run = faces.head.shapeRange(context, range(2, 8), latin)
      assertEquals(run.glyphs.map(_.cluster.value), Vector(2, 3, 6, 7))
      assertEquals(run.advance, faces.head.shape("office", latin).advance)
      intercept[IllegalArgumentException](faces(1).shapeRange(source, range(1, 4), arabic))
    }
  }

  test("real mixed-script paragraph selects supplied faces and shares measured positions with recording") {
    services { (faces, unicode) =>
      val layout = NativeParagraph.layout("office κόσμος سلام דָבר क्षि", faces, unicode)
      assert(layout.isComplete, layout.issues.toString)
      assertEquals(layout.logicalRuns.map(_.run.options.script.tag).toSet, Set("Latn", "Grek", "Arab", "Hebr", "Deva"))
      assert(layout.logicalRuns.exists(r => r.run.font eq faces(1).font))
      assert(layout.logicalRuns.exists(r => r.run.font eq faces(2).font))
      assert(layout.logicalRuns.exists(r => r.run.font eq faces(3).font))
      assertEquals(layout.width, layout.runs.map(_.resolved.run.advance.x).sum)
      assert(layout.height > 24 && layout.baseline > 0 && layout.inkBounds.nonEmpty)
      assert(layout.runs.forall(_.origin.y == layout.baseline))
      layout.logicalRuns.foreach { resolved =>
        assert(resolved.run.source eq layout.source)
        assert(resolved.run.glyphs.forall(g => layout.source.utf16Offset(g.cluster).nonEmpty))
        val owner = faces.find(_.font eq resolved.run.font).get
        resolved.run.glyphs.find(_.ink.nonEmpty).foreach(g => assert(owner.rasterize(g.id).coverageCopy.exists(_ != 0)))
      }
      val origin = Point(7, 11)
      val paint = Paint.Solid(Color(0, 0, 0))
      val drawing = Drawing.record(canvas => layout.record(canvas, origin, paint))
      assertEquals(drawing.commands.size, layout.runs.size)
      drawing.commands.zip(layout.runs).foreach { (command, placed) =>
        command match
          case DrawCommand.Glyphs(run, at, recordedPaint) =>
            assert(run eq placed.resolved.run)
            assertEquals(at, Point(origin.x + placed.origin.x, origin.y + placed.origin.y))
            assertEquals(recordedPaint, paint)
          case other => fail(s"unexpected paragraph command $other")
      }
      val resources = DrawingResources.inspect(drawing).toOption.get
      assertEquals(resources.fonts.toSet, layout.logicalRuns.map(_.run.font.data).toSet)
    }
  }

  test("RTL placement uses logical source shaping, visual run order and real mirrored punctuation") {
    services { (faces, unicode) =>
      val layout = NativeParagraph.layout("אבג office דהו", faces, unicode)
      assert(layout.isComplete)
      assertEquals(layout.bidi.get.paragraphLevel, 1)
      assert(layout.runs.head.resolved.run.range.start.value > layout.runs.last.resolved.run.range.start.value)
      layout.runs.filter(_.resolved.level % 2 == 1).foreach { placed =>
        val clusters = placed.resolved.run.glyphs.map(_.cluster.value)
        assert(clusters.zip(clusters.drop(1)).forall((a, b) => a >= b))
      }
      val rtl = ShapingOptions(Script.Hebrew, TextDirection.RightToLeft, "he")
      val ltr = rtl.copy(direction = TextDirection.LeftToRight)
      assertEquals(faces(2).shape("(", rtl).glyphs.map(_.id), faces(2).shape(")", ltr).glyphs.map(_.id))
      val hebrewText = layout.logicalRuns.filter(_.run.options.script == Script.Hebrew)
      assert(hebrewText.forall(r => substring(layout.source, r.run.range).exists(c => c >= 'א' && c <= 'ת')))
      val mirrored = NativeParagraph.layout("(office)", faces, unicode,
        ParagraphOptions(direction = ParagraphDirection.RightToLeft))
      val latin = ShapingOptions(Script.Latin, TextDirection.LeftToRight, "und")
      assertEquals(mirrored.logicalRuns.head.run.glyphs.map(_.id), faces.head.shape(")", latin).glyphs.map(_.id))
      assertEquals(mirrored.logicalRuns.last.run.glyphs.map(_.id), faces.head.shape("(", latin).glyphs.map(_.id))
      assertEquals(mirrored.runs.map(_.resolved.run.range.start.value), Vector(7, 1, 0))
    }
  }

  test("fallback inside an item reshapes whole atoms and preserves a mixed-font source partition") {
    services { (faces, unicode) =>
      val options = ShapingOptions(Script.Hebrew, TextDirection.RightToLeft, "und")
      assert(!faces.head.shape("±", options).hasMissingGlyphs)
      assert(faces(2).shape("±", options).hasMissingGlyphs)
      assert(faces.head.shape("־", options).hasMissingGlyphs)
      assert(!faces(2).shape("־", options).hasMissingGlyphs)
      val layout = NativeParagraph.layout("±־", Vector(faces.head, faces(2)), unicode)
      assertEquals(layout.items.size, 1)
      assert(layout.isComplete, layout.issues.toString)
      assertEquals(layout.logicalRuns.map(_.run.font), Vector(faces.head.font, faces(2).font))
      assertEquals(layout.logicalRuns.map(_.run.range), Vector(range(0, 2), range(2, 4)))
    }
  }

  test("no face can cover a base/mark grapheme: missing text stays explicit and unsplit") {
    services { (faces, unicode) =>
      val layout = NativeParagraph.layout("a\u05B0😀", Vector(faces.head, faces(2)), unicode)
      assert(!layout.isComplete)
      assert(layout.logicalRuns.exists(_.run.hasMissingGlyphs))
      assert(layout.issues.exists(i => i.reason == TextIssueReason.MissingGlyphs && i.range == range(0, 3)))
      assert(!layout.logicalRuns.exists(r => r.run.range.start.value == 1 || r.run.range.end.value == 1))
      val variation = NativeParagraph.layout("a\uFE0F", faces, unicode)
      assert(!variation.isComplete)
      assert(variation.issues.exists(_.reason == TextIssueReason.UnsupportedVariationSequence))
      val ligature = NativeParagraph.layout("office😀", faces, unicode)
      assert(!ligature.isComplete)
      assertEquals(ligature.logicalRuns.flatMap(_.run.glyphs).map(_.cluster.value), Vector(0, 1, 4, 5, 6))
      assert(ligature.issues.exists(i => i.range == range(6, 10) && i.reason == TextIssueReason.MissingGlyphs))
    }
  }

  test("empty and whitespace paragraphs use actual face metrics; copied layouts outlive native owners") {
    val layout = services { (faces, unicode) =>
      val empty = NativeParagraph.layout("", faces, unicode)
      assertEquals(empty.width, 0.0)
      assertEquals(empty.height, faces.head.font.metrics.ascent + faces.head.font.metrics.descent + math.max(0, faces.head.font.metrics.lineGap))
      assertEquals(empty.runs, Vector.empty)
      assert(empty.isComplete)
      val spaces = NativeParagraph.layout("   ", faces, unicode)
      assert(spaces.width > 0 && spaces.inkBounds.isEmpty && spaces.isComplete)
      NativeParagraph.layout("office سلام", faces, unicode)
    }
    assert(layout.isComplete)
    assert(layout.logicalRuns.forall(_.run.font.data.bytesCopy.nonEmpty))
    assert(Drawing.record(c => layout.record(c, Point(0, 0), Paint.Solid(Color(0, 0, 0)))).commands.nonEmpty)
  }

  test("hard breaks, tabs, work limits, closed services and other-thread use reject explicitly") {
    services { (faces, unicode) =>
      for text <- Vector("a\nb", "a\rb", "a\tb", "a\u2028b", "\uD800") do
        intercept[IllegalArgumentException](NativeParagraph.layout(text, faces, unicode))
      def limited(text: String, limits: ParagraphLimits): Unit =
        intercept[IllegalArgumentException](NativeParagraph.layout(text, faces, unicode, ParagraphOptions(limits = limits)))
      limited("é", ParagraphLimits(maxInputBytes = 1))
      limited("abc", ParagraphLimits(maxShapedBytes = 1))
      limited("office", ParagraphLimits(maxShapedGlyphs = 1))
      limited("abc", ParagraphLimits(maxFaces = 1))
      limited("abc κόσμος", ParagraphLimits(maxItems = 1))
      limited("abc κόσμος", ParagraphLimits(maxShapeCalls = 1))
      limited("a(((b)))", ParagraphLimits(maxBracketDepth = 2))
      val error = new AtomicReference[Throwable]()
      val thread = new Thread(() =>
        try { NativeParagraph.layout("abc", faces, unicode); () }
        catch case problem: Throwable => error.set(problem))
      thread.start()
      thread.join()
      assert(error.get().isInstanceOf[IllegalArgumentException])
      faces.head.close()
      intercept[IllegalArgumentException](NativeParagraph.layout("", faces, unicode))
    }
  }
