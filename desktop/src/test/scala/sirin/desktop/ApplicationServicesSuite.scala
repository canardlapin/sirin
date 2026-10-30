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

  test("native quit and menu save wait for earlier layout-held edits before reading committed state") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application")
      val body=owner.cell("");val saved=owner.cell("");val seen=owner.cell(Vector.empty[String])
      val save=owner.action[Unit]("save","Save")(_ => saved.set(body.snapshot.value))
      val windows=Windows.single("main")(_ => Window("Document",MenuBar.empty)(TextField.bind(body)))
        .onQuitRequest {
          seen.update(_:+body.get)
          if body.get==saved.get then CloseDecision.Allow else CloseDecision.KeepOpen
        }
      val mounted=r.mountWindows(owner,windows)
      owner.observe(mounted.snapshot)(_ => ())
      val input=new OwnerInputQueue[Int]
      val boundary=new OwnerCommandBoundary
      var ready=false;var menus=0;var decision=Option.empty[CloseDecision]
      input.add(1,Some(sirin.host.HostInput.Down(1,1)))
      input.add(2,Some(sirin.host.HostInput.TextInput("latest unsaved edit")))
      def drain(): Unit =
        assert(input.drain(() => ready,_ => true)((_,event) => {
          event match
            case sirin.host.HostInput.TextInput(text) => assertEquals(r.transact {body.set(text)},CommitResult.Committed(()))
            case _ => ()
          true
        },_ => fail("no window close was queued")))
        boundary.drain(input.nonEmpty)(() => r.transact {mounted.requestApplicationQuit()} match
          case CommitResult.Committed(value) => decision=Some(value)
          case other => fail(s"$other"),() => {
            menus+=1
            assert(r.transact {save.dispatch(())}.isInstanceOf[CommitResult.Committed[?]])
          })
      boundary.requestQuit();boundary.requestQuit();drain()
      assertEquals(decision,None);assertEquals(menus,0)
      ready=true;drain()
      assertEquals(decision,Some(CloseDecision.KeepOpen));assertEquals(menus,1)
      assertEquals(r.read(saved),Value.Ok("latest unsaved edit"))
      assertEquals(r.read(seen),Value.Ok(Vector("latest unsaved edit")))
      drain()
      assertEquals(r.read(seen),Value.Ok(Vector("latest unsaved edit")))
      assertEquals(NativeApplication.liveApplications(),0)
    finally r.close()
  }
  test("a retired peer cannot hold zero-window application commands indefinitely") {
    val input=new OwnerInputQueue[Int];val boundary=new OwnerCommandBoundary
    input.add(1,Some(sirin.host.HostInput.Down(1,1)))
    var quits=0;var menus=0
    boundary.requestQuit()
    boundary.drain(input.nonEmpty)(() => quits+=1,() => menus+=1)
    assertEquals((quits,menus),(0,0))
    input.clear()
    boundary.drain(input.nonEmpty)(() => quits+=1,() => menus+=1)
    assertEquals((quits,menus),(1,1))
  }
