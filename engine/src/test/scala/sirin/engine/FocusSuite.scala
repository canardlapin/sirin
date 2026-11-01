package sirin.engine

import sirin.*

class FocusSuite extends munit.FunSuite:
  private def ok[A](c: CommitResult[A]): A = c match
    case CommitResult.Committed(v) => v
    case x => fail(s"$x")
  test("semantic part focus is a rollback-aware intent, not a deferred capability") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("parts")
      val ref = owner.ref()
      val mounted = runtime.mount(owner, Button("Target").onPress(()).ref(ref))
      runtime.observe(mounted.snapshot)(_ => ())
      assertEquals(runtime.transact {
        ref.focusPart(PartId(7))
        UiTxn.refuse("cancel")
      },
        CommitResult.Refused("cancel"))
      assertEquals(runtime.takeFocusRequests().size, 0)
      ok(runtime.transact { ref.focusPart(PartId(7)) })
      ok(runtime.transact { () })
      val requests = runtime.takeFocusRequests()
      assertEquals(requests.map(_.part), Vector(Some(PartId(7))))
      assertEquals(runtime.read(ref.focusedPart), Value.Ok(None))
      val direct = Set(requests.head.reference)
      ok(runtime.transact { runtime.publishFocus(direct, direct, true, Some(PartId(7))) })
      assertEquals(runtime.read(ref.focusedPart), Value.Ok(Some(PartId(7))))
      assertEquals(runtime.read(ref.hasFocus), Value.Ok(true))
      assertEquals(runtime.transact {
        runtime.publishFocus(Set.empty, Set.empty, false)
        UiTxn.refuse("retain part")
      }, CommitResult.Refused("retain part"))
      assertEquals(runtime.read(ref.focusedPart), Value.Ok(Some(PartId(7))))
      ok(runtime.transact { mounted.update(View.empty) })
      assertEquals(runtime.read(ref.focusedPart), Value.Ok(None))
      assertEquals(runtime.read(ref.hasFocus), Value.Ok(false))
    finally runtime.close()
  }
  test("focus intents roll back, remain pending through take-free turns and focus state publishes atomically") {
    val r=new HeadlessRuntime
    try
      val scope=r.scope("app");val ref=scope.ref();val parent=scope.ref()
      val m=r.mount(scope,Column(Button("One").onPress(()).ref(ref)).ref(parent));r.observe(m.snapshot)(_ => ())
      assertEquals(r.transact {ref.focus();UiTxn.refuse("rollback")},CommitResult.Refused("rollback"))
      assertEquals(r.takeFocusRequests(),Vector.empty)
      ok(r.transact {ref.focus()})
      ok(r.transact {()})
      val requests=r.takeFocusRequests();assertEquals(requests.size,1)
      val attached=r.attachments.map(_.reference).toSet
      val child = requests.head.reference
      val ancestor = (attached - child).head
      ok(r.transact {r.publishFocus(Set(child),Set(child,ancestor),true)})
      assertEquals(r.read(ref.hasFocus),Value.Ok(true));assertEquals(r.read(ref.focusRing),Value.Ok(true))
      assertEquals(r.read(parent.hasFocus),Value.Ok(false));assertEquals(r.read(parent.containsFocus),Value.Ok(true))
      assertEquals(r.transact {r.publishFocus(Set.empty,Set.empty,false);UiTxn.refuse("keep")},CommitResult.Refused("keep"))
      assertEquals(r.read(ref.hasFocus),Value.Ok(true))
      ok(r.transact {m.update(View.empty)})
      assertEquals(r.read(ref.hasFocus),Value.Ok(false))
      assertEquals(r.read(parent.containsFocus),Value.Ok(false))
    finally r.close()
  }
