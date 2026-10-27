package sirin

/** Pure value checks only. These do not claim an implemented transaction,
  * command, scope, rendering or streaming runtime.
  */
class AuthoringValuesSuite extends munit.FunSuite:
  test("revision zero and invalid construction") {
    assertEquals(Revision.initial.value, 0L)
    intercept[IllegalArgumentException](Revision(-1L))
  }
  test("revision exhaustion never wraps and monotonic merge never regresses") {
    val before = Revision(Long.MaxValue - 1)
    val last = before.next
    assert(last.isAfter(before))
    assertEquals(last.max(Revision.initial), last)
    intercept[ArithmeticException](last.next)
  }
  test("channel capacities reject zero and negative bounds") {
    intercept[IllegalArgumentException](Capacity(0))
    intercept[IllegalArgumentException](Capacity(-1))
    assertEquals(Capacity(1).value, 1)
  }
  test("task projections preserve explicit stale content and expose failures") {
    val previous = Completed("old", Outcome.Succeeded(42), current = true)
    val pending: TaskState[String, Int] = TaskState.Pending("new", Some(previous))
    assertEquals(pending.shown, Some(42))
    assert(pending.pending && pending.stale)
    val defect = new IllegalStateException("failed")
    val failed: TaskState[String, Int] = TaskState.Done(Completed("new", Outcome.Failed(defect), current = true))
    assertEquals(failed.shown, None)
    assertEquals(failed.error, Some(defect))
    assert(!failed.pending && !failed.stale)
    val recovered: TaskState[String, Int] = TaskState.Done(Completed("new", Outcome.Succeeded(43), current = true))
    assertEquals(recovered.shown, Some(43))
    assertEquals(recovered.error, None)
  }
