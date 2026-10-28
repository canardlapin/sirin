package sirin.desktop

class BenchmarkFixtureSuite extends munit.FunSuite:
  test("frozen2x fixture accepts repeated observations") {
    val fixture=new BenchmarkFixture
    assert(!fixture.stable)
    (1 to 3).foreach(_ => fixture.observe(BenchmarkGeometry(600,360,1200,720)))
    assert(fixture.stable);assertEquals(fixture.checks,3L)
  }
  test("1x display refuses qualification before sampling") {
    val fixture=new BenchmarkFixture
    val error=intercept[IllegalArgumentException](fixture.observe(BenchmarkGeometry(600,360,600,360)))
    assert(error.getMessage.contains("fixture changed"));assert(!fixture.stable)
  }
  test("resize refusal remains sticky after restoring the window") {
    val fixture=new BenchmarkFixture
    fixture.observe(fixture.expected)
    intercept[IllegalArgumentException](fixture.observe(BenchmarkGeometry(640,400,1280,800)))
    intercept[IllegalArgumentException](fixture.observe(fixture.expected))
    assert(!fixture.stable)
  }
