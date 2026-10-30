package sirin.engine

import sirin.*

class WindowRenderRecoverySuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def read[A](r: HeadlessRuntime,value: Signal[A]): A=r.read(value) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  private def windows(s: MountedSnapshot): Vector[MountedSnapshot.Window]=s match
    case w: MountedSnapshot.Window => Vector(w)
    case MountedSnapshot.Component(_,_,child) => windows(child)
    case MountedSnapshot.Rows(rows) => rows.flatMap((_,child) => windows(child))
    case MountedSnapshot.Group(children,_,_,_) => children.flatMap(windows)
    case _ => Vector.empty
  /** TextField mounts as a component around its public element; the element instance is the field identity. */
  private def fields(s: MountedSnapshot): Vector[(Long,TextField.Description)]=s match
    case MountedSnapshot.Component(_,field: TextField.Description,MountedSnapshot.Custom(frame,_)) => Vector(frame.instance->field)
    case MountedSnapshot.Group(children,_,_,_) => children.flatMap(fields)
    case MountedSnapshot.Component(_,_,child) => fields(child)
    case MountedSnapshot.Rows(rows) => rows.flatMap((_,child) => fields(child))
    case MountedSnapshot.Branch(_,_,child) => fields(child)
    case MountedSnapshot.Scroll(_,child) => fields(child)
    case MountedSnapshot.Styled(child,_) => fields(child)
    case MountedSnapshot.Referenced(_,child) => fields(child)
    case _ => Vector.empty
  private def command(window: MountedSnapshot.Window,id: String): Command[Unit,?]=
    window.menu.menus.flatMap(_.entries).collectFirst {
      case MenuEntry.Item(command,_) if command.id.name==id => command
    }.get
  test("render refusal publishes transactionally and Retry exposes the same retained field identity and draft") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val text=owner.cell("retained")
      val mounted=r.mountWindows(owner,Windows.single("main")(_ => Window("Document",MenuBar.empty)(TextField.bind(text))))
      owner.observe(mounted.snapshot)(_ => ())
      def window=windows(read(r,mounted.snapshot)).head
      val before=window;val field=fields(before.child).head._1
      val failure=WindowRenderFailure(1,"resource_limit","original renderer refusal",2)
      assertEquals(r.transact {mounted.reportWindowRenderFailure(before.instance,failure);UiTxn.refuse("rollback")},CommitResult.Refused("rollback"))
      assertEquals(window.renderStatus,WindowRenderStatus.empty)
      ok(r.transact {mounted.reportWindowRenderFailure(before.instance,failure)})
      assertEquals(window.instance,before.instance);assertEquals(window.renderStatus.current,Some(failure))
      assert(!fields(window.child).exists(_._1==field))
      ok(r.transact {text.set("changed while unavailable")})
      val retry=command(window,"window.render.retry")
      ok(r.transact {retry.dispatch(())})
      assertEquals(window.renderStatus.current,None);assertEquals(window.renderStatus.recent,Vector(failure))
      assert(fields(window.child).exists(_._1==field))
      assertEquals(read(r,text),"changed while unavailable")
    finally r.close()
  }
  test("OS close cannot bypass input review, and acknowledged recovery close still invokes the application guard") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val allow=owner.cell(false);val requests=owner.cell(0)
      val mounted=r.mountWindows(owner,Windows.single("main")(_ => Window("Document",MenuBar.empty)(Text("data")).onCloseRequest {
        requests.update(_+1);if allow.get then CloseDecision.Allow else CloseDecision.KeepOpen
      }))
      owner.observe(mounted.snapshot)(_ => ())
      def window=windows(read(r,mounted.snapshot)).head
      val instance=window.instance
      ok(r.transact {mounted.reportWindowRenderFailure(instance,WindowRenderFailure(1,"resource_limit","detail",2))})
      ok(r.transact {mounted.requestWindowClose(instance)})
      assertEquals(read(r,requests),0);assertEquals(window.instance,instance)
      val close=command(window,"window.render.close")
      ok(r.transact {close.dispatch(())})
      assertEquals(read(r,requests),1);assertEquals(window.instance,instance)
      ok(r.transact {allow.set(true);close.dispatch(())})
      assertEquals(read(r,requests),2);assert(windows(read(r,mounted.snapshot)).isEmpty)
    finally r.close()
  }
  test("failure history is bounded and late facts cannot reach a source key's replacement window") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val source=owner.keyed(Seq(1))(identity)
      val mounted=r.mountWindows(owner,Windows(source)((_,_) => Window("Document",MenuBar.empty)(Text("data"))))
      owner.observe(mounted.snapshot)(_ => ())
      def window=windows(read(r,mounted.snapshot)).head
      val first=window.instance
      for generation <- 1L to 10L do ok(r.transact {mounted.reportWindowRenderFailure(first,WindowRenderFailure(generation,"resource_limit","detail"))})
      assertEquals(window.renderStatus.recent.map(_.generation),(3L to 10L).toVector)
      assertEquals(r.transact {mounted.reportWindowRenderFailure(first,WindowRenderFailure(9,"stale","detail"))},CommitResult.Refused("render failure generation is stale"))
      ok(r.transact {source.remove(1)});ok(r.transact {source.append(1)})
      assert(window.instance!=first);assertEquals(window.renderStatus,WindowRenderStatus.empty)
      assertEquals(r.transact {mounted.reportWindowRenderFailure(first,WindowRenderFailure(11,"late","detail"))},CommitResult.Refused("window is no longer mounted"))
      assertEquals(window.renderStatus,WindowRenderStatus.empty)
      ok(r.transact {owner.dispose()})
      intercept[KernelError](r.transact {mounted.reportWindowRenderFailure(first,WindowRenderFailure(12,"late","detail"))} match
        case CommitResult.Failed(error) => throw error
        case _ => fail("disposed mount accepted failure"))
    finally r.close()
  }
  test("a failing custom recovery view can be suppressed without replacing the retained normal content") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val normal=owner.cell("normal");val custom=owner.cell("custom")
      val existing=owner.action[Unit]("existing","Existing action")(_ => ())
      val mounted=r.mountWindows(owner,Windows.single("main")(_ => Window("Document",MenuBar(Menu("View",MenuItem(existing))))(TextField.bind(normal))
        .renderFailureContent(TextField.bind(custom))))
      owner.observe(mounted.snapshot)(_ => ())
      def window=windows(read(r,mounted.snapshot)).head
      val instance=window.instance
      ok(r.transact {mounted.reportWindowRenderFailure(instance,WindowRenderFailure(1,"bad_scene","detail"))})
      assert(fields(window.child).exists(_._2.value eq custom))
      ok(r.transact {mounted.reportWindowRenderFailure(instance,WindowRenderFailure(2,"bad_recovery","detail",recoveryFailed=true))})
      assert(fields(window.child).isEmpty)
      assertEquals(window.menu.menus.count(_.title=="View"),1)
      assertEquals(command(window,"existing").id.name,"existing")
      val retry=command(window,"window.render.retry")
      ok(r.transact {retry.dispatch(())})
      assert(fields(window.child).exists(_._2.value eq normal))
    finally r.close()
  }

  test("later unapplied input updates only the matching current receipt and keeps aggregate counts without growing history") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application")
      val mounted=r.mountWindows(owner,Windows.single("main")(_ => Window("Document",MenuBar.empty)(Text("data"))))
      owner.observe(mounted.snapshot)(_ => ())
      def window=windows(read(r,mounted.snapshot)).head
      val instance=window.instance
      ok(r.transact {mounted.reportWindowRenderFailure(instance,WindowRenderFailure(1,"resource_limit","detail",2))})
      ok(r.transact {mounted.reportWindowUnappliedInputs(instance,1,512)})
      assertEquals(window.renderStatus.current.map(_.unappliedInputs),Some(514L))
      assertEquals(window.renderStatus.recent.size,1)
      assertEquals(window.renderStatus.recent.last.unappliedInputs,514L)
      assertEquals(r.transact {mounted.reportWindowUnappliedInputs(instance,2,1)},CommitResult.Refused("render failure generation is stale"))
      assertEquals(r.transact {mounted.reportWindowUnappliedInputs(instance,1,1);UiTxn.refuse("rollback")},CommitResult.Refused("rollback"))
      assertEquals(window.renderStatus.current.map(_.unappliedInputs),Some(514L))
    finally r.close()
  }
