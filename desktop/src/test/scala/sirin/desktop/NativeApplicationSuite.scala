package sirin.desktop

class NativeApplicationSuite extends munit.FunSuite:
  test("application library loads without creating a native application or window") {
    assertEquals(NativeApplication.liveApplications(),0)
    assertEquals(NativeWindow.liveWindows(),0)
  }
  test("non-main-thread application launch fails before allocating SDL ownership") {
    assert(!NativeWindow.firstThread())
    intercept[IllegalStateException](new NativeApplication)
    assertEquals(NativeApplication.liveApplications(),0)
    assertEquals(NativeWindow.liveWindows(),0)
  }
  test("public launch discovers the JVM provider and rejects a test-thread launch before build") {
    var built=false
    val error=intercept[IllegalStateException] {
      sirin.Application.run { _ => built=true;throw new IllegalStateException("builder must not run") }
    }
    assert(error.getMessage.contains("XstartOnFirstThread"))
    assert(!built)
    assertEquals(NativeApplication.liveApplications(),0)
  }
