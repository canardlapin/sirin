package sirin.engine

import sirin.*
import scala.annotation.unused
import scala.concurrent.duration.*

class MotionSuite extends munit.FunSuite:
  private val standard = Transition(160.millis, Easing.Standard)
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")
  private def frame[A](r: HeadlessRuntime, presentation: Presentation[A]): A = r.present(presentation.current)
  private def transactions(r: HeadlessRuntime): Long = r.driverSnapshot.transactions

  /** Paint-only presentation reported through the text requests paint declares. */
  private class Lifted extends Element[Unit, Unit, Unit, Unit, Double]:
    def paint(
        @unused
        state: Unit,
        @unused
        presentation: Double,
        @unused
        size: Size,
        @unused
        canvas: Canvas
    )(using
        @unused
        c: Committed
    ): Unit = ()
    override def textRequests(
        @unused
        state: Unit,
        presentation: Double,
        @unused
        size: Size
    )(using
        @unused
        committed: Committed
    ): Seq[TextRequest] = Vector(TextRequest(f"$presentation%.3f", TextStyle.Body))

  test("a curve retargets from its current value and velocity, without snapping or queueing") {
    val curve = new MotionCurve(0, standard)
    curve.retarget(100, 0)
    assertEquals(curve.value(0), 0.0)
    assertEquals(curve.velocity(0), 0.0)
    assertEqualsDouble(curve.value(80.millis.toNanos), 50.0, 1e-9)
    assertEquals(curve.value(160.millis.toNanos), 100.0)
    val at = 60.millis.toNanos
    val before = curve.value(at)
    val speed = curve.velocity(at)
    assert(speed > 0)
    curve.retarget(-40, at)
    assertEqualsDouble(curve.value(at), before, 1e-9)
    assertEqualsDouble(curve.velocity(at), speed, 1e-12)
    assertEquals(curve.target, -40.0)
    assertEquals(curve.end, at + 160.millis.toNanos)
    assert(curve.value(at + 10.millis.toNanos) > before, "momentum carries through the retarget")
    assertEquals(curve.value(at + 160.millis.toNanos), -40.0)
    assert(!curve.moving(at + 160.millis.toNanos))
  }

  test("easings start from rest as declared and arrive exactly at the target") {
    val linear = new MotionCurve(0, Transition(100.millis, Easing.Linear))
    linear.retarget(10, 0)
    assertEqualsDouble(linear.value(25.millis.toNanos), 2.5, 1e-9)
    assertEqualsDouble(linear.value(75.millis.toNanos), 7.5, 1e-9)
    val decelerate = new MotionCurve(0, Transition(100.millis, Easing.Decelerate))
    decelerate.retarget(1, 0)
    assertEqualsDouble(decelerate.value(50.millis.toNanos), 0.75, 1e-9)
    assertEquals(decelerate.velocity(100.millis.toNanos), 0.0)
    assertEquals(decelerate.value(100.millis.toNanos), 1.0)
  }

  test("presented animations tick on the deterministic clock with frames but no writes or transactions") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val s = r.scope("motion")
      val open = s.cell(false)
      val position = s.animate(open.map(o => if o then 100.0 else 0.0), standard)
      assertEquals(frame(r, position), 0.0)
      assertEquals(r.animationWaitMillis, -1)
      ok(
        r.transact {
          open.set(true)
        }
      )
      val committed = transactions(r)
      assertEquals(r.activeAnimations, 1)
      assert(r.animationWaitMillis >= 0 && r.animationWaitMillis <= 17)
      clock.advanceBy(80.millis)
      assertEqualsDouble(frame(r, position), 50.0, 1e-9)
      assertEquals(r.animationWaitMillis, 17)
      clock.advanceBy(17.millis)
      assertEquals(r.animationWaitMillis, 0)
      clock.advanceBy(63.millis)
      assertEquals(frame(r, position), 100.0)
      assertEquals(r.animationWaitMillis, -1)
      assertEquals(r.activeAnimations, 0)
      assertEquals(transactions(r), committed, "ticks run outside transactions")
      assertEquals(r.withCommitted(_(open)), Value.Ok(true))
    finally r.close()
  }

  test("an animation the latest frame did not present settles instead of animating and schedules no frames") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val s = r.scope("motion")
      val open = s.cell(false)
      val hidden = s.animate(open.map(o => if o then 100.0 else 0.0), standard)
      val shown = s.animate(open.map(o => if o then 10.0 else 0.0), standard)
      assertEquals(frame(r, shown), 0.0)
      r.present(())
      ok(
        r.transact {
          open.set(true)
        }
      )
      assertEquals(r.animationWaitMillis, -1, "nothing on screen is animating")
      assertEquals(hidden.current, 100.0)
      assertEquals(shown.current, 10.0)
      assertEquals(frame(r, shown), 10.0)
      ok(
        r.transact {
          open.set(false)
        }
      )
      assertEquals(r.activeAnimations, 1)
      clock.advanceBy(40.millis)
      val partial = frame(r, shown)
      assert(partial > 0 && partial < 10)
      r.present(())
      clock.advanceBy(16.millis)
      assertEquals(frame(r, shown), 0.0, "content that missed a frame resumes at its target")
      assertEquals(r.animationWaitMillis, -1)
    finally r.close()
  }

  test("reduced motion reaches the target without frames, and turning it on settles animations in flight") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val s = r.scope("motion")
      val open = s.cell(false)
      val position = s.animate(open.map(o => if o then 100.0 else 0.0), standard)
      assertEquals(frame(r, position), 0.0)
      ok(
        r.transact {
          open.set(true)
        }
      )
      clock.advanceBy(40.millis)
      assert(frame(r, position) < 100)
      r.reducedMotion = true
      assertEquals(r.animationWaitMillis, -1)
      assertEquals(frame(r, position), 100.0)
      val committed = transactions(r)
      ok(
        r.transact {
          open.set(false)
        }
      )
      assertEquals(transactions(r), committed + 1, "only the application's own write commits")
      assertEquals(r.animationWaitMillis, -1)
      assertEquals(frame(r, position), 0.0)
      assertEquals(r.withCommitted(_(open)), Value.Ok(false))
    finally r.close()
  }

  test("an animation belongs to its scope; disposal stops its frames and a refused construction leaves none") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val owner = r.scope("owner")
      val s = owner.child("panel")
      val open = owner.cell(false)
      val position = s.animate(open.map(o => if o then 1.0 else 0.0), standard)
      assertEquals(frame(r, position), 0.0)
      ok(
        r.transact {
          open.set(true)
        }
      )
      assertEquals(r.activeAnimations, 1)
      ok(
        r.transact {
          s.dispose()
        }
      )
      r.present(())
      assertEquals(r.animationWaitMillis, -1)
      assertEquals(r.activeAnimations, 0)
      val refused =
        r.transact {
          owner.animate(open.map(o => if o then 2.0 else 3.0), standard)
          UiTxn.refuse("construction refused")
        }
      assert(refused.isInstanceOf[CommitResult.Refused[?]], s"$refused")
      ok(
        r.transact {
          open.set(false)
        }
      )
      r.present(())
      assertEquals(r.activeAnimations, 0)
      assertEquals(r.animationCount, 0)
    finally r.close()
  }

  test("element presentation is sampled per frame without new model revisions or rebuilt frames") {
    val clock = new ManualClock
    val r = new HeadlessRuntime(clock = clock)
    try
      val s = r.scope("element")
      val dragging = s.cell(false)
      val lift = s.animate(dragging.map(d => if d then 1.0 else 0.0), standard)
      val none = Signal.const(())
      val m = r.mount(s, Element("lifted", ElementInputs(none, none, none, none, lift), new Lifted))
      def mounted: ElementFrame =
        ok(
          r.transact {
            m.snapshot.get
          }
        ).asInstanceOf[MountedSnapshot.Custom].frame
      def lifted(f: ElementFrame): String = f.textRequests(Size(10.lp, 10.lp)).head.content
      val base = mounted
      val first = r.present(base.presented)
      assertEquals(first, base, "an unchanged sample keeps the frame")
      assertEquals(lifted(first), "0.000")
      ok(
        r.transact {
          dragging.set(true)
        }
      )
      val settled = mounted
      clock.advanceBy(80.millis)
      val (a, b) = r.present((settled.presented, settled.presented))
      assert(a eq b, "one sample per frame shares one frame")
      assertEquals(lifted(a), "0.500")
      assertEquals(a.revisions, settled.revisions)
      assertEquals(a.layoutIdentity, settled.layoutIdentity)
      assertEquals(a.instance, settled.instance)
      clock.advanceBy(80.millis)
      assertEquals(lifted(r.present(settled.presented)), "1.000")
      assertEquals(r.animationWaitMillis, -1)
    finally r.close()
  }
