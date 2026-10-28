package sirin.graphics.text

import sirin.graphics.*
import sirin.graphics.unicode.NativeUnicode
import java.nio.file.{Files, Path as FilePath}

class EditingGeometrySuite extends munit.FunSuite:
  private val up = CaretAffinity.Upstream
  private val down = CaretAffinity.Downstream
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
  private def at(g: TextGeometry, utf16: Int, affinity: CaretAffinity = CaretAffinity.Downstream): TextCaret =
    g.caretUtf16(Utf16Offset(utf16), affinity).getOrElse(fail(s"missing caret at $utf16/$affinity"))
  private def checkTopology(g: TextGeometry): Unit =
    assertEquals(g.graphemes.map(_.range), g.analysis.graphemeBoundaries.filter(o =>
      o.value >= g.range.start.value && o.value <= g.range.end.value).sliding(2)
      .filter(_.size == 2).map(v => Utf8Range(v(0), v(1))).toVector)
    g.carets.foreach { c =>
      assert(g.analysis.isGraphemeBoundary(c.position.offset))
      assertEquals(g.caret(c.position), Some(c))
      assertEquals(c.bounds.width, 0.0)
      val hit = g.hit(Point(c.bounds.x, c.bounds.y + c.bounds.height / 2))
      assert((hit.primary +: hit.alternatives).contains(c), s"lost coincident caret $c in $hit")
      assertEquals(hit.primary.bounds.x, c.bounds.x)
    }
    g.graphemes.foreach { cell =>
      val selected = g.selection(cell.range)
      if cell.bounds.width == 0 then assertEquals(selected, Vector.empty)
      else
        assertEquals(selected.size, 1)
        assertEquals(selected.head.bounds, cell.bounds)
        assertEquals(selected.head.lineIndex, cell.leading.lineIndex)
    }

  test("font GDEF positions, not thirds of ffi, drive ligature carets and partial selection") {
    services { (faces, unicode) =>
      val layout = NativeParagraph.layout("office", faces, unicode)
      val g = layout.geometry
      val ligature = layout.logicalRuns.head.run.glyphs.find(_.cluster.value == 1).get
      // Independent values from the pinned font's GDEF design coordinates 315/631,
      // scaled to 24 logical units and rounded to HarfBuzz's 1/64 precision.
      assertEquals(ligature.ligatureCarets, Vector(7.5625, 15.140625))
      assertEquals(at(g, 2).kind, CaretEdgeKind.FontLigature)
      assertEquals(at(g, 3).kind, CaretEdgeKind.FontLigature)
      assertEquals(at(g, 2).bounds.x, ligature.position.x + 7.5625)
      assertEquals(at(g, 3).bounds.x, ligature.position.x + 15.140625)
      assertNotEquals(at(g, 2).bounds.x - at(g, 1).bounds.x, (at(g, 4).bounds.x - at(g, 1).bounds.x) / 3)
      assert(!g.selection(range(2, 3)).head.interpolated)
      assertEquals(g.selection(range(2, 3)).head.bounds.width, 15.140625 - 7.5625)
      assertEquals(at(g, 0, up).bounds.x, 0.0)
      assertEquals(at(g, 6, down).bounds.x, layout.width)
      checkTopology(g)
    }
  }

  test("feature-off glyphs have cluster-edge carets; ligatures with joiners explicitly interpolate") {
    services { (faces, unicode) =>
      val plain = NativeParagraph.layout("office", faces, unicode, ParagraphOptions(standardLigatures = false))
      assert(plain.geometry.carets.forall(_.kind == CaretEdgeKind.ClusterEdge))
      // The joiner belongs to an extended grapheme while the ligature spans
      // multiple graphemes. GDEF character carets cannot be mapped one-to-one.
      val joined = NativeParagraph.layout("of\u200Dfice", faces, unicode)
      assert(joined.isComplete)
      assert(joined.geometry.carets.exists(_.kind == CaretEdgeKind.Interpolated),
        joined.logicalRuns.flatMap(_.run.glyphs).toString)
      assert(joined.geometry.graphemes.exists(cell => joined.geometry.selection(cell.range).exists(_.interpolated)))
      val leading = at(joined.geometry, 1).bounds.x
      val trailing = at(joined.geometry, 5, up).bounds.x
      val clusterAdvance = joined.logicalRuns.head.run.glyphs.filter(_.cluster.value == 1).map(_.advance.x).sum
      assertEqualsDouble(trailing - leading, clusterAdvance, 1e-12)
      assertEqualsDouble(at(joined.geometry, 3).bounds.x, leading + clusterAdvance / 3, 1e-12)
      assertEqualsDouble(at(joined.geometry, 4).bounds.x, leading + clusterAdvance * 2 / 3, 1e-12)
      val arabic = NativeParagraph.layout("سلام لا", faces, unicode)
      assert(arabic.isComplete)
      assert(arabic.geometry.graphemes.forall(c => c.leading.bounds.x >= c.trailing.bounds.x))
      checkTopology(plain.geometry)
      checkTopology(joined.geometry)
      checkTopology(arabic.geometry)
    }
  }

  test("combining sequences, Indic conjuncts and emoji have no internal editing stops") {
    services { (faces, unicode) =>
      val text = "x\u0301 דָ क्षि 👩‍👩‍👧‍👦 🇨🇦"
      val layout = NativeParagraph.layout(text, faces, unicode)
      val g = layout.geometry
      assert(!layout.isComplete) // Pinned faces deliberately have no emoji.
      assertEquals(g.caretUtf16(Utf16Offset(1), down), None)
      for i <- 0 to text.length do
        val expected = g.analysis.graphemeUtf16Boundaries.contains(Utf16Offset(i))
        assertEquals(g.caretUtf16(Utf16Offset(i), down).nonEmpty, expected, s"UTF-16 $i")
      for i <- 0 to g.source.byteSize do
        assertEquals(g.caretsAt(Utf8Offset(i)).nonEmpty, g.analysis.isGraphemeBoundary(Utf8Offset(i)), s"UTF-8 $i")
      checkTopology(g)
    }
  }

  test("bidi affinity preserves dual positions and logical selection can have two visual regions") {
    services { (faces, unicode) =>
      val layout = NativeParagraph.layout("abc אבג xyz", faces, unicode)
      val g = layout.geometry
      assert(layout.isComplete)
      assert(at(g, 4, up).bounds.x < at(g, 4, down).bounds.x)
      assert(at(g, 7, up).bounds.x < at(g, 7, down).bounds.x)
      val partial = g.selectionUtf16(Utf16Offset(0), Utf16Offset(5))
      assertEquals(partial.size, 2)
      assert(partial.head.bounds.x + partial.head.bounds.width < partial.last.bounds.x)
      val whole = g.selection(range(0, layout.source.byteSize))
      assertEquals(whole.size, 1)
      assertEqualsDouble(whole.head.bounds.width, layout.width, 1e-12)
      checkTopology(g)
    }
  }

  test("wrap affinity chooses preceding or following line and hit uses each actual line's height") {
    services { (faces, unicode) =>
      val width = NativeParagraph.layout("office ", faces, unicode).width
      val layout = NativeParagraph.wrap("office office office", faces, unicode, width)
      val g = layout.geometry
      assertEquals(layout.lines.size, 3)
      val before = at(g, 7, up)
      val after = at(g, 7, down)
      assertEquals(before.lineIndex, 0)
      assertEquals(after.lineIndex, 1)
      assertEquals(before.bounds.x, layout.lines.head.layout.width)
      assertEquals(after.bounds.x, 0.0)
      assertEquals(after.bounds.y, layout.lines(1).origin.y)
      assertEquals(g.hit(Point(0, after.bounds.y)).primary.lineIndex, 1)
      assertEquals(g.hit(Point(-100, -100)).primary.lineIndex, 0)
      assertEquals(g.hit(Point(1e6, 1e6)).primary.lineIndex, 2)
      assertEquals(g.selection(range(0, layout.source.byteSize)).map(_.lineIndex), Vector(0, 1, 2))
      layout.lines.foreach(p => checkTopology(p.layout.geometry))
      checkTopology(g)
    }
  }

  test("wrapped mixed bidi, brackets and controls keep global source and selection mappings") {
    services { (faces, unicode) =>
      val text = "a \u202Bאב  גד\u202C z (office) x\u0301 \u200B"
      val width = NativeParagraph.layout("a אב  ", faces, unicode).width
      val wrapped = NativeParagraph.wrap(text, faces, unicode, width)
      assert(wrapped.lines.size > 1)
      checkTopology(wrapped.geometry)
      val g = NativeParagraph.layout("a\u202Bאב\u202C\u200B", faces, unicode).geometry
      checkTopology(g)
      val onlyControl = NativeParagraph.layout("\u202B\u202C\u200B", faces, unicode)
      assertEquals(onlyControl.width, 0.0)
      checkTopology(onlyControl.geometry)
      assertEquals(onlyControl.geometry.selection(range(0, onlyControl.source.byteSize)), Vector.empty)
    }
  }

  test("empty and overflowing text have real-height carets and retain spaces in selection") {
    services { (faces, unicode) =>
      val empty = NativeParagraph.wrap("", faces, unicode, 0)
      val g = empty.geometry
      assertEquals(g.graphemes, Vector.empty)
      assertEquals(g.carets.size, 2)
      assertEquals(g.hit(Point(100, 100)).primary.bounds, Rect(0, 0, 0, empty.height))
      assertEquals(g.selection(range(0, 0)), Vector.empty)
      checkTopology(g)
      val overflow = NativeParagraph.wrap("office   ", faces, unicode, 0)
      assert(overflow.hasOverflow)
      val selected = overflow.geometry.selection(range(0, overflow.source.byteSize))
      assertEqualsDouble(selected.head.bounds.width, overflow.width, 1e-12)
      checkTopology(overflow.geometry)
    }
  }

  test("nearest-stop ties remain explicit and transformed hosts can map input through the drawing inverse") {
    services { (faces, unicode) =>
      val layout = NativeParagraph.layout("W", faces, unicode)
      val g = layout.geometry
      val hit = g.hit(Point(layout.width / 2, layout.height / 2))
      assertEquals(hit.primary.position, TextPosition(Utf8Offset(0), down))
      assertEquals((hit.primary +: hit.alternatives).size, 4) // two affinities at both edges
      val transform = Affine.rotation(0.7).andThen(Affine.scale(1.5, 2)).andThen(Affine.translation(20, 80))
      val local = Point(layout.width - 0.1, layout.height / 2)
      val device = transform(local)
      val restored = transform.inverse.get(device)
      assertEquals(g.hit(restored).primary.position.offset, Utf8Offset(1))
      val drawing = Drawing.record(_.transformed(transform)(layout.record(_, Point(0, 0), Paint.Solid(Color(0, 0, 0)))))
      assert(drawing.commands.exists { case DrawCommand.Glyphs(run, _, _) => run eq layout.logicalRuns.head.run; case _ => false })
    }
  }

  test("geometry can be first prepared after native services close and recording retains identical glyph runs") {
    val layout = services((faces, unicode) => NativeParagraph.wrap("office אבג سلام", faces, unicode, 90))
    // Deliberately first touch lazy geometry after the service owners are gone.
    val geometry = layout.geometry
    checkTopology(geometry)
    assert(geometry eq layout.geometry)
    val drawing = Drawing.record(layout.record(_, Point(0, 0), Paint.Solid(Color(0, 0, 0))))
    val glyphs = drawing.commands.collect { case DrawCommand.Glyphs(run, _, _) => run }
    val original = layout.lines.flatMap(_.layout.runs.map(_.resolved.run))
    assertEquals(glyphs, original)
    assert(glyphs.zip(original).forall((a, b) => a eq b))
  }

  test("invalid UTF encodings, grapheme interiors, reversed and out-of-layout selections reject without snapping") {
    services { (faces, unicode) =>
      val g = NativeParagraph.layout("x\u0301😀", faces, unicode).geometry
      assertEquals(g.caretUtf16(Utf16Offset(3), down), None) // surrogate interior
      assertEquals(g.caretUtf16(Utf16Offset(1), down), None) // combining interior
      assertEquals(g.caret(TextPosition(Utf8Offset(2), up)), None) // continuation byte
      assertEquals(g.caret(TextPosition(Utf8Offset(999), up)), None)
      intercept[IllegalArgumentException](g.selection(range(0, 1)))
      intercept[IllegalArgumentException](g.selection(range(0, 999)))
      intercept[IllegalArgumentException](g.selectionUtf16(Utf16Offset(0), Utf16Offset(3)))
      intercept[IllegalArgumentException](g.selectionUtf16(Utf16Offset(4), Utf16Offset(0)))
      val wrap = NativeParagraph.wrap("a a a", faces, unicode, 25)
      val line = wrap.lines.last.layout.geometry
      intercept[IllegalArgumentException](line.selection(range(0, line.source.byteSize)))
    }
  }
