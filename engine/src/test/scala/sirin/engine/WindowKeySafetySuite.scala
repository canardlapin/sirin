package sirin.engine

import sirin.*

object WindowKeySafetySuite:
  final class Key(val id: Int, compare: () => Unit):
    override def hashCode(): Int = 0
    override def equals(other: Any): Boolean =
      compare()
      other match
        case key: Key => id == key.id
        case _ => false

class WindowKeySafetySuite extends munit.FunSuite:
  import WindowKeySafetySuite.Key

  test("automatic dismissal cannot invoke key equality with UI write authority") {
    val r = new HeadlessRuntime
    try
      val app = r.scope("application")
      val target = app.cell(0)
      var txn: UiTxn = null
      var armed = false
      var armOnClose = false
      def compare(): Unit =
        if armed then
          armed = false // Attack only the first comparison after the user close handler.
          try target.set(99)(using txn) catch case _: KernelError => ()
      val keys = Seq(new Key(1, () => compare()), new Key(2, () => compare()))
      val source = app.keyed(keys)(identity)
      val root = r.mountWindows(app, Windows(source) { (_, _) =>
        Window("Window", MenuBar.empty)(View.empty).onClose {
          if armOnClose then armed = true
        }
      })
      def frames(snapshot: MountedSnapshot): Vector[Long] = snapshot match
        case MountedSnapshot.Window(id, _, _, _, _) => Vector(id)
        case MountedSnapshot.Component(_, _, child) => frames(child)
        case MountedSnapshot.CustomLayout(_, children) => children.flatMap((_, child) => frames(child))
        case MountedSnapshot.Rows(rows) => rows.flatMap((_, child) => frames(child))
        case _ => Vector.empty
      val ids = r.transact { frames(root.snapshot.get) } match
        case CommitResult.Committed(value) => value
        case other => fail(s"read did not commit: $other")
      assertEquals(r.transact { root.requestWindowClose(ids.head) }, CommitResult.Committed(()))
      armOnClose = true
      assertEquals(r.transact {
        txn = summon[UiTxn]
        root.requestWindowClose(ids(1))
      }, CommitResult.Failed(KernelError.ReadOnlyPhase))
      assertEquals(r.transact { target.get }, CommitResult.Committed(0))
      assertEquals(r.transact { frames(root.snapshot.get) }, CommitResult.Committed(Vector(ids(1))))
    finally r.close()
  }
