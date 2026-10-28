package sirin.desktop

import java.nio.file.{Files, Path}
import sirin.graphics.*
import sirin.graphics.text.*
import sirin.graphics.unicode.NativeUnicode
import sirin.text.ResolvedTextLayout

class NativeServicesSuite extends munit.FunSuite:
  private val fontPath = Path.of("../graphics/src/test/resources/fonts/NotoSans-Regular.ttf")
  test("JVM FFM uses real font, Unicode, wrapping and retained shared editing geometry") {
    val face = NativeFont.open(FontData.copy(Files.readAllBytes(fontPath)),18)
    val unicode = NativeUnicode.open()
    try
      val paragraph = NativeParagraph.wrap("office café שלום 😀",Vector(face),unicode,120)
      val result = ResolvedTextLayout(paragraph)
      assertEquals(result.text,"office café שלום 😀")
      assert(result.size.height.toDouble>0)
      assert(result.caretsAt(1).nonEmpty)
      assert(result.caretsAt(result.text.length-1).isEmpty)
      assert(result.selection(0,6).nonEmpty)
      val run=face.shape("office",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en"))
      val raster=face.rasterize(run.glyphs.head.id)
      assert(raster.byteSize>0)
      val saved=result.caretsAt(1)
      unicode.close();face.close()
      assertEquals(result.caretsAt(1),saved)
      intercept[IllegalArgumentException](face.shape("closed",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en")))
    finally {unicode.close();face.close()}
  }
