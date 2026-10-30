package sirin.desktop

import sirin.*
import sirin.engine.*

class ApplicationServicesSuite extends munit.FunSuite:
  test("programmatic quit uses owner-checked outer publication, including rollback and disposal") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");var requested=0
      val services=DesktopApplicationPlatform.services(r)(owner,() => requested+=1)
      assertEquals(r.transact {services.quit.dispatch(());UiTxn.refuse("keep working")},CommitResult.Refused("keep working"))
      assertEquals(requested,0)
      assert(r.transact {services.quit.dispatch(())}.isInstanceOf[CommitResult.Committed[?]])
      assertEquals(requested,1)
      assertEquals(r.transact {services.quit.dispatch(());owner.dispose()},CommitResult.Committed(()))
      assertEquals(requested,1)
    finally r.close()
  }
  test("service launch rejects a non-main-thread JVM call before the application builder runs") {
    var built=false
    intercept[IllegalStateException] {
      Application.run { (_: Scope,_: ApplicationServices) => built=true;Windows.single("x")(_ => Window("x",MenuBar.empty)(View.empty)) }
    }
    assert(!built);assertEquals(NativeApplication.liveApplications(),0)
  }
