package sirin.engine

import sirin.*
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.unused

class ElementSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")
  private def snapshot(r: HeadlessRuntime, m: MountedView): MountedSnapshot = ok(
    r.transact {
      m.snapshot.get
    }
  )
  private def custom(r: HeadlessRuntime, m: MountedView): MountedSnapshot.Custom = snapshot(r, m).asInstanceOf[
    MountedSnapshot.Custom
  ]
  private class Surface extends Element[Int, Int, Int, Int, Int]:
    def paint(
        @unused
        state: Int,
        @unused
        presentation: Int,
        @unused
        size: Size,
        @unused
        canvas: Canvas
    )(using
        @unused
        c: Committed
    ): Unit = ()

  test("settled phases change independently, preserve instance and skip unchanged snapshots") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("element")
      val l = s.cell(1)
      val p = s.cell(2)
      val h = s.cell(3)
      val a = s.cell(4)
      val presentation = Presentation.const(7).map(_ + 1)
      val definition = new Surface
      val m = r.mount(s, Element("surface", ElementInputs(l, p, h, a, presentation), definition))
      val first = custom(r, m)
      assertEquals(first.frame.revisions, Vector.fill(5)(1L))
      ok(
        r.transact {
          p.set(20)
        }
      )
      val second = custom(r, m)
      assertEquals(second.frame.instance, first.frame.instance)
      assertEquals(second.frame.revisions, Vector(1L, 2L, 1L, 1L, 1L))
      ok(
        r.transact {
          p.set(20)
        }
      )
      assert(custom(r, m).frame eq second.frame)
      ok(
        r.transact {
          l.set(10)
          h.set(30)
          a.set(40)
        }
      )
      assertEquals(custom(r, m).frame.revisions, Vector(2L, 2L, 2L, 2L, 1L))
    finally
      r.close()
  }
  test("owner handlers publish transactionally and a removed instance refuses stale dispatch") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("element")
      val count = s.cell(0)
      val definition =
        new Surface:
          override def key(
              @unused
              event: KeyEvent
          )(using UiTxn): Boolean = {
            count.set(count.get + 1)
            true
          }
      val m = r.mount(
        s,
        Element("surface", ElementInputs(count, count, count, count, Presentation.const(0)), definition)
      )
      val binding = custom(r, m).binding
      assert(
        ok(
          r.transact {
            binding.key(KeyEvent(Key.Enter, Mods.none, false))
          }
        )
      )
      assertEquals(
        ok(
          r.transact {
            count.get
          }
        ),
        1
      )
      ok(
        r.transact {
          m.update(View.empty)
        }
      )
      assertEquals(
        r.transact {
          binding.key(KeyEvent(Key.Enter, Mods.none, false))
        },
        CommitResult.Failed(KernelError.DisposedScope)
      )
      assertEquals(
        ok(
          r.transact {
            count.get
          }
        ),
        1
      )
    finally
      r.close()
  }
  test("caught owner mutation poisons a pure phase and does not change the model") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("element")
      val count = s.cell(0)
      val definition =
        new Surface:
          override def measure(
              @unused
              value: Int,
              c: Constraints
          )(using
              @unused
              committed: Committed
          ): Size =
            try
              r.transact {
                count.set(100)
              }
            catch
              case _: Throwable =>
                ()
            c.biggest
      val m = r.mount(
        s,
        Element("surface", ElementInputs(count, count, count, count, Presentation.const(0)), definition)
      )
      val frame = custom(r, m).frame
      assertEquals(
        intercept[KernelError](frame.measure(Constraints(0.lp, 40.lp, 0.lp, 30.lp))),
        KernelError.ReadOnlyPhase
      )
      assertEquals(
        ok(
          r.transact {
            count.get
          }
        ),
        0
      )
    finally
      r.close()
  }
  test("caught cross-thread UI access poisons a phase on a Scala-created worker") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("element")
      val count = s.cell(0)
      val definition =
        new Surface:
          override def measure(
              @unused
              value: Int,
              c: Constraints
          )(using
              @unused
              committed: Committed
          ): Size =
            try
              r.read(count)
            catch
              case _: Throwable =>
                ()
            c.biggest
      val m = r.mount(
        s,
        Element("surface", ElementInputs(count, count, count, count, Presentation.const(0)), definition)
      )
      val frame = custom(r, m).frame
      val outcome = new AtomicReference[Throwable]()
      val thread =
        new Thread(() =>
          try {
            frame.measure(Constraints(0.lp, 40.lp, 0.lp, 30.lp))
            ()
          } catch
            case error: Throwable =>
              outcome.set(error)
        )
      thread.start()
      thread.join()
      assertEquals(outcome.get(), KernelError.WrongThread)
      assertEquals(
        ok(
          r.transact {
            count.get
          }
        ),
        0
      )
    finally
      r.close()
  }
  test("named layouts retain children through reorder and roll back enclosing refusal") {
    val r = new HeadlessRuntime
    try
      val s = r.scope("layout")
      val state = s.cell(1)
      val draft = s.cell("")
      val policy =
        new LayoutPolicy[Int]:
          def layout(
              @unused
              state: Int,
              c: Constraints,
              @unused
              children: LayoutChildren
          )(using
              @unused
              committed: Committed
          ): Size = c.biggest
      def view(reverse: Boolean) =
        sirin
          .Layout
          .named("slots", state, policy)(
            (
              if reverse then
                Vector(Slot("b") -> Button("b"), Slot("a") -> TextField.bind(draft))
              else
                Vector(Slot("a") -> TextField.bind(draft), Slot("b") -> Button("b"))
            )*
          )
      val m = r.mount(s, view(false))
      val first = snapshot(r, m).asInstanceOf[MountedSnapshot.CustomLayout]
      ok(
        r.transact {
          m.update(view(true))
        }
      )
      val next = snapshot(r, m).asInstanceOf[MountedSnapshot.CustomLayout]
      assert(next.frame eq first.frame)
      assertEquals(
        next
          .children
          .map((slot, child) =>
            slot -> (
              child match
                case MountedSnapshot.Control(id, _) =>
                  id
                case MountedSnapshot.Custom(frame, _) =>
                  frame.instance
                case other =>
                  fail(s"unexpected child: $other")
            )
          ),
        first
          .children
          .reverse
          .map((slot, child) =>
            slot -> (
              child match
                case MountedSnapshot.Control(id, _) =>
                  id
                case MountedSnapshot.Custom(frame, _) =>
                  frame.instance
                case other =>
                  fail(s"unexpected child: $other")
            )
          )
      )
      assertEquals(
        r.transact {
          m.update(View.empty)
          UiTxn.refuse("keep")
        },
        CommitResult.Refused("keep")
      )
      assertEquals(snapshot(r, m), next)
    finally
      r.close()
  }

  test("single-state elements invalidate every phase and keep their mounted identity") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("simple")
      val state = owner.cell(1)
      val definition =
        new Element.Simple[Int]:
          def paint(
              @unused
              state: Int,
              @unused
              size: Size,
              @unused
              canvas: Canvas
          )(using
              @unused
              c: Committed
          ): Unit = ()
      val mounted = runtime.mount(owner, Element.simple("simple", state, definition))
      val before = custom(runtime, mounted)
      ok(
        runtime.transact {
          state.set(2)
        }
      )
      val after = custom(runtime, mounted)
      assertEquals(after.frame.instance, before.frame.instance)
      assertEquals(after.frame.revisions, Vector(2L, 2L, 2L, 2L, 1L))
    finally
      runtime.close()
  }

  test("a paint-only change reuses measurement identity, while a layout change replaces it") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("phases")
      val layout = owner.cell(1)
      val paint = owner.cell(2)
      val mounted = runtime.mount(
        owner,
        Element("phases", ElementInputs(layout, paint, layout, layout, Presentation.const(0)), new Surface)
      )
      val before = custom(runtime, mounted).frame
      ok(
        runtime.transact {
          paint.set(3)
        }
      )
      val recolored = custom(runtime, mounted).frame
      assert(recolored.layoutIdentity eq before.layoutIdentity)
      ok(
        runtime.transact {
          layout.set(4)
        }
      )
      assert(!(custom(runtime, mounted).frame.layoutIdentity eq before.layoutIdentity))
    finally
      runtime.close()
  }
