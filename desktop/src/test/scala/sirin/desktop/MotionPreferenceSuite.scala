package sirin.desktop

import scala.sys.process.*

/** The native Reduce Motion read agrees with the macOS accessibility defaults. Toggling the setting needs a person. */
class MotionPreferenceSuite extends munit.FunSuite:
  test("the Reduce Motion read matches the accessibility defaults") {
    val output = new StringBuilder
    val status = Process(Seq("defaults", "read", "com.apple.universalaccess", "reduceMotion")).!(
      ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n'))
    )
    val text = output.toString
    val expected =
      if status == 0 then
        text.trim == "1"
      else
        assume(text.contains("does not exist"), s"accessibility defaults are unreadable here: $text")
        false
    assertEquals(NativeApplication.reduceMotion(), expected)
  }
