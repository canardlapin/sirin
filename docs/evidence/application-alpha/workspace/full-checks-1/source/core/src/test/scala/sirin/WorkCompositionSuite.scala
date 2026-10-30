package sirin

class WorkCompositionSuite extends munit.FunSuite:
  test("composition is lazy, sequential and shares one cancellation context") {
    var log=Vector.empty[String]
    val token=new Cancellation {def cancelled=false}
    val first=Work {assert(summon[Cancellation] eq token);log=log:+"first";3}
    val all=first.flatMap(n => Work {assert(summon[Cancellation] eq token);log=log:+"second";n+1}).map(_*2)
    assert(log.isEmpty);assertEquals(Work.execute(all,token),8);assertEquals(log,Vector("first","second"))
  }
  test("cancellation stops composition before its first or next step") {
    var calls=0
    val token=new Cancellation {def cancelled=WorkCompositionSuite.this.stop}
    val first=Work {calls+=1;stop=true;1}
    val all=first.flatMap(_ => {calls+=100;Work {calls+=1000;2}})
    def stopped(work: Work[Int]): Unit =
      var caught=false
      try Work.execute(work,token) catch case _: InterruptedException => caught=true
      assert(caught)
    stop=false;stopped(all);assertEquals(calls,1)
    stopped(all);assertEquals(calls,1)
  }
  private var stop=false
  test("attempt keeps explicit failures and never catches cooperative interruption") {
    val token=new Cancellation {def cancelled=false}
    val error=new IllegalStateException("failure")
    assertEquals(Work.execute(Work[Int] {throw error}.attempt,token),Left(error))
    var caught=false
    try Work.execute(Work[Int] {throw new InterruptedException("cancel")}.attempt,token)
    catch case _: InterruptedException => caught=true
    assert(caught)
  }
