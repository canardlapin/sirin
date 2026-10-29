package sirin.graphics.unicode

import sirin.graphics.*
import java.nio.file.{Files, Path as FilePath}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

class UnicodeSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def service[A](body: NativeUnicode => A): A =
    val engine = NativeUnicode.open()
    try body(engine) finally engine.close()

  private def fixture(name: String): Vector[String] =
    val candidates = Vector(FilePath.of("probes/unicode/src/test/resources/unicode"), FilePath.of("src/test/resources/unicode"))
    val path = candidates.map(_.resolve(name)).find(Files.exists(_)).getOrElse(fail(s"missing Unicode fixture: $name"))
    Files.readAllLines(path).asScala.toVector

  private def cases(name: String): Vector[(String, Vector[Int])] =
    fixture(name).map(_.takeWhile(_ != '#').trim).filter(_.nonEmpty).map { line =>
      val text = new StringBuilder
      val boundaries = Vector.newBuilder[Int]
      var bytes = 0
      line.split("\\s+").foreach {
        case "÷" => boundaries += bytes
        case "×" => ()
        case value =>
          val cp = Integer.parseInt(value, 16)
          text.append(new String(Character.toChars(cp)))
          bytes += (if cp < 0x80 then 1 else if cp < 0x800 then 2 else if cp < 0x10000 then 3 else 4)
      }
      // ICU iterators include structural start 0; UAX #14 marks it × (LB2).
      (text.result(), (Vector(0) ++ boundaries.result()).distinct)
    }

  test("Unicode 18 grapheme reference corpus") {
    service { engine =>
      val data = cases("GraphemeBreakTest.txt")
      assertEquals(data.size, 853)
      data.zipWithIndex.foreach { case ((text, expected), index) =>
        assertEquals(engine.analyze(text).graphemeBoundaries.map(_.value), expected, s"grapheme case $index")
      }
    }
  }

  test("Unicode 18 word reference corpus with default rules, no dictionary tailoring") {
    service { engine =>
      val data = cases("WordBreakTest.txt")
      assertEquals(data.size, 1944)
      data.zipWithIndex.foreach { case ((text, expected), index) =>
        assertEquals(engine.analyze(text).wordBoundaries.map(_.value), expected, s"word case $index")
      }
    }
  }

  test("Unicode 18 strict line reference corpus with structural start sentinel") {
    service { engine =>
      val data = cases("LineBreakTest.txt")
      assertEquals(data.size, 19346)
      data.zipWithIndex.foreach { case ((text, expected), index) =>
        assertEquals(engine.analyze(text).lineBoundaries.map(_.value), expected, s"line case $index")
      }
    }
  }

  test("raw bidi levels match the first 16 UAX9 neutral/isolate reference examples") {
    // Raw BidiInfo levels are before line-dependent L1/L2. This fixed reference
    // prefix exercises resolution without asserting complete UAX9 line layout.
    val examples = fixture("BidiCharacterTest.txt").map(_.takeWhile(_ != '#').trim).filter(_.nonEmpty).take(16)
    assertEquals(examples.size, 16)
    service { engine =>
      examples.zipWithIndex.foreach { case (line, index) =>
        val fields = line.split(";")
        val points = fields(0).trim.split("\\s+").map(Integer.parseInt(_, 16))
        val text = points.map(cp => new String(Character.toChars(cp))).mkString
        val direction = fields(1).trim.toInt match
          case 0 => ParagraphDirection.LeftToRight
          case 1 => ParagraphDirection.RightToLeft
          case _ => ParagraphDirection.Auto
        val expected = fields(3).trim.split("\\s+")
        val analysis = engine.analyze(text, direction)
        var offset = 0
        points.indices.foreach { i =>
          if expected(i) != "x" then assertEquals(analysis.bidiLevels(offset), expected(i).toInt, s"bidi case $index / $i")
          offset += (if points(i) < 0x80 then 1 else if points(i) < 0x800 then 2 else if points(i) < 0x10000 then 3 else 4)
        }
      }
    }
  }

  test("graphemes preserve emoji ZWJ, modifiers, flags, combining marks and CRLF") {
    service { engine =>
      val analysis = engine.analyze("A👩🏽‍💻🇨🇦e\u0301\r\n")
      assertEquals(analysis.graphemeCount, 5)
      assertEquals(analysis.graphemeUtf16Boundaries.map(_.value), Vector(0, 1, 8, 12, 14, 16))
      assert(!analysis.isGraphemeBoundary(Utf8Offset(2)))
      assert(analysis.isGraphemeBoundary(Utf8Offset(analysis.source.byteSize)))
      assertEquals(analysis.graphemeRange(GraphemeIndex(0)), Some(Utf8Range(Utf8Offset(0), Utf8Offset(1))))
      assertEquals(analysis.graphemeRange(GraphemeIndex(5)), None)
    }
  }

  test("word categories, mixed direction and source paragraph ranges remain explicit") {
    service { engine =>
      val words = engine.analyze("abc 123").words
      assertEquals(words.map(_.kind), Vector(WordKind.Letter, WordKind.NonWord, WordKind.Number))
      val mixed = engine.analyze("abc אבג\nمرحبا")
      assertEquals(mixed.paragraphs.size, 2)
      assertEquals(mixed.paragraphs.head.direction, BidiDirectionClass.Mixed)
      assertEquals(mixed.paragraphs.last.direction, BidiDirectionClass.RightToLeft)
      assert(mixed.bidiLevels.exists(_ % 2 == 1))
      assertEquals(mixed.paragraphs.last.range.end.value, mixed.source.byteSize)
    }
  }

  test("empty analysis and copied results survive explicit engine closure") {
    val engine = NativeUnicode.open()
    val empty = engine.analyze("")
    val saved = engine.analyze("x\u0301")
    engine.close()
    engine.close()
    assertEquals(empty.graphemeBoundaries.map(_.value), Vector(0))
    assertEquals(empty.paragraphs, Vector.empty)
    assertEquals(saved.graphemeCount, 1)
    intercept[IllegalArgumentException](engine.analyze("late"))
  }

  test("invalid Unicode, byte budgets and other-thread access are rejected") {
    val engine = NativeUnicode.open(4)
    try
      intercept[IllegalArgumentException](engine.analyze("12345"))
      intercept[IllegalArgumentException](engine.analyze("ééé"))
      intercept[IllegalArgumentException](engine.analyze("\uD800"))
      val result = new AtomicReference[Throwable]()
      val thread = new Thread(() =>
        try { engine.analyze("a"); () }
        catch case error: Throwable => result.set(error))
      thread.start()
      thread.join()
      assert(result.get().isInstanceOf[IllegalArgumentException])
    finally engine.close()
    intercept[IllegalArgumentException](NativeUnicode.open(0))
  }
