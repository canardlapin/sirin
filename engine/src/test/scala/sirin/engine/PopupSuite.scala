package sirin.engine

import scala.collection.mutable
import sirin.*

/** A popup's content is mounted under its originator only while it is open. */
class PopupSuite extends munit.FunSuite:
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

  test("content mounts while open, is disposed on close, takes fresh state on reopen and dies with its originator") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("popup")
      val drafts = mutable.ArrayBuffer.empty[Cell[Int]]
      val piece = Component[Unit]("piece") { (scope, _) =>
        drafts += scope.cell(0)
        View.empty
      }
      var open = Option.empty[Cell[Boolean]]
      val originator = Component[Unit]("originator") { (scope, _) =>
        val shown = scope.cell(false)
        open = Some(shown)
        val anchor = scope.ref()
        Column(Button("anchor").ref(anchor), Popup(shown, anchor)(piece.view))
      }
      val m = r.mount(owner, originator.view)
      r.observe(m.snapshot)(_ => ())
      def popups(s: MountedSnapshot): Vector[MountedSnapshot.Popup] =
        s match
          case p: MountedSnapshot.Popup =>
            Vector(p)
          case MountedSnapshot.Component(_, _, child) =>
            popups(child)
          case MountedSnapshot.CustomLayout(_, children) =>
            children.flatMap((_, child) => popups(child))
          case MountedSnapshot.Referenced(_, child) =>
            popups(child)
          case _ =>
            Vector.empty
      assertEquals(drafts.size, 0, "closed content is not mounted")
      assertEquals(popups(read(r, m.snapshot)), Vector.empty)
      ok(r.transact(open.get.set(true)))
      assertEquals(drafts.size, 1)
      val shown = popups(read(r, m.snapshot))
      assertEquals(shown.size, 1)
      assert(shown.head.anchor > 0)
      ok(r.transact(drafts.head.set(5)))
      ok(r.transact(open.get.set(false)))
      assertEquals(r.transact(drafts.head.set(6)), CommitResult.Failed(KernelError.DisposedScope))
      ok(r.transact(open.get.set(true)))
      assertEquals(drafts.size, 2)
      assertEquals(read(r, drafts(1)), 0, "reopening builds fresh content state")
      ok(r.transact(m.update(View.empty)))
      assertEquals(r.transact(drafts(1).set(1)), CommitResult.Failed(KernelError.DisposedScope))
    finally r.close()
  }
