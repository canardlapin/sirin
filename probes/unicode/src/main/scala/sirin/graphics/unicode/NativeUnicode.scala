package sirin.graphics.unicode

import sirin.graphics.*
import scala.scalanative.unsafe.*

final class NativeUnicodeError(val operation: String, val status: Int)
    extends RuntimeException(s"Unicode $operation failed (native status $status)")

/** Isolated P0.8 service, not a production host dependency. Fixed Unicode-rule
  * segmentation (no dictionary tailoring) and raw paragraph bidi resolution.
  */
final class NativeUnicode private (private var handle: Ptr[Byte], val maxBytes: Int) extends AutoCloseable:
  private val owner = Thread.currentThread()
  private def checkThread(): Unit = require(Thread.currentThread() eq owner, "Unicode service used from another thread")
  private def checkOpen(): Unit =
    checkThread()
    require(handle != null, "Unicode service is closed")

  def analyze(text: String, direction: ParagraphDirection = ParagraphDirection.Auto): UnicodeAnalysis =
    checkOpen()
    require(text.length <= maxBytes, "text exceeds analysis code-unit budget")
    val source = TextSource(text)
    require(source.byteSize <= maxBytes, "text exceeds analysis UTF-8 budget")
    Zone.acquire { implicit zone =>
      val bytes = source.utf8Copy
      val input = alloc[Byte](math.max(1, bytes.length))
      bytes.indices.foreach(i => input(i) = bytes(i))
      val out = alloc[Ptr[Byte]]()
      val base = direction match
        case ParagraphDirection.Auto => -1
        case ParagraphDirection.LeftToRight => 0
        case ParagraphDirection.RightToLeft => 1
      NativeUnicode.check("analyze", UShim.su_analyze(handle, input, bytes.length, base, out))
      val result = !out
      try
        val counts = alloc[CInt](5)
        UShim.su_counts(result, counts)
        def ints(kind: Int, count: Int): Vector[Int] =
          require(count >= 0 && count <= (source.byteSize + 1) * 3, "invalid Unicode result count")
          val data = UShim.su_ints(result, kind)
          Vector.tabulate(count)(i => data(i))
        val graphemes = ints(0, counts(0)).map(Utf8Offset(_))
        val wordBoundaries = ints(1, counts(1)).map(Utf8Offset(_))
        val lines = ints(2, counts(2)).map(Utf8Offset(_))
        val types = ints(3, counts(1))
        val words = wordBoundaries.zip(wordBoundaries.drop(1)).zip(types.drop(1)).map { case ((start, end), kind) =>
          val wordKind = kind match
            case 0 => WordKind.NonWord
            case 1 => WordKind.Number
            case 2 => WordKind.Letter
            case _ => throw new NativeUnicodeError("word classification", 3)
          WordSegment(Utf8Range(start, end), wordKind)
        }
        require(counts(3) == source.byteSize, "invalid bidi byte length")
        val levelData = UShim.su_levels(result)
        val levels = Vector.tabulate(counts(3))(i => levelData(i).toInt & 0xff)
        val rawParagraphs = ints(4, counts(4) * 3)
        val paragraphs = rawParagraphs.grouped(3).map { p =>
          val classification = p(2) match
            case 0 => BidiDirectionClass.LeftToRight
            case 1 => BidiDirectionClass.RightToLeft
            case 2 => BidiDirectionClass.Mixed
            case _ => throw new NativeUnicodeError("paragraph direction", 3)
          BidiParagraph(Utf8Range(Utf8Offset(p(0)), Utf8Offset(p(1))), classification)
        }.toVector
        new UnicodeAnalysis(source, graphemes, wordBoundaries, words, lines, levels, paragraphs, direction,
          NativeUnicode.Engine, NativeUnicode.UnicodeVersion)
      finally UShim.su_result_free(result)
    }

  /** Apply L1/L2 to a caller-selected line without resolving the substring as a
    * new paragraph. Ranges are source-global UTF-8 codepoint boundaries. This
    * does not choose wrap points or perform shaping, mirroring or caret layout.
    */
  def resolveLine(analysis: UnicodeAnalysis, range: Utf8Range, paragraphIndex: Int = 0): BidiLine =
    checkOpen()
    require(analysis.engine == NativeUnicode.Engine && analysis.unicodeVersion == NativeUnicode.UnicodeVersion,
      "Unicode analysis belongs to a different engine/version")
    val source = analysis.source
    require(source.byteSize <= maxBytes, "text exceeds analysis UTF-8 budget")
    require(paragraphIndex >= 0 && paragraphIndex < analysis.paragraphs.size, "invalid bidi paragraph index")
    val paragraph = analysis.paragraphs(paragraphIndex).range
    require(range.start.value >= paragraph.start.value && range.end.value <= paragraph.end.value,
      "bidi line must be contained in its paragraph")
    require(source.utf16Offset(range.start).nonEmpty && source.utf16Offset(range.end).nonEmpty,
      "bidi line must use codepoint boundaries")
    Zone.acquire { implicit zone =>
      val bytes = source.utf8Copy
      val input = alloc[Byte](math.max(1, bytes.length))
      bytes.indices.foreach(i => input(i) = bytes(i))
      val out = alloc[Ptr[Byte]]()
      val base = analysis.requestedDirection match
        case ParagraphDirection.Auto => -1
        case ParagraphDirection.LeftToRight => 0
        case ParagraphDirection.RightToLeft => 1
      NativeUnicode.check("resolve line", UShim.su_line(handle, input, bytes.length, base, paragraphIndex,
        range.start.value, range.end.value, out))
      val result = !out
      try
        val counts = alloc[CInt](3)
        UShim.su_line_counts(result, counts)
        val capacity = range.end.value - range.start.value
        require(counts(0) >= 0 && counts(0) <= capacity && counts(1) >= 0 && counts(1) <= counts(0),
          "invalid bidi line counts")
        val logical = UShim.su_line_ints(result, 0)
        val codepoints = Vector.tabulate(counts(0)) { i =>
          val level = logical(i * 3 + 2)
          require(level >= -1 && level <= 126, "invalid bidi line level")
          BidiCodepoint(Utf8Range(Utf8Offset(logical(i * 3)), Utf8Offset(logical(i * 3 + 1))),
            if level == -1 then None else Some(level))
        }
        val order = UShim.su_line_ints(result, 1)
        val visual = Vector.tabulate(counts(1))(i => Utf8Offset(order(i)))
        new BidiLine(analysis, paragraphIndex, range, counts(2), codepoints, visual)
      finally UShim.su_line_free(result)
    }

  override def close(): Unit =
    checkThread()
    if handle != null then
      UShim.su_close(handle)
      handle = null

  /** ICU4X Script/Script_Extensions, bracket and ignorable properties. */
  def properties(source: TextSource): Vector[CodepointProperties] =
    checkOpen()
    require(source.byteSize <= maxBytes, "text exceeds Unicode property budget")
    Zone.acquire { implicit zone =>
      val out = alloc[CInt](5)
      val tags = alloc[CInt](64)
      def script(tag: Int): Script = Script(new String(Array(24, 16, 8, 0).map(shift => ((tag >>> shift) & 255).toChar)))
      val result = Vector.newBuilder[CodepointProperties]
      var utf16 = 0
      var byte = 0
      while utf16 < source.text.length do
        val cp = source.text.codePointAt(utf16)
        val size = if cp < 128 then 1 else if cp < 2048 then 2 else if cp < 65536 then 3 else 4
        NativeUnicode.check("properties", UShim.su_script_info(cp, out, tags))
        require(out(1) > 0 && out(1) <= 64, "invalid script extensions count")
        val bracket = out(2) match
          case 0 => None
          case 1 => Some(ScriptBracket(BracketKind.Open, out(3)))
          case 2 => Some(ScriptBracket(BracketKind.Close, out(3)))
          case _ => throw new NativeUnicodeError("bracket properties", 3)
        result += CodepointProperties(Utf8Range(Utf8Offset(byte), Utf8Offset(byte + size)), cp, script(out(0)),
          Vector.tabulate(out(1))(i => script(tags(i))), bracket, (out(4) & 1) != 0, (out(4) & 2) != 0)
        byte += size
        utf16 += Character.charCount(cp)
      result.result()
    }

  /** Upstream L2 for already resolved object/run levels. */
  def visualOrder(levels: Vector[Int]): Vector[Int] =
    checkOpen()
    require(levels.size <= 65536 && levels.forall(l => l >= 0 && l <= 126), "invalid object bidi levels/budget")
    Zone.acquire { implicit zone =>
      val input = alloc[Byte](math.max(1, levels.size))
      val out = alloc[CInt](math.max(1, levels.size))
      levels.indices.foreach(i => input(i) = levels(i).toByte)
      NativeUnicode.check("object order", UShim.su_order(handle, input, levels.size, out))
      val result = Vector.tabulate(levels.size)(i => out(i))
      require(result.sorted == levels.indices.toVector, "invalid object bidi permutation")
      result
    }

object NativeUnicode:
  private[unicode] val Engine = "ICU4X 2.4.0 C API + pinned Unicode 18 segmentation ABI"
  private[unicode] val UnicodeVersion = "18.0.0"
  private[unicode] def check(operation: String, status: Int): Unit =
    if status != 0 then throw new NativeUnicodeError(operation, status)

  def open(maxBytes: Int = 1048576): NativeUnicode =
    require(maxBytes > 0 && maxBytes <= 1048576, "Unicode byte budget must be in [1, 1048576]")
    Zone.acquire { implicit zone =>
      val out = alloc[Ptr[Byte]]()
      check("open", UShim.su_open(out))
      new NativeUnicode(!out, maxBytes)
    }

@extern
private[unicode] object UShim:
  @blocking def su_open(out: Ptr[Ptr[Byte]]): CInt = extern
  @blocking def su_close(handle: Ptr[Byte]): Unit = extern
  @blocking def su_analyze(handle: Ptr[Byte], text: Ptr[Byte], length: CInt, base: CInt, out: Ptr[Ptr[Byte]]): CInt = extern
  @blocking def su_result_free(result: Ptr[Byte]): Unit = extern
  def su_counts(result: Ptr[Byte], out: Ptr[CInt]): Unit = extern
  def su_ints(result: Ptr[Byte], kind: CInt): Ptr[CInt] = extern
  def su_levels(result: Ptr[Byte]): Ptr[Byte] = extern
  @blocking def su_line(handle: Ptr[Byte], text: Ptr[Byte], length: CInt, base: CInt,
    paragraph: CInt, start: CInt, end: CInt, out: Ptr[Ptr[Byte]]): CInt = extern
  @blocking def su_line_free(result: Ptr[Byte]): Unit = extern
  def su_line_counts(result: Ptr[Byte], out: Ptr[CInt]): Unit = extern
  def su_line_ints(result: Ptr[Byte], kind: CInt): Ptr[CInt] = extern
  def su_script_info(codepoint: CInt, out: Ptr[CInt], tags: Ptr[CInt]): CInt = extern
  @blocking def su_order(handle: Ptr[Byte], levels: Ptr[Byte], count: CInt, out: Ptr[CInt]): CInt = extern
