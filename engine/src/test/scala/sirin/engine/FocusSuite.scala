package sirin.engine

import sirin.*

class FocusSuite extends munit.FunSuite:
  private def ok[A](c: CommitResult[A]): A = c match
    case CommitResult.Committed(v) => v
    case x => fail(s"$x")
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
      val child=requests.head;val ancestor=(attached-child).head
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
