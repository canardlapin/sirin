package sirin.host

import sirin.*
import sirin.engine.*

class InteractionSuite extends munit.FunSuite:
  private def target(id: Int,enabled: Boolean=true,x: Double=0): Target =
    Target(NodeId(Vector(id)),TargetKind.Button(id),Box(x,0,20,20),Box(x,0,20,20),enabled,Vector(id.toLong),Vector(90),Vector.empty)
  private def setup(): Interaction =
    val i=new Interaction;i.install(InteractionSnapshot(1,Vector(target(1,x=0),target(2,x=30),target(3,x=60))),Map.empty);i
  test("disabled focused middle control retains focus and traversal skips in document order") {
    val i=setup();assert(i.focusRef(2))
    i.install(InteractionSnapshot(2,Vector(target(1),target(2,false,30),target(3,x=60))),Map.empty)
    assertEquals(i.state.focused,Some(NodeId(Vector(2))))
    assertEquals(i.handle(HostInput.KeyDown(Key.Enter,Mods.none)).activate,None)
    i.handle(HostInput.KeyDown(Key.Tab,Mods.none));assertEquals(i.state.focused,Some(NodeId(Vector(3))))
    i.focusRef(2);i.handle(HostInput.KeyDown(Key.Tab,Mods.Shift));assertEquals(i.state.focused,Some(NodeId(Vector(1))))
  }
  test("pointer and Space press origins cannot complete each other's activation; modified releases cancel") {
    val i=setup();i.focusRef(1)
    i.handle(HostInput.KeyDown(Key.Space,Mods.none))
    assertEquals(i.handle(HostInput.Up(5,5)).activate,None)
    i.handle(HostInput.Down(5,5))
    assertEquals(i.handle(HostInput.KeyUp(Key.Space,Mods.none)).activate,None)
    i.handle(HostInput.KeyDown(Key.Space,Mods.none));i.handle(HostInput.KeyUp(Key.Space,Mods.Shift))
    assertEquals(i.handle(HostInput.KeyUp(Key.Space,Mods.none)).activate,None)
    i.handle(HostInput.KeyDown(Key.Space,Mods.none))
    assertEquals(i.handle(HostInput.KeyUp(Key.Space,Mods.none)).activate,Some(1L))
  }
  test("matching primary hit pairs activate once; changed geometry, deactivation and outside release cancel") {
    val i=setup()
    i.handle(HostInput.Down(5,5));assertEquals(i.handle(HostInput.Up(5,5)).activate,Some(1L))
    assertEquals(i.handle(HostInput.Up(5,5)).activate,None)
    i.handle(HostInput.Down(5,5));i.handle(HostInput.Deactivate)
    assertEquals(i.handle(HostInput.Up(5,5)).activate,None)
    i.handle(HostInput.Down(5,5));assertEquals(i.handle(HostInput.Up(99,99)).activate,None)
    i.handle(HostInput.Down(5,5));i.install(InteractionSnapshot(2,Vector(target(1,x=1))),Map.empty)
    assertEquals(i.handle(HostInput.Up(5,5)).activate,None)
    i.handle(HostInput.Down(5,5,false));assertEquals(i.handle(HostInput.Up(5,5,false)).activate,None)
  }
  test("visual-only presentation preserves press; mounted replacement rejects old target") {
    val i=setup();i.handle(HostInput.Down(5,5))
    i.install(InteractionSnapshot(2,i.presented.targets),Map.empty)
    assertEquals(i.handle(HostInput.Up(5,5)).activate,Some(1L))
    i.handle(HostInput.Down(5,5));i.install(InteractionSnapshot(3,Vector(target(4))),Map.empty)
    assertEquals(i.handle(HostInput.Up(5,5)).activate,None)
  }
  test("reference wrappers are transparent to grow in both decoration orders") {
    def leaf(id: String,size: Double)=Node(NodeId(Vector(id)),Content.Space(size))
    val child=leaf("s",0)
    val reference=Node(NodeId(Vector("r")),Content.Reference(1,child))
    val variants=Vector(
      Node(NodeId(Vector("g")),Content.Styled(Hint.Grow,reference)),
      reference.copy(content=Content.Reference(1,Node(NodeId(Vector("g")),Content.Styled(Hint.Grow,child)))))
    variants.foreach { growing =>
      val root=Node(NodeId(Vector("row")),Content.Stack(true,0,Alignment.Start,Vector(growing,leaf("fixed",20))))
      val layout=sirin.host.Layout(root,100,30)(_ => fail("no text"))
      assertEquals(layout.root.children.map(_.bounds.width),Vector(80.0,20.0))
      assertEquals(layout.root.children(1).bounds.x,80.0)
    }
  }
  test("wheel and page/home/end clamp using presented viewport extent") {
    val i=new Interaction
    val scroll=target(7).copy(kind=TargetKind.Scroll(20,500),bounds=Box(0,0,100,80),clip=Box(0,0,100,80))
    i.install(InteractionSnapshot(1,Vector(scroll)),Map.empty);i.focusRef(7)
    i.handle(HostInput.KeyDown(Key.PageDown,Mods.none));assertEquals(i.state.scrolls(20),72.0)
    i.handle(HostInput.Wheel(10,10,-2));assertEquals(i.state.scrolls(20),152.0)
    i.handle(HostInput.KeyDown(Key.End,Mods.none));assertEquals(i.state.scrolls(20),500.0)
    i.handle(HostInput.KeyDown(Key.Home,Mods.none));assertEquals(i.state.scrolls(20),0.0)
  }
  test("button replacement retires identity and owner bindings recheck enablement at dispatch") {
    val r=new HeadlessRuntime
    def ok[A](c: CommitResult[A]): A = c match
      case CommitResult.Committed(v) => v
      case x => fail(s"$x")
    try
      val scope=r.scope("app");val enabled=scope.cell(true);val count=scope.cell(0)
      val command=scope.action[Unit]("inc","Increment",enabled)(_ => count.set(count.get+1))
      val m=r.mount(scope,Button(command));r.observe(m.snapshot)(_ => ())
      def capture()=r.withCommitted(reader => reader(m.snapshot) match
        case Value.Ok(s) => CommittedTree.captureWithBindings(s,reader)
        case Value.Failed(e) => throw e)
      val first=capture();val id=first.bindings.keys.head
      ok(r.transact {enabled.set(false)})
      ok(r.transact {first.bindings(id).run(summon[UiTxn])})
      assertEquals(r.read(count),Value.Ok(0))
      ok(r.transact {m.update(Text("replacement"))})
      assert(!capture().bindings.contains(id))
      ok(r.transact {m.update(Button(command))})
      assert(!capture().bindings.contains(id))
      ok(r.transact {m.update(Scroll(Text("scroll")))})
      assert(capture().tree.content.isInstanceOf[Content.Scroll])
    finally r.close()
  }
  test("refused activation rolls back publication without resurrecting a consumed release") {
    val i=setup();i.handle(HostInput.Down(5,5));val before=i.state
    assertEquals(i.handle(HostInput.Up(5,5)).activate,Some(1L));i.rollbackPublication(before)
    assertEquals(i.handle(HostInput.Up(5,5)).activate,None)
    i.focusRef(1);i.handle(HostInput.KeyDown(Key.Space,Mods.none));val keyboard=i.state
    assertEquals(i.handle(HostInput.KeyUp(Key.Space,Mods.none)).activate,Some(1L));i.rollbackPublication(keyboard)
    assertEquals(i.handle(HostInput.KeyUp(Key.Space,Mods.none)).activate,None)
  }
  test("remounted scroll at the same position cannot inherit focus") {
    val i=new Interaction
    val first=target(1).copy(id=NodeId(Vector("scroll",10)),kind=TargetKind.Scroll(10,500))
    i.install(InteractionSnapshot(1,Vector(first)),Map.empty);i.focusRef(1)
    i.install(InteractionSnapshot(2,Vector(first.copy(id=NodeId(Vector("scroll",11)),kind=TargetKind.Scroll(11,500)))),Map.empty)
    assertEquals(i.state.focused,None)
  }
