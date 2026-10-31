package sirin.engine

import scala.collection.mutable
import sirin.*

/** `View.live` children and `Layout.reporting` viewport sinks. */
class LiveContentSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")
  private def read[A](r: HeadlessRuntime, s: Signal[A]): A =
    r.read(s) match
      case Value.Ok(v) =>
        v
      case Value.Failed(e) =>
        throw e

  test("live content keeps a static child's identity across descriptions and remounts on a new kind or key") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("live")
      var builds = 0
      val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
      val piece = Component[String]("piece") { (scope, _) =>
        builds += 1
        drafts += scope.cell(0)
        Spacer(10.lp)
      }
      val frame = Component[View]("frame")((_, content) => View.live(content))
      val m = r.mount(owner, frame(piece("a")))
      r.observe(m.snapshot)(_ => ())
      assertEquals(builds, 1)
      ok(r.transact(drafts.head.set(7)))
      ok(r.transact(m.update(frame(piece("b")))))
      assertEquals(builds, 1, "a new description of the same component delivers props")
      assertEquals(read(r, drafts.head), 7)
      read(r, m.snapshot) match
        case MountedSnapshot.Component(_, _, MountedSnapshot.Component(_, props, _)) =>
          assertEquals(props, "b")
        case other =>
          fail(s"live content adds no node of its own: $other")
      ok(r.transact(m.update(frame(piece("c").keyed("k")))))
      assertEquals(builds, 2, "a new key remounts")
      assertEquals(r.transact(drafts.head.set(1)), CommitResult.Failed(KernelError.DisposedScope))
      ok(r.transact(m.update(frame(Spacer(5.lp)))))
      ok(r.transact(m.update(frame(piece("d").keyed("k")))))
      assertEquals(builds, 3, "a different kind in between retires the child")
    finally r.close()
  }

  test("failed live content is unavailable, and recovery mounts fresh state") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("live")
      val divisor = owner.cell(1)
      val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
      val piece = Component[Int]("piece") { (scope, _) =>
        drafts += scope.cell(0)
        View.empty
      }
      val content = divisor.map(d => piece(10 / d))
      val frame = Component[Unit]("frame")((_, _) => View.live(content))
      val m = r.mount(owner, frame(()))
      r.observe(m.snapshot)(_ => ())
      assertEquals(drafts.size, 1)
      ok(r.transact(divisor.set(0)))
      read(r, m.snapshot) match
        case MountedSnapshot.Component(_, _, MountedSnapshot.Unavailable(_)) =>
          ()
        case other =>
          fail(s"expected unavailable live content: $other")
      assertEquals(r.transact(drafts.head.set(1)), CommitResult.Failed(KernelError.DisposedScope))
      ok(r.transact(divisor.set(2)))
      assertEquals(drafts.size, 2)
    finally r.close()
  }

  test("a reporting layout receives its host's viewport reports until it is unmounted") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("report")
      val sink = owner.cell(Viewport.empty)
      val policy =
        new LayoutPolicy[Unit]:
          def layout(state: Unit, c: Constraints, children: LayoutChildren)(using Committed): Size = c.smallest
      val m = r.mount(owner, Layout.reporting("panel", Signal.const(()), policy, sink)())
      r.observe(m.snapshot)(_ => ())
      val (instance, reports) =
        read(r, m.snapshot) match
          case MountedSnapshot.CustomLayout(frame, _) =>
            (frame.instance, frame.reportsViewport)
          case other =>
            fail(s"expected a layout: $other")
      assert(reports)
      val shown = Viewport(Size(40.lp, 30.lp), Rect(Point(0.lp, 5.lp), Size(40.lp, 20.lp)), Size(40.lp, 90.lp))
      ok(r.transact(m.reportViewport(instance, shown)))
      assertEquals(read(r, sink), shown)
      ok(r.transact(m.update(View.empty)))
      assertEquals(
        r.transact(m.reportViewport(instance, Viewport.empty)),
        CommitResult.Refused("reporting layout is no longer mounted")
      )
      assertEquals(read(r, sink), shown)
      intercept[IllegalArgumentException](Viewport(Size(Lp(-1), Lp.zero), Rect(Point.origin, Size(Lp.zero, Lp.zero)), Size(Lp.zero, Lp.zero)))
    finally r.close()
  }
