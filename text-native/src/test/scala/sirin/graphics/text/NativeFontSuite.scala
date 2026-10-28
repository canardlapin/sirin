package sirin.graphics.text

import sirin.graphics.*
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference

class NativeFontSuite extends munit.FunSuite:
  private val latin = ShapingOptions(Script.Latin, TextDirection.LeftToRight, "en")
  private val arabic = ShapingOptions(Script.Arabic, TextDirection.RightToLeft, "ar")
  private def data(name: String): FontData =
    val paths = Vector(Path.of("graphics/src/test/resources/fonts"), Path.of("../graphics/src/test/resources/fonts"))
    val path = paths.map(_.resolve(name)).find(Files.exists(_)).getOrElse(fail(s"missing pinned font: $name"))
    FontData.copy(Files.readAllBytes(path))
  private def withFont[A](name: String = "NotoSans-Regular.ttf", size: Double = 24)(body: NativeFont => A): A =
    val face = NativeFont.open(data(name), size)
    try body(face) finally face.close()

  test("pinned libraries open a real face and return resolved metrics") {
    withFont() { face =>
      assertEquals(face.font.shaperVersion, "14.5.0")
      assertEquals(face.font.rasterizerVersion, "2.15.0")
      assertEquals(face.font.unitsPerEm, 1000)
      assert(face.font.glyphCount > 1000)
      assert(face.font.metrics.ascent > 0 && face.font.metrics.descent > 0)
      assertEquals(face.font.size, 24.0)
    }
    intercept[NativeTextError](NativeFont.open(FontData.copy(Array[Byte](1, 2, 3)), 24))
  }

  test("OpenType ligature features change real glyph substitution and preserve source clusters") {
    withFont() { face =>
      val shaped = face.shape("office", latin)
      val separate = face.shape("office", latin.copy(standardLigatures = false))
      assert(!shaped.hasMissingGlyphs && !separate.hasMissingGlyphs)
      assertEquals(separate.glyphs.size, 6)
      assert(shaped.glyphs.size < separate.glyphs.size)
      assertEquals(shaped.glyphs.map(_.cluster.value), Vector(0, 1, 4, 5))
      assertEquals(shaped.glyphs.find(_.cluster.value == 1).get.ligatureCarets, Vector(7.5625, 15.140625))
      assert(shaped.advance.x > 0 && shaped.inkBounds.nonEmpty)
      assertEquals(shaped.advance.y, 0.0)
    }
  }

  test("combining marks and multilingual runs preserve UTF-8 source boundaries") {
    withFont() { face =>
      val combining = face.shape("x\u0301", latin)
      assert(!combining.hasMissingGlyphs)
      assert(combining.glyphs.forall(_.cluster.value == 0))
      val greek = face.shape("κόσμος", ShapingOptions(Script.Greek, TextDirection.LeftToRight, "el"))
      assert(!greek.hasMissingGlyphs)
      assert(greek.glyphs.forall(g => greek.source.utf16Offset(g.cluster).nonEmpty))
      assert(face.shape("سلام", latin).hasMissingGlyphs)
    }
    withFont("NotoNaskhArabic-Regular.ttf") { face =>
      val run = face.shape("سلام", arabic)
      assert(!run.hasMissingGlyphs)
      assert(run.glyphs.map(_.cluster.value).sliding(2).forall(v => v(0) >= v(1)))
      assert(run.advance.x > 0)
    }
  }

  test("empty text has no invented glyph, advance or ink") {
    withFont() { face =>
      val run = face.shape("", latin)
      assertEquals(run.glyphs, Vector.empty)
      assertEquals(run.advance, Point(0, 0))
      assertEquals(run.inkBounds, None)
    }
  }

  test("FreeType coverage agrees with unhinted glyph ink and supports fractional x phase") {
    withFont() { face =>
      val run = face.shape("T", latin)
      val glyph = run.glyphs.head
      val raster = face.rasterize(glyph.id)
      assert(raster.byteSize > 0 && raster.coverageCopy.exists(_ != 0))
      val ink = glyph.ink.get
      assertEqualsDouble(raster.left.toDouble, ink.x, 1.1)
      assertEqualsDouble(-raster.top.toDouble, ink.y, 1.1)
      assertEqualsDouble(raster.width.toDouble, ink.width, 2.1)
      val shifted = face.rasterize(glyph.id, subpixelX = 0.25)
      assert(shifted.coverageCopy.toVector != raster.coverageCopy.toVector)
      val large = face.rasterize(glyph.id, deviceScale = 1.5)
      assert(large.height > raster.height)
      assertEquals(run.advance, face.shape("T", latin).advance)
      val space = face.shape(" ", latin).glyphs.head
      assertEquals(face.rasterize(space.id).byteSize, 0)
    }
  }

  test("owned masks and recorded runs survive native face closure without reshaping") {
    val face = NativeFont.open(data("NotoSans-Regular.ttf"), 24)
    val run = try face.shape("office", latin) finally face.close()
    val drawing = Drawing.record { c =>
      c.glyphs(run, Point(0.25, 30), Paint.Solid(Color(0, 0, 0)))
      c.glyphs(run, Point(0.25, 60), Paint.Solid(Color(1, 0, 0)))
    }
    val resources = DrawingResources.inspect(drawing).toOption.get
    assertEquals(resources.fonts, Vector(run.font.data))
    assertEquals(resources.fontBytes, run.font.data.byteSize.toLong)
    val DrawCommand.Glyphs(recorded, _, _) = drawing.commands.head: @unchecked
    assert(recorded eq run)
    intercept[IllegalArgumentException](face.shape("late", latin))
    face.close()
    val mask = withFont()(opened => opened.rasterize(opened.shape("T", latin).glyphs.head.id))
    val original = mask.coverageCopy.toVector
    val copy = mask.coverageCopy
    copy.indices.foreach(i => copy(i) = 0)
    assertEquals(mask.coverageCopy.toVector, original)
  }

  test("native face rejects foreign owner use and raster/input bounds") {
    withFont() { face =>
      val outcome = new AtomicReference[Throwable]()
      val thread = new Thread(() =>
        try { face.shape("wrong thread", latin); () }
        catch case error: Throwable => outcome.set(error))
      thread.start()
      thread.join()
      assert(outcome.get().isInstanceOf[IllegalArgumentException])
      intercept[IllegalArgumentException](face.rasterize(face.font.glyphCount))
      intercept[IllegalArgumentException](face.rasterize(1, subpixelX = 0.1))
      intercept[IllegalArgumentException](face.rasterize(1, deviceScale = Double.NaN))
      val glyph = face.shape("T", latin).glyphs.head
      intercept[NativeTextError](face.rasterize(glyph.id, maxPixels = 1))
    }
  }
