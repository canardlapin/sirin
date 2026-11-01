package sirin.engine

import sirin.*

class SemanticReferenceSuite extends munit.FunSuite:
  test("semantic identity snapshots contain only live committed attachments and remain immutable") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("references")
      val attached = owner.ref()
      val absent = owner.ref()
      val mounted = runtime.mount(owner, Spacer(10.lp).ref(attached))
      owner.observe(mounted.snapshot)(_ => ())
      val first = runtime.referenceIdentities
      assertEquals(first.size, 1)
      assert(first.head._1 eq attached)
      assertEquals(first.map(_._2), runtime.attachments.map(_.reference))
      assert(!first.exists(_._1 eq absent))
      assertEquals(runtime.transact {
        mounted.update(Spacer(10.lp).ref(absent))
        UiTxn.refuse("keep attachment")
      }, CommitResult.Refused("keep attachment"))
      assertEquals(runtime.referenceIdentities, first)
      assertEquals(runtime.transact(mounted.update(Spacer(10.lp).ref(absent))), CommitResult.Committed(()))
      val next = runtime.referenceIdentities
      assertEquals(next.size, 1)
      assert(next.head._1 eq absent)
      assertNotEquals(next.head._2, first.head._2)
      assert(first.head._1 eq attached, "the prior snapshot is a value, not mutable docking state")
      assertEquals(runtime.transact(owner.dispose()), CommitResult.Committed(()))
      assertEquals(runtime.referenceIdentities, Vector.empty)
    finally runtime.close()
  }
