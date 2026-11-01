package sirin.desktop

class AccessibilityAbiSuite extends munit.FunSuite:
  test("FFM reaches every entry in the same accessibility ABI as the Native probe without a window") {
    assertEquals(NativeApplication.liveApplications(), 0)
    assertEquals(NativeWindow.liveWindows(), 0)
    NativeWindow.verifyAccessibilityAbi()
    assertEquals(NativeApplication.liveApplications(), 0)
    assertEquals(NativeWindow.liveWindows(), 0)
  }
