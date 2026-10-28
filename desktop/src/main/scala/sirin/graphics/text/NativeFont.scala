// JVM FFM platform adaptation; shares the native service ABI and result algorithms.
package sirin.graphics.text

import sirin.graphics.*
import sirin.desktop.ffm.Memory.*
import sirin.desktop.ffm.Calls

final class NativeTextError(val operation: String, val status: Int)
    extends RuntimeException(s"$operation failed (native status $status)")

/** Explicitly closed, thread-confined static font face. No finalizer, created
  * worker or Scala callback. Slow operations belong on a caller-owned worker.
  */
final class NativeFont private (private var handle: Ptr[Byte], val font: ResolvedFont) extends AutoCloseable:
  private val owner = Thread.currentThread()
  private def checkThread(): Unit =
    require(Thread.currentThread() eq owner, "font service used from a different thread")
  private[text] def checkOpen(): Unit =
    checkThread()
    require(handle != null, "native font is closed")

  def shape(text: String, options: ShapingOptions): ShapedRun =
    checkOpen()
    require(text.length <= 1048576, "text run exceeds 1,048,576 UTF-16 code units")
    val source = TextSource(text)
    shapeRange(source, Utf8Range(Utf8Offset(0), Utf8Offset(source.byteSize)), options)

  /** Shape logical input with the entire source available as context. Glyph
    * clusters use global source UTF-8 offsets; pen positions remain run-local.
    */
  def shapeRange(source: TextSource, range: Utf8Range, options: ShapingOptions): ShapedRun =
    shapeRange(source, range, options, Utf8Range(Utf8Offset(0), Utf8Offset(source.byteSize)))

  /** Explicit shaping context, e.g. one visual line. Text outside this window
    * cannot affect joining or substitution. Clusters stay source-global.
    */
  def shapeRange(source: TextSource, range: Utf8Range, options: ShapingOptions, context: Utf8Range): ShapedRun =
    checkOpen()
    require(source.byteSize <= 1048576, "text run exceeds 1 MiB UTF-8 limit")
    require(source.utf16Offset(range.start).nonEmpty && source.utf16Offset(range.end).nonEmpty,
      "shaped range must use source codepoint boundaries")
    require(context.start.value <= range.start.value && context.end.value >= range.end.value &&
      source.utf16Offset(context.start).nonEmpty && source.utf16Offset(context.end).nonEmpty,
      "shaping context must contain the item and use source codepoint boundaries")
    Zone.acquire { implicit zone =>
      val bytes = source.utf8Copy
      val input = alloc[Byte](math.max(1, bytes.length))
      input.raw.copyFrom(java.lang.foreign.MemorySegment.ofArray(bytes))
      val out = alloc[Ptr[Byte]]()
      NativeFont.check("shape", TextShim.sg_text_shape(handle, input, bytes.length,
        if options.direction == TextDirection.RightToLeft then 1 else 0,
        toCString(options.script.tag), toCString(options.language), if options.standardLigatures then 1 else 0,
        range.start.value, range.end.value, context.start.value, context.end.value, out))
      val result = !out
      try
        val count = TextShim.sg_text_run_count(result)
        val id = alloc[CUnsignedInt]()
        val cluster = alloc[CUnsignedInt]()
        val values = alloc[CDouble](8)
        val hasInk = alloc[CInt]()
        val glyphs = Vector.newBuilder[PositionedGlyph]
        var pen = Point(0, 0)
        var index = 0
        while index < count do
          NativeFont.check("glyph", TextShim.sg_text_glyph(result, index, id, cluster, values, hasInk))
          val advance = Point(values(0), values(1))
          val position = Point(pen.x + values(2), pen.y + values(3))
          val ink = if !hasInk == 0 then None else Some(Rect(values(4), values(5), values(6), values(7)))
          glyphs += PositionedGlyph((!id).toInt, Utf8Offset((!cluster).toInt), position, advance, ink)
          pen = Point(pen.x + advance.x, pen.y + advance.y)
          index += 1
        val shaped = glyphs.result()
        val groups = shaped.groupBy(_.cluster)
        val starts = groups.keys.toVector.sortBy(_.value)
        val ends = starts.zip(starts.drop(1) :+ range.end).toMap
        val caretValues = alloc[CDouble](64)
        val caretCount = alloc[CInt]()
        val withCarets = shaped.zipWithIndex.map { (glyph, i) =>
          if groups(glyph.cluster).size == 1 then
            val start16 = source.utf16Offset(glyph.cluster).get.value
            val end16 = source.utf16Offset(ends(glyph.cluster)).get.value
            val scalars = source.text.codePointCount(start16, end16)
            if scalars > 1 then
              NativeFont.check("ligature carets", TextShim.sg_text_carets(result, i, caretValues, caretCount))
              if !caretCount == scalars - 1 then
                glyph.copy(ligatureCarets = Vector.tabulate(!caretCount)(j => caretValues(j)))
              else glyph
            else glyph
          else glyph
        }
        new ShapedRun(source, font, options, withCarets, range, context)
      finally TextShim.sg_text_run_free(result)
    }

  /** Unhinted grayscale coverage. Scale changes rasterization, never the stored
    * run advances. Phase is a 1/64-pixel horizontal shift; y phase stays zero.
    */
  def rasterize(glyphId: Int, deviceScale: Double = 1, subpixelX: Double = 0,
      maxPixels: Int = 1048576): GlyphRaster =
    checkOpen()
    require(glyphId >= 0 && glyphId < font.glyphCount, "glyph ID outside font")
    require(deviceScale.isFinite && deviceScale > 0 && font.size * deviceScale <= 4096,
      "raster scale must be positive and physical size <= 4096")
    require(subpixelX.isFinite && subpixelX >= 0 && subpixelX < 1 && subpixelX * 64 == math.rint(subpixelX * 64),
      "subpixel phase must be a multiple of 1/64 in [0, 1)")
    require(maxPixels > 0, "raster budget must be positive")
    val physicalSize64 = math.round(font.size * deviceScale * 64).toInt
    require(physicalSize64 > 0, "raster size rounds to zero")
    Zone.acquire { implicit zone =>
      val out = alloc[Ptr[Byte]]()
      NativeFont.check("rasterize", TextShim.sg_text_raster(handle, glyphId.toUInt, physicalSize64,
        (subpixelX * 64).toInt, maxPixels, out))
      val result = !out
      try
        val meta = alloc[CInt](4)
        TextShim.sg_text_raster_meta(result, meta)
        val pixels = new Array[Byte](meta(0) * meta(1))
        val source = TextShim.sg_text_raster_pixels(result)
        if pixels.nonEmpty then System.arraycopy(source.raw.reinterpret(pixels.length).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),0,pixels,0,pixels.length)
        new GlyphRaster(font, glyphId, physicalSize64 / (font.size * 64), subpixelX,
          meta(0), meta(1), meta(2), meta(3), pixels)
      finally TextShim.sg_text_raster_free(result)
    }

  override def close(): Unit =
    checkThread()
    if handle != null then
      TextShim.sg_text_close(handle)
      handle = null

object NativeFont:
  private[text] def check(operation: String, status: Int): Unit =
    if status != 0 then throw new NativeTextError(operation, status)

  /** Opens immutable font bytes, retaining a private native copy until close.
    * Static scalable Unicode faces only; variable/color fonts are unsupported.
    * Logical size is quantized to 1/64 and the resolved size is reported.
    */
  def open(data: FontData, size: Double, faceIndex: Int = 0): NativeFont =
    require(data.byteSize <= 64 * 1024 * 1024, "font input exceeds 64 MiB limit")
    require(size.isFinite && size > 0 && size <= 4096, "font size must be in (0, 4096]")
    require(faceIndex >= 0, "face index must be nonnegative")
    val size64 = math.round(size * 64).toInt
    require(size64 > 0, "font size rounds to zero")
    Zone.acquire { implicit zone =>
      val bytes = data.bytesCopy
      val input = alloc[Byte](bytes.length)
      input.raw.copyFrom(java.lang.foreign.MemorySegment.ofArray(bytes))
      val out = alloc[Ptr[Byte]]()
      check("open", TextShim.sg_text_open(input, bytes.length, faceIndex, size64, out))
      val handle = !out
      try
        val count = alloc[CInt]()
        val upem = alloc[CInt]()
        val metrics = alloc[CDouble](3)
        val ftVersion = alloc[CInt](3)
        check("metrics", TextShim.sg_text_metrics(handle, count, upem, metrics))
        TextShim.sg_text_ft_version(handle, ftVersion)
        val resolved = new ResolvedFont(data, faceIndex, size64 / 64.0, !count, !upem,
          FontMetrics(metrics(0), metrics(1), metrics(2)),
          fromCString(TextShim.sg_text_hb_version()), s"${ftVersion(0)}.${ftVersion(1)}.${ftVersion(2)}")
        new NativeFont(handle, resolved)
      catch
        case error: Throwable =>
          TextShim.sg_text_close(handle)
          throw error
    }

private[text] object TextShim:
  def sg_text_hb_version(): CString = Calls.ptr[Byte]("sg_text_hb_version")
  def sg_text_ft_version(font: Ptr[Byte], out: Ptr[CInt]): Unit = Calls.unit("sg_text_ft_version", font, out)
  def sg_text_open(bytes: Ptr[Byte], length: CInt, index: CInt, size64: CInt, out: Ptr[Ptr[Byte]]): CInt = Calls.int("sg_text_open", bytes, length, index, size64, out)
  def sg_text_close(font: Ptr[Byte]): Unit = Calls.unit("sg_text_close", font)
  def sg_text_metrics(font: Ptr[Byte], count: Ptr[CInt], upem: Ptr[CInt], values: Ptr[CDouble]): CInt = Calls.int("sg_text_metrics", font, count, upem, values)
  def sg_text_shape(font: Ptr[Byte], bytes: Ptr[Byte], length: CInt, rtl: CInt,
    script: CString, language: CString, liga: CInt, start: CInt, end: CInt,
    contextStart: CInt, contextEnd: CInt, out: Ptr[Ptr[Byte]]): CInt = Calls.int("sg_text_shape", font, bytes, length, rtl, script, language, liga, start, end, contextStart, contextEnd, out)
  def sg_text_run_count(result: Ptr[Byte]): CInt = Calls.int("sg_text_run_count", result)
  def sg_text_carets(result: Ptr[Byte], index: CInt, values: Ptr[CDouble], count: Ptr[CInt]): CInt = Calls.int("sg_text_carets", result, index, values, count)
  def sg_text_glyph(result: Ptr[Byte], index: CInt, glyph: Ptr[CUnsignedInt], cluster: Ptr[CUnsignedInt],
    values: Ptr[CDouble], hasInk: Ptr[CInt]): CInt = Calls.int("sg_text_glyph", result, index, glyph, cluster, values, hasInk)
  def sg_text_run_free(result: Ptr[Byte]): Unit = Calls.unit("sg_text_run_free", result)
  def sg_text_raster(font: Ptr[Byte], glyph: CUnsignedInt, size64: CInt, phase64: CInt,
    maxPixels: CInt, out: Ptr[Ptr[Byte]]): CInt = Calls.int("sg_text_raster", font, glyph, size64, phase64, maxPixels, out)
  def sg_text_raster_meta(result: Ptr[Byte], out: Ptr[CInt]): Unit = Calls.unit("sg_text_raster_meta", result, out)
  def sg_text_raster_pixels(result: Ptr[Byte]): Ptr[Byte] = Calls.ptr[Byte]("sg_text_raster_pixels", result)
  def sg_text_raster_free(result: Ptr[Byte]): Unit = Calls.unit("sg_text_raster_free", result)
