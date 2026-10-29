package sirin

import scala.compiletime.testing.typeChecks

class TextApiSuite extends munit.FunSuite:
  test("text requests still need the read-only phase capability and caret calls require affinity") {
    assert(!typeChecks("import sirin.*; def request(s: TextServices) = s.layout(\"text\", TextStyle.Body, 100.lp)"))
    assert(typeChecks("import sirin.*; def request(s: TextServices)(using Committed) = s.layout(\"text\", TextStyle.Body, 100.lp)"))
    assert(!typeChecks("import sirin.*; def caret(l: TextLayout) = l.caret(0)"))
    assert(typeChecks("import sirin.*; def caret(l: TextLayout) = l.caret(0, TextAffinity.Downstream)"))
  }

  test("text positions reject negative UTF-16 offsets") {
    intercept[IllegalArgumentException](TextPosition(-1, TextAffinity.Upstream))
    assertEquals(TextPosition(0, TextAffinity.Downstream).utf16Offset, 0)
  }
