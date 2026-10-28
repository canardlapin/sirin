package sirin.graphics.unicode

import sirin.graphics.*
import java.nio.file.{Files, Path as FilePath}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

class BidiLineSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def range(start: Int, end: Int): Utf8Range = Utf8Range(Utf8Offset(start), Utf8Offset(end))
  private def service[A](body: NativeUnicode => A): A =
    val engine = NativeUnicode.open()
    try body(engine) finally engine.close()

  test("all 91707 Unicode 18 BidiCharacterTest records: paragraph base, L1 levels, X9 and L2 source order") {
    val candidates = Vector(FilePath.of("probes/unicode/src/test/resources/unicode/BidiCharacterTest.txt"),
      FilePath.of("src/test/resources/unicode/BidiCharacterTest.txt"))
    val path = candidates.find(Files.exists(_)).getOrElse(fail("missing pinned BidiCharacterTest"))
    val records = Files.readAllLines(path).asScala.iterator.map(_.takeWhile(_ != '#').trim).filter(_.nonEmpty).toVector
    assertEquals(records.size, 91707)
    service { engine =>
      records.zipWithIndex.foreach { (record, index) =>
        val fields = record.split(";")
        val points = fields(0).trim.split("\\s+").map(Integer.parseInt(_, 16))
        val text = points.map(cp => new String(Character.toChars(cp))).mkString
        val direction = fields(1).trim.toInt match
          case 0 => ParagraphDirection.LeftToRight
          case 1 => ParagraphDirection.RightToLeft
          case _ => ParagraphDirection.Auto
        val analysis = engine.analyze(text, direction)
        assertEquals(analysis.paragraphs.size, 1, s"record $index paragraph count")
        val line = engine.resolveLine(analysis, range(0, analysis.source.byteSize))
        val expectedLevels = fields(3).trim.split("\\s+").map {
          case "x" => None
          case level => Some(level.toInt)
        }.toVector
        val expectedOrder = fields(4).trim.split("\\s+").filter(_.nonEmpty).map(_.toInt).toVector
        val logicalIndices = line.codepoints.map(_.range.start).zipWithIndex.toMap
        assertEquals(line.paragraphLevel, fields(2).trim.toInt, s"record $index base level")
        assertEquals(line.codepoints.map(_.level), expectedLevels, s"record $index L1/X9 levels")
        assertEquals(line.visualOrder.map(logicalIndices), expectedOrder, s"record $index L2 source indices")
      }
    }
  }

  test("a wrapped line resets trailing embedded whitespace without mutating paragraph levels") {
    service { engine =>
      val prefix = "a \u202Bאב  "
      val analysis = engine.analyze(prefix + "גד\u202C z")
      val end = TextSource(prefix).byteSize
      val line = engine.resolveLine(analysis, range(0, end))
      assertEquals(analysis.bidiLevels(end - 1), 1)
      assertEquals(line.codepoints.takeRight(2).map(_.level), Vector(Some(0), Some(0)))
      assertEquals(analysis.bidiLevels(end - 1), 1)
      assertEquals(line.codepoints.find(_.range.start.value == 2).get.level, None) // RLE, X9
      assert(!line.visualOrder.contains(Utf8Offset(2)))
      val empty = engine.resolveLine(analysis, range(end, end))
      assertEquals(empty.codepoints, Vector.empty)
      assertEquals(empty.visualOrder, Vector.empty)
      assertEquals(empty.paragraphLevel, 0)
    }
  }

  test("line analysis retains paragraph context and global UTF-8 codepoint ranges") {
    service { engine =>
      val source = engine.analyze("abc - אבג")
      val line = engine.resolveLine(source, range(4, source.source.byteSize))
      val standalone = engine.analyze("- אבג")
      val other = engine.resolveLine(standalone, range(0, standalone.source.byteSize))
      assert(line.analysis eq source)
      assertEquals(line.paragraphLevel, 0)
      assertEquals(other.paragraphLevel, 1)
      assertEquals(line.codepoints.head.level, Some(0))
      assertEquals(other.codepoints.head.level, Some(1))
      val supplementary = engine.analyze("א😀ב")
      val emojiLine = engine.resolveLine(supplementary, range(0, supplementary.source.byteSize))
      assertEquals(emojiLine.codepoints.map(_.range), Vector(range(0, 2), range(2, 6), range(6, 8)))
      assertEquals(emojiLine.visualOrder.map(_.value), Vector(6, 2, 0))
      val combining = engine.analyze("א😀\u0301ב")
      val combinedLine = engine.resolveLine(combining, range(0, combining.source.byteSize))
      assertEquals(combining.graphemeCount, 3)
      assertEquals(combinedLine.codepoints.size, 4) // L2 codepoints are not L3 glyph/grapheme order.
    }
  }

  test("paragraph selection, invalid ranges, source budgets and closed or other-thread use") {
    val engine = NativeUnicode.open()
    val analysis = engine.analyze("אב\nxyz")
    val second = engine.resolveLine(analysis, analysis.paragraphs(1).range, 1)
    assertEquals(second.paragraphLevel, 0)
    assertEquals(second.codepoints.head.range.start, analysis.paragraphs(1).range.start)
    intercept[IllegalArgumentException](engine.resolveLine(analysis, range(1, 2)))
    intercept[IllegalArgumentException](engine.resolveLine(analysis, range(0, analysis.source.byteSize)))
    intercept[IllegalArgumentException](engine.resolveLine(analysis, second.range, 0))
    intercept[IllegalArgumentException](engine.resolveLine(analysis, second.range, -1))
    intercept[IllegalArgumentException](engine.resolveLine(analysis, second.range, 2))
    intercept[IllegalArgumentException](engine.resolveLine(engine.analyze(""), range(0, 0)))
    val small = NativeUnicode.open(2)
    try intercept[IllegalArgumentException](small.resolveLine(analysis, second.range, 1))
    finally small.close()
    val error = new AtomicReference[Throwable]()
    val thread = new Thread(() =>
      try { engine.resolveLine(analysis, second.range, 1); () }
      catch case problem: Throwable => error.set(problem))
    thread.start()
    thread.join()
    assert(error.get().isInstanceOf[IllegalArgumentException])
    engine.close()
    assertEquals(second.visualOrder, second.codepoints.map(_.range.start))
    intercept[IllegalArgumentException](engine.resolveLine(analysis, second.range, 1))
  }
