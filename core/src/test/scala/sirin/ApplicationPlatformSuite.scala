package sirin

class ApplicationPlatformSuite extends munit.FunSuite:
  private def providers(values: ApplicationPlatform*): java.util.Iterator[ApplicationPlatform] =
    val list=new java.util.ArrayList[ApplicationPlatform]()
    values.foreach(list.add)
    list.iterator()
  private val backend = new ApplicationPlatform:
    def run(build: Scope => Windows): Unit = ()
  test("no installed backend is an explicit failure before application construction") {
    var built=false
    val error=intercept[IllegalStateException] {
      Application.run { _ => built=true; Windows.single("unused")(_ => Window("Unused",MenuBar.empty)(View.empty)) }
    }
    assert(error.getMessage.contains("No Sirin application backend"))
    assert(!built)
  }
  test("one provider is selected without constructing application state") {
    assert(Application.platform(providers(backend)) eq backend)
  }
  test("ambiguous providers are rejected rather than selected by classpath order") {
    val error=intercept[IllegalStateException](Application.platform(providers(backend,backend)))
    assert(error.getMessage.contains("Multiple Sirin application backends"))
  }

  test("a backend without application services refuses before invoking the service builder") {
    var built=false
    intercept[UnsupportedOperationException] {
      backend.runWithServices { (_,_) => built=true;Windows.single("unused")(_ => Window("Unused",MenuBar.empty)(View.empty)) }
    }
    assert(!built)
  }
