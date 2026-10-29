package sirin.graphics

import java.nio.file.{Files, Path as FilePath}

class TextDataSuite extends munit.FunSuite:
  test("typed UTF-8 clusters convert only at valid codepoint boundaries") {
    val source = TextSource("A😀e\u0301")
    assertEquals(source.byteSize, 8)
    for (utf8, utf16) <- Vector((0, 0), (1, 1), (5, 3), (6, 4), (8, 5)) do
      assertEquals(source.utf16Offset(Utf8Offset(utf8)).map(_.value), Some(utf16))
      assertEquals(source.utf8Offset(Utf16Offset(utf16)).map(_.value), Some(utf8))
    for offset <- Vector(2, 6, 999) do
      assertEquals(source.utf8Offset(Utf16Offset(offset)), None)
    for offset <- Vector(2, 3, 4, 7, 9) do
      assertEquals(source.utf16Offset(Utf8Offset(offset)), None)
    val copy = source.utf8Copy
    copy(0) = 0
    assertEquals(source.utf8Copy(0), 'A'.toByte)
    assertEquals(TextSource("").utf16Offset(Utf8Offset(0)).map(_.value), Some(0))
  }

  test("invalid Unicode and negative typed offsets are rejected") {
    intercept[IllegalArgumentException](TextSource("\uD800"))
    intercept[IllegalArgumentException](TextSource("\uDC00"))
    intercept[IllegalArgumentException](TextSource("\uD800x"))
    intercept[IllegalArgumentException](Utf8Offset(-1))
    intercept[IllegalArgumentException](Utf16Offset(-1))
  }

  test("real font bytes are snapshotted independently of imports and exports") {
    val candidates = Vector(FilePath.of("graphics/src/test/resources/fonts/NotoSans-Regular.ttf"),
      FilePath.of("src/test/resources/fonts/NotoSans-Regular.ttf"))
    val path = candidates.find(Files.exists(_)).getOrElse(fail("missing pinned font fixture"))
    val source = Files.readAllBytes(path)
    val original = source.toVector
    val data = FontData.copy(source)
    source(0) = 127
    val exported = data.bytesCopy
    exported(0) = 127
    assertEquals(data.bytesCopy.toVector, original)
    assertEquals(data.byteSize, original.size)
    intercept[IllegalArgumentException](FontData.copy(Array.emptyByteArray))
  }

  test("explicit script/direction options reject invalid language tags") {
    val options = ShapingOptions(Script.Arabic, TextDirection.RightToLeft, "ar")
    assertEquals(options.script.tag, "Arab")
    assertEquals(Script("Deva").tag, "Deva")
    intercept[IllegalArgumentException](Script("deva"))
    intercept[IllegalArgumentException](Script("D3va"))
    intercept[IllegalArgumentException](Script(""))
    intercept[IllegalArgumentException](options.copy(language = ""))
    intercept[IllegalArgumentException](options.copy(language = "en\u0000other"))
  }
