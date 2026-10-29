package sirin.engine

import sirin.*
import scala.concurrent.duration.*

class HostBoundarySuite extends munit.FunSuite:
  test("committed reader expires, rejects undeclared nodes and cannot mutate the graph") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("host");val value=owner.cell(1)
      var escaped: CommittedRead=null
      r.withCommitted { reader => escaped=reader; assertEquals(reader(value),Value.Ok(1)) }
      intercept[KernelError](escaped(value))
      val undeclared=value.map(_+1)
      intercept[KernelError](r.withCommitted(reader => reader(undeclared)))
      intercept[KernelError](r.withCommitted(_ => r.transact { value.set(2) }))
      assertEquals(r.read(value),Value.Ok(1))
    finally r.close()
  }
  test("backend wait uses runtime clock origin, ceiling and ready work") {
    val clock=new ManualClock(1000)
    val r=new HeadlessRuntime(clock=clock)
    try
      val owner=r.scope("host");val value=owner.cell(0)
      owner.debounce(value,150.millis)
      assertEquals(r.activityWaitMillis,-1)
      r.transact { value.set(1) }
      assertEquals(r.activityWaitMillis,150)
      clock.advanceBy(149.millis)
      assertEquals(r.activityWaitMillis,1)
      clock.advanceBy(1.millis)
      assertEquals(r.activityWaitMillis,0)
      r.drive(8)
      assertEquals(r.activityWaitMillis,-1)
    finally r.close()
  }
  test("wake callback is outside the transport lock, guarded, detachable and failure-accounted") {
    val clock=new ManualClock
    val r=new HeadlessRuntime(clock=clock)
    try
      val owner=r.scope("host");val value=owner.cell(0)
      var calls=0
      val subscription=r.onActivity(() => { calls+=1; () })
      clock.advanceBy(1.millis)
      assertEquals(calls,1)
      subscription.cancel();clock.advanceBy(1.millis)
      assertEquals(calls,1)
      val invalid=r.onActivity(() => { r.transact { value.set(2) }; () })
      clock.advanceBy(1.millis)
      assertEquals(r.wakeFailures,1L)
      assertEquals(r.read(value),Value.Ok(0))
      invalid.cancel()
    finally r.close()
  }
  test("activity clears host wake registrations before retained workers can signal again") {
    val activity=new Activity
    var calls=0
    activity.onWake(() => calls+=1)
    activity.signal();assertEquals(calls,1)
    activity.clearWakes();activity.signal();assertEquals(calls,1)
  }
