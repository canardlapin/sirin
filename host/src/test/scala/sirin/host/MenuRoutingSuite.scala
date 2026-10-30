package sirin.host

import sirin.*
import sirin.engine.*

object MenuRoutingSuite:
  final class AttackingKey(compare: () => Unit):
    override def equals(other: Any): Boolean =
      compare();other.isInstanceOf[AttackingKey]
    override def hashCode(): Int=0

class MenuRoutingSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def value[A](r: HeadlessRuntime,signal: Signal[A]): A=r.read(signal) match
    case Value.Ok(result) => result
    case Value.Failed(error) => throw error
  private def capture(r: HeadlessRuntime,m: MountedView): CapturedApplication =
    r.withCommitted(reader => reader(m.snapshot) match
      case Value.Ok(snapshot) => WindowCapture.captureApplication(snapshot,reader)
      case Value.Failed(error) => throw error)
  private def mount(r: HeadlessRuntime)(owner: r.OwnedScope,windows: Windows): MountedView =
    val m=r.mountWindows(owner,windows);owner.observe(m.snapshot)(_ => ());m
  private def item(menus: CapturedMenus): MenuNode.Item=menus.nodes.head match
    case MenuNode.Group(_,Vector(result: MenuNode.Item)) => result
    case other => fail(s"$other")
  private def invoke(r: HeadlessRuntime,activation: Activation): Unit=ok(r.transact {activation.run(summon[UiTxn])})

  test("application commands remain live with zero windows and can reopen a window") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val windows=owner.keyed(Seq(1))(identity)
      val open=owner.action[Unit]("open","New")(_ => windows.append(2))
      val m=mount(r)(owner,Windows(windows)((_,_) => Window("Document",MenuBar.empty)(Text("body")))
        .menuBar(MenuBar(Menu("File",MenuItem(open,Chord.primary('n'))))))
      val before=capture(r,m);ok(r.transact {m.requestWindowClose(before.windows.head.instance)})
      val empty=capture(r,m);assert(empty.windows.isEmpty)
      val p=new MenuPresentation
      val revision=p.update(None,empty.menu,CapturedMenus.empty,Set(Chord.primary('n'))).get
      assert(revision.routed.isEmpty,"zero-window Cocoa equivalents must invoke native action facts")
      invoke(r,p.action(revision.revision,item(empty.menu).id).get)
      assertEquals(capture(r,m).windows.size,1)
    finally r.close()
  }
  test("menu, button and shortcut share argument enablement and command refusal") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val allowed=owner.cell(false);val calls=owner.cell(0)
      val command=owner.action[Unit]("save","Save",enabledFor=allowed.map(b => (_: Unit) => b))(_ => calls.update(_+1))
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar(Menu("File",MenuItem(command,Chord.primary('s')))))(
        Button(command).shortcut(Chord.primary('s'),command))))
      val first=capture(r,m).windows.head
      assert(!item(first.menu).enabled);assert(!first.content.bindings.values.head.enabled)
      val disabled=KeyboardRouting.command(first.content,None,Chord.primary('s'),first.menu,CapturedMenus.empty).get
      assert(!disabled.enabled);invoke(r,disabled)
      assertEquals(value(r,calls),0);assertEquals(value(r,command.lastRefusal),Some("command disabled"))
      ok(r.transact {allowed.set(true)})
      val next=capture(r,m).windows.head
      assert(item(next.menu).enabled);assert(next.content.bindings.values.head.enabled)
      invoke(r,next.menu.shortcut(Chord.primary('s')).get)
      assertEquals(value(r,calls),1);assertEquals(value(r,command.lastRefusal),None)
      ok(r.transact {allowed.set(false)})
      invoke(r,next.menu.shortcut(Chord.primary('s')).get) // previously enabled capture must recheck
      assertEquals(value(r,calls),1);assertEquals(value(r,command.lastRefusal),Some("command disabled"))
    finally r.close()
  }
  test("running jobs update menus while window closure preserves application work") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val completed=owner.cell(0)
      val work=Work {42}
      val save=owner.command[Unit,Unit,Int]("save","Save",WorkPolicy.download)(_ => Job((),work))((_,_) => completed.update(_+1))
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar.empty)(View.empty))
        .menuBar(MenuBar(Menu("File",MenuItem(save)))))
      val before=capture(r,m);assert(!item(before.menu).busy)
      invoke(r,before.menu.actions(item(before.menu).id).activation)
      assert(item(capture(r,m).menu).busy)
      ok(r.transact {m.requestWindowClose(before.windows.head.instance)})
      val ticket=r.scheduler.tickets.head.id;r.scheduler.run(ticket);ok(r.scheduler.deliver(ticket))
      val after=capture(r,m);assert(after.windows.isEmpty);assert(!item(after.menu).busy)
      assertEquals(value(r,completed),1);assert(r.scheduler.tickets.isEmpty)
    finally r.close()
  }
  test("local shortcuts bubble from focused child outward, then window and application") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val log=owner.cell(Vector.empty[String]);val text=owner.cell("")
      def command(name: String)=owner.action[Unit](name,name)(_ => log.update(_:+name))
      val app=command("app");val window=command("window");val inner=command("inner");val outer=command("outer")
      val chord=Chord.primary('s')
      val first=TextField.bind(text).shortcut(chord,inner)
      val second=TextField.bind(text)
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar(Menu("File",MenuItem(window,chord))))(
        Column(first,second).shortcut(chord,outer))).menuBar(MenuBar(Menu("File",MenuItem(app,chord)))))
      val all=capture(r,m);val w=all.windows.head
      val ids=w.content.editors.keys.toVector.sorted
      def route(tree: CapturedTree,focus: Option[NodeId],menus: CapturedMenus=w.menu): Unit =
        invoke(r,r.withCommitted(_ => KeyboardRouting.command(tree,focus,Chord.primary('S'),menus,all.menu).get))
      route(w.content,Some(NodeId(Vector("editor",ids.head))));assertEquals(value(r,log).last,"inner")
      route(w.content,Some(NodeId(Vector("editor",ids.last))));assertEquals(value(r,log).last,"outer")
      route(w.content.copy(keys=Vector.empty),None);assertEquals(value(r,log).last,"window")
      route(w.content.copy(keys=Vector.empty),None,CapturedMenus.empty);assertEquals(value(r,log).last,"app")
    finally r.close()
  }
  test("a disabled inner binding consumes the chord rather than falling through") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val calls=owner.cell(0)
      val no=owner.action[Unit]("no","Disabled",Signal.const(false))(_ => calls.update(_+100))
      val yes=owner.action[Unit]("yes","Enabled")(_ => calls.update(_+1))
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar(Menu("File",MenuItem(yes,Chord.primary('s')))))(
        Text("body").shortcut(Chord.primary('s'),no))))
      val w=capture(r,m).windows.head
      invoke(r,KeyboardRouting.command(w.content,None,Chord.primary('s'),w.menu,CapturedMenus.empty).get)
      assertEquals(value(r,calls),0);assertEquals(value(r,no.lastRefusal),Some("command disabled"))
    finally r.close()
  }
  test("onKey bubbles inside a focused subtree and hidden bindings disappear") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val calls=owner.cell(Vector.empty[String]);val shown=owner.cell(true)
      val body=Text("body").onKey(Chord.primary('k')) {calls.update(_:+"inner")}.onKey(Chord.primary('k')) {calls.update(_:+"outer")}.visible(shown)
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar.empty)(body)))
      val first=capture(r,m).windows.head
      invoke(r,KeyboardRouting.local(first.content,None,Chord.primary('k')).get)
      assertEquals(value(r,calls),Vector("inner"))
      ok(r.transact {shown.set(false)})
      assert(capture(r,m).windows.head.content.keys.isEmpty)
    finally r.close()
  }
  test("presentation rejects stale revisions, owner changes and same-row replacement bindings") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application")
      val a=owner.action[Unit]("a","Same")(_ => ());val b=owner.action[Unit]("b","Same")(_ => ())
      def menu(command: Command[Unit,?])=capture(r,mount(r)(owner,Windows.single("x")(_ => Window("Document",MenuBar(Menu("File",MenuItem(command))))(View.empty)))).windows.head.menu
      val menus=menu(a);val p=new MenuPresentation
      val first=p.update(Some(1),menus,CapturedMenus.empty,Set.empty).get
      assertEquals(p.update(Some(1),menus,CapturedMenus.empty,Set.empty),None)
      assert(p.action(first.revision,item(menus).id).nonEmpty)
      val second=p.update(Some(1),menu(b),CapturedMenus.empty,Set.empty).get
      assertEquals(first.menus.nodes,second.menus.nodes)
      assert(p.action(first.revision,item(menus).id).isEmpty)
      val third=p.update(Some(2),second.menus,CapturedMenus.empty,Set.empty).get
      assert(p.action(second.revision,item(menus).id).isEmpty)
      assert(p.action(third.revision,999).isEmpty)
      assert(p.action(third.revision,item(menus).id).nonEmpty)
    finally r.close()
  }
  test("fresh captures of the same immutable bindings preserve native presentation revision") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val command=owner.action[Unit]("a","A")(_ => ())
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar(Menu("File",MenuItem(command))))(View.empty)))
      val first=capture(r,m);val second=capture(r,m);val p=new MenuPresentation
      val revision=p.update(Some(first.windows.head.instance),first.menu,first.windows.head.menu,Set.empty).get
      assertEquals(p.update(Some(second.windows.head.instance),second.menu,second.windows.head.menu,Set.empty),None)
      val counts=r.lifetimes
      (1 to 100).foreach(_ => capture(r,m))
      assertEquals(r.lifetimes,counts,"repeated capture cannot allocate fresh enabledFor signal graphs")
      ok(r.transact {m.requestWindowClose(first.windows.head.instance)})
      val after=capture(r,m)
      p.update(None,after.menu,CapturedMenus.empty,Set.empty)
      assert(p.action(revision.revision,item(first.windows.head.menu).id).isEmpty)
    finally r.close()
  }
  test("matching top-level titles merge without changing action namespaces or chord priority") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val command=owner.action[Unit]("a","A")(_ => ())
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar(Menu("File",MenuItem(command))))(View.empty))
        .menuBar(MenuBar(Menu("File",MenuItem(command)))))
      val c=capture(r,m);val combined=MenuCapture.combine(c.menu,c.windows.head.menu)
      assertEquals(combined.nodes.size,1);assertEquals(combined.actions.size,2)
      assert(combined.actions.keys.exists(_<1024));assert(combined.actions.keys.exists(_>1024))
      assertEquals(combined.nodes.head.asInstanceOf[MenuNode.Group].entries.size,2)
    finally r.close()
  }
  test("menu bounds, duplicate titles and local key bounds fail explicitly") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val command=owner.action[Unit]("a","A")(_ => ())
      def read(bar: MenuBar)=r.withCommitted(reader => MenuCapture.capture(bar,reader,new CaptureBudget(10000,100000),0,0))
      intercept[IllegalArgumentException](read(MenuBar(Menu("File"),Menu("File"))))
      val large=MenuBar(Menu("File",Vector.fill(512)(MenuEntry.Separator)*))
      intercept[IllegalArgumentException](read(large))
      val deep=(1 to 34).foldLeft(MenuEntry.Separator: MenuEntry)((entry,i) => MenuEntry.Submenu(Menu(i.toString,entry)))
      intercept[IllegalArgumentException](read(MenuBar(Menu("root",deep))))
      val styled=Column(Vector.fill(257)(Text("body").shortcut(Chord.primary('s'),command))*)
      val m=r.mount(owner,styled);owner.observe(m.snapshot)(_ => ())
      val snapshot=value(r,m.snapshot)
      r.withCommitted(reader => intercept[IllegalArgumentException](CommittedTree.captureWithBindings(snapshot,reader)))
    finally r.close()
  }

  test("failed menu enablement stays disabled with diagnostic data and dispatch refusal") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val calls=owner.cell(0)
      val broken=owner.action[Unit]("bad","Unavailable",Signal.const(0).map(n => 1/n>0))(_ => calls.update(_+1))
      val m=mount(r)(owner,Windows.single("doc")(_ => Window("Document",MenuBar(Menu("File",MenuItem(broken,Chord.primary('s')))))(View.empty)))
      val menus=capture(r,m).windows.head.menu
      assert(!item(menus).enabled)
      // The kernel turns failed command enablement into a disabled command and retains its diagnostic.
      assert(r.diagnostics.exists(_.isInstanceOf[ArithmeticException]))
      invoke(r,menus.shortcut(Chord.primary('s')).get)
      assertEquals(value(r,calls),0);assertEquals(value(r,broken.lastRefusal),Some("command disabled"))
    finally r.close()
  }
  test("key ancestry equality has read-only authority during owner routing") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("application");val calls=owner.cell(0);var comparisons=0
      def compare(): Unit =
        comparisons+=1
        intercept[KernelError](r.transact {calls.set(99)})
      val id=NodeId(Vector(new MenuRoutingSuite.AttackingKey(() => compare())))
      val focus=NodeId(Vector(new MenuRoutingSuite.AttackingKey(() => compare())))
      val captured=CapturedTree(Node(id,Content.Empty),Map.empty,keys=Vector(KeyBinding(id,Chord.primary('s'),Activation(true,_ => ()))))
      intercept[KernelError](r.withCommitted(_ => KeyboardRouting.local(captured,Some(focus),Chord.primary('s'))))
      assert(comparisons>0);assertEquals(value(r,calls),0)
    finally r.close()
  }
