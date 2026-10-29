package sirin.desktop

class NativeAbiSuite extends munit.FunSuite:
  test("FFM pointer-only C compiler layout and by-value color round trip") {
    NativeWindow.verifyAbi()
    assertEquals(NativeWindow.liveWindows(), 0)
  }
  test("window creation rejects a JVM test worker outside the process main thread") {
    assert(!NativeWindow.firstThread())
    intercept[IllegalStateException](new NativeWindow("wrong owner",800,520))
    assertEquals(NativeWindow.liveWindows(),0)
  }
