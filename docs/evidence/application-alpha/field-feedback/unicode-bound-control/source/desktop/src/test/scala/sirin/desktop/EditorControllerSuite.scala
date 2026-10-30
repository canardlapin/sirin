package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

class EditorControllerSuite extends munit.FunSuite:
  private class Fixture(initial: String="alpha café שלום",commit: UiTxn => Unit = _ => (),multiple: Boolean=true,extraField: Boolean=false,limit: Int=16384,width: Int=220,height: Int=120) extends AutoCloseable:
    val runtime=new HeadlessRuntime;val scope=runtime.scope("editor-test")
    val value=scope.cell(initial);var clipboard="";var clears=0
    val unicode=NativeUnicode.open(65536)
    val base=TextField.bind(value).maxLength(limit).label("Document")
    val field=(if multiple then base.multiline else base).onCommit(commit(summon[UiTxn]))
    val mounted=runtime.mount(scope,if extraField then Column(field,TextField.bind(value).label("Second")) else field)
    runtime.observe(mounted.snapshot)(_ => ())
    val controller=new EditorController(runtime,unicode.analyze(_),() => clipboard,s => clipboard=s,() => clears+=1)
    val root=Path.of("..").toAbsolutePath.normalize()
    val renderer=new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/unused"),64,editorSystemFonts=true)
    var generation=0L
    var tree=CapturedTree(Node(NodeId(Vector("empty")),Content.Empty),Map.empty)
    def update(): Unit =
      runtime.drive(32)
      tree=runtime.withCommitted { r => r(mounted.snapshot) match
        case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot,r)
        case Value.Failed(error) => throw error
      }
      controller.update(tree)
    def settle(snapshot: Option[Path]=None): LayoutResult =
      update();generation+=1
      val state=controller.interaction.state
      val request=FrameRequest(generation,FramePayload(controller.tree,width,height,width,height,Map.empty,state.focused,state.keyboard,editors=controller.snapshots))
      val frame=renderer.render(request,() => false).asInstanceOf[RenderedFrame]
      try
        snapshot.foreach(path => FrameWorker.save(frame.pixels,path))
        controller.install(generation,frame.layout);frame.layout
      finally frame.close()
    settle()
    val id=tree.editors.keys.head
    controller.focusEditor(id);settle()
    def key(key: Key,mods: Mods=Mods.none): Unit={controller.handle(HostInput.KeyDown(key,mods));update()}
    def text(value: String): Unit={controller.handle(HostInput.TextInput(value));update()}
    def current: String=runtime.transact {value.get} match
      case CommitResult.Committed(v) => v
      case other => throw new IllegalStateException(s"$other")
    def close(): Unit={renderer.close();unicode.close();runtime.close()}
  test("Unicode typing, select/copy/cut/paste and undo/redo preserve exact Cell values") {
    val f=new Fixture
    try
      f.key(Key.Character('a'),Mods.Primary);f.text("a\u0301😀👩‍💻")
      assertEquals(f.current,"a\u0301😀👩‍💻")
      f.key(Key.Backspace);assertEquals(f.current,"a\u0301😀")
      f.key(Key.Backspace);assertEquals(f.current,"a\u0301")
      f.key(Key.Backspace);assertEquals(f.current,"")
      f.text("office café");f.key(Key.Character('a'),Mods.Primary);f.key(Key.Character('c'),Mods.Primary)
      assertEquals(f.clipboard,"office café")
      f.key(Key.Character('x'),Mods.Primary);assertEquals(f.current,"")
      f.key(Key.Character('z'),Mods.Primary);assertEquals(f.current,"office café")
      f.key(Key.Character('z'),Mods.Primary|Mods.Shift);assertEquals(f.current,"")
      f.key(Key.Character('v'),Mods.Primary);assertEquals(f.current,"office café")
    finally f.close()
  }
  test("composition uses codepoint indices, stays provisional, consumes shortcuts and commits atomically") {
    val f=new Fixture("draft")
    try
      f.key(Key.Character('a'),Mods.Primary)
      f.controller.handle(HostInput.Composition("😀にほ",2,1));f.update()
      val state=f.controller.state(f.id).snapshot
      assertEquals(f.current,"draft");assertEquals(state.display,"😀にほ")
      assertEquals(state.visibleSelection.start,3);assertEquals(state.visibleSelection.end,4)
      f.key(Key.Character('x'),Mods.Primary);assertEquals(f.current,"draft");assertEquals(f.clipboard,"")
      f.text("日本語");assertEquals(f.current,"日本語")
      f.key(Key.Character('z'),Mods.Primary);assertEquals(f.current,"draft")
      f.controller.handle(HostInput.Composition("未確定",3,0));f.key(Key.Escape)
      assertEquals(f.current,"draft");assertEquals(f.controller.state(f.id).snapshot.preedit,None);assert(f.clears>0)
    finally f.close()
  }
  test("transaction refusal on commit preserves Cell and edit history") {
    val f=new Fixture("draft",txn => UiTxn.refuse("reject save")(using txn))
    try
      f.key(Key.Character('a'),Mods.Primary);f.text("edited")
      val before=f.controller.state(f.id)
      f.key(Key.Enter,Mods.Primary)
      assertEquals(f.current,"edited");assertEquals(f.controller.state(f.id),before)
      assertEquals(f.controller.rejected,1)
      f.key(Key.Character('z'),Mods.Primary);assertEquals(f.current,"draft")
    finally f.close()
  }
  test("external replacement/removal cancels preedit and retires history and stale AX actions") {
    val f=new Fixture
    try
      f.text("changed");f.controller.handle(HostInput.Composition("pending",7,0));f.update()
      f.runtime.transact {f.value.set("external")};f.update()
      assert(!f.controller.state(f.id).canUndo);assertEquals(f.controller.state(f.id).snapshot.preedit,None)
      val stale=new NativeWindow.AccessibilityAction(f.generation,f.id,3,0,0,"stale")
      assert(!f.controller.accessibility(stale));assertEquals(f.current,"external")
      f.settle()
      val invalid=new NativeWindow.AccessibilityAction(f.generation,f.id,2,2,999,"")
      assert(!f.controller.accessibility(invalid))
      f.runtime.transact {f.mounted.update(View.empty)};f.update()
      assert(f.controller.snapshots.isEmpty);assert(!f.controller.accessibility(stale))
    finally f.close()
  }
  test("visual arrows and hit testing use presented mixed-direction geometry at valid stops") {
    val f=new Fixture("abc שלום xyz")
    try
      val layout=f.settle();val p=layout.fields.head;val text=p.text.get
      val origin=layout.textOrigin(p)
      val target=text.caretsAt(5).head
      f.controller.handle(HostInput.Down(origin.x.toDouble+target.bounds.origin.x.toDouble,origin.y.toDouble+target.bounds.origin.y.toDouble+2));f.update()
      val hit=f.controller.state(f.id).snapshot.selection.head
      assert(text.caretsAt(hit.utf16Offset).nonEmpty)
      f.settle();f.key(Key.ArrowRight,Mods.Shift);f.settle()
      val state=f.controller.state(f.id).snapshot
      assert(text.caretsAt(state.selection.head.utf16Offset).nonEmpty)
      assertEquals(state.selection.anchor,hit)
    finally f.close()
  }
  test("wrapped selection, viewport caret and native publication geometry are clipped coherently") {
    val f=new Fixture((1 to 18).map(i => s"Line $i café").mkString("\n"))
    try
      f.key(Key.Character('a'),Mods.Primary);val layout=f.settle();val p=layout.fields.head
      val origin=layout.textOrigin(p);val caret=layout.editorCaret(p).bounds;val area=layout.editorArea(p)
      assert(origin.y.toDouble+caret.origin.y.toDouble>=area.y)
      assert(origin.y.toDouble+caret.origin.y.toDouble+caret.size.height.toDouble<=area.y+area.height+0.001)
      assert(p.text.get.selection(0,f.current.length).size>=18)
    finally f.close()
  }

  test("hard-line documents preserve CRLF and blank-line offsets after native owners close") {
    val f=new Fixture("a\u0301\r\nשלום\n\nx")
    val text=try f.settle().fields.head.text.get finally f.close()
    assertEquals(text.text,"a\u0301\r\nשלום\n\nx")
    assertEquals(text.caretsAt(3),Vector.empty)
    assert(text.caretsAt(4).head.bounds.origin.y.toDouble>text.caretsAt(2).head.bounds.origin.y.toDouble)
    assert(text.caretsAt(10).head.lineIndex>text.caretsAt(9).head.lineIndex)
    assertEquals(text.selection(2,4).size,1)
    intercept[IllegalArgumentException](text.selection(3,4))
    val caret=text.caretsAt(4).head
    assert(text.hit(Point(caret.bounds.origin.x,(caret.bounds.origin.y.toDouble+2).lp)).primary.position.utf16Offset>=4)
  }
  test("single-line fields retain unwrapped geometry and keep the insertion caret visible") {
    val f=new Fixture("long text "*50,multiple=false)
    try
      f.key(Key.Character('a'),Mods.Primary)
      val layout=f.settle();val p=layout.fields.head;val area=layout.editorArea(p);val origin=layout.textOrigin(p);val caret=layout.editorCaret(p).bounds
      assert(p.text.get.size.width.toDouble>area.width)
      assert(origin.x.toDouble<area.x)
      assert(origin.x.toDouble+caret.origin.x.toDouble>=area.x)
      assert(origin.x.toDouble+caret.origin.x.toDouble<area.x+area.width)
      f.text("line1\r\nline2\tend")
      assertEquals(f.current,"line1 line2 end")
    finally f.close()
  }
  test("double-click word and shift-click selection follow shaped hit positions") {
    val f=new Fixture("one café two")
    try
      val layout=f.settle();val p=layout.fields.head;val origin=layout.textOrigin(p)
      def at(offset: Int): (Double,Double) =
        val c=p.text.get.caretsAt(offset).head
        (origin.x.toDouble+c.bounds.origin.x.toDouble,origin.y.toDouble+c.bounds.origin.y.toDouble+2)
      val (x,y)=at(5)
      f.controller.handle(HostInput.Down(x,y),clicks=2)
      assertEquals(f.controller.state(f.id).selected,"café")
      f.settle()
      val (endX,endY)=at(11)
      f.controller.handle(HostInput.Down(endX,endY),Mods.Shift)
      assertEquals(f.controller.state(f.id).snapshot.selection.start,4)
      assertEquals(f.controller.state(f.id).snapshot.selection.end,11)
    finally f.close()
  }
  test("oversized paste refuses atomically and reports an editor notice") {
    val f=new Fixture("retained")
    try
      f.clipboard="x"*16385
      f.key(Key.Character('v'),Mods.Primary)
      assertEquals(f.current,"retained");assert(!f.controller.state(f.id).canUndo)
      assert(f.controller.notice.nonEmpty);assertEquals(f.controller.rejected,1)
    finally f.close()
  }

  test("Japanese composition has actual font coverage and retained glyph geometry") {
    val f=new Fixture("日本語 にほんご")
    try
      val p=f.settle().fields.head
      assert(p.text.get.isComplete,"editor CJK fallback must cover the IME fixture")
      assert(p.text.get.caretsAt(3).nonEmpty)
    finally f.close()
  }

  test("copy ends typing coalescence and removing a composing field clears native marked text") {
    val f=new Fixture("")
    try
      f.text("a");f.key(Key.Character('c'),Mods.Primary);f.text("b")
      f.key(Key.Character('z'),Mods.Primary);assertEquals(f.current,"a")
      f.key(Key.Character('z'),Mods.Primary);assertEquals(f.current,"")
      f.controller.handle(HostInput.Composition("未確定",3,0));f.update()
      val before=f.clears
      f.runtime.transact {f.mounted.update(View.empty)};f.update()
      assertEquals(f.clears,before+1);assert(f.controller.snapshots.isEmpty)
    finally f.close()
  }
  test("composition offsets follow normalized text and valid native actions are transactional") {
    val f=new Fixture("draft")
    try
      f.key(Key.Character('a'),Mods.Primary)
      f.controller.handle(HostInput.Composition("a\r\nb",4,0));f.update()
      assertEquals(f.controller.state(f.id).snapshot.visibleSelection.head.utf16Offset,3)
      assertEquals(f.current,"draft")
      f.key(Key.Escape);f.settle()
      assert(f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation,f.id,2,1,3,"")))
      assertEquals(f.controller.state(f.id).selected,"raf")
      f.settle()
      assert(f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation,f.id,2,4,-3,"")))
      assertEquals(f.controller.state(f.id).snapshot.selection.anchor.utf16Offset,4)
      assertEquals(f.controller.state(f.id).snapshot.selection.head.utf16Offset,1)
      f.settle()
      assert(f.controller.accessibility(new NativeWindow.AccessibilityAction(f.generation,f.id,3,0,0,"changed")))
      f.update();assertEquals(f.current,"changed")
      f.key(Key.Character('z'),Mods.Primary);assertEquals(f.current,"draft")
    finally f.close()
  }

  test("editor and IME reserve editing keys while unknown command chords reach application routing") {
    val f=new Fixture("draft",extraField=true)
    try
      def consumes(key: Key,mods: Mods=Mods.none)=f.controller.consumesKey(HostInput.KeyDown(key,mods))
      assert(consumes(Key.Character('a'),Mods.Primary));assert(consumes(Key.Character('z'),Mods.Primary|Mods.Shift))
      assert(consumes(Key.Character('界')));assert(consumes(Key.Backspace));assert(consumes(Key.ArrowLeft))
      assert(!consumes(Key.Character('s'),Mods.Primary));assert(!consumes(Key.Character('k'),Mods.Control))
      assert(consumes(Key.Tab));assert(consumes(Key.Tab,Mods.Shift));assert(!consumes(Key.Tab,Mods.Primary))
      f.controller.handle(HostInput.Composition("未確定",3,0));f.update()
      val focused=f.controller.interaction.state.focused
      assert(consumes(Key.Character('s'),Mods.Primary));assert(consumes(Key.Tab,Mods.Primary))
      f.key(Key.Tab);assertEquals(f.controller.interaction.state.focused,focused)
      assert(f.controller.state(f.id).snapshot.preedit.nonEmpty)
      f.key(Key.Escape);assert(f.controller.state(f.id).snapshot.preedit.isEmpty)
      assert(!consumes(Key.Character('s'),Mods.Primary));assertEquals(f.current,"draft")
    finally f.close()
  }

  test("button activation, focus traversal and scroll keys take precedence over shortcuts") {
    val runtime=new HeadlessRuntime
    try
      val controller=new EditorController(runtime,_ => fail("no text analysis expected"),() => "",_ => (),() => ())
      val id=NodeId(Vector("button"));val bounds=Box(0,0,100,100)
      def focus(kind: TargetKind): Unit =
        controller.interaction.install(InteractionSnapshot(1,Vector(Target(id,kind,bounds,bounds,true,Vector.empty,Vector.empty,Vector.empty))),Map.empty)
        controller.interaction.state=FocusState(Some(id))
      def key(k: Key,mods: Mods=Mods.none)=controller.consumesKey(HostInput.KeyDown(k,mods))
      focus(TargetKind.Button(1));assert(key(Key.Enter));assert(key(Key.Space));assert(key(Key.Tab))
      assert(!key(Key.Enter,Mods.Primary));assert(!key(Key.Character('s'),Mods.Primary))
      focus(TargetKind.Scroll(2,500));assert(key(Key.PageDown));assert(key(Key.Home));assert(key(Key.ArrowUp))
      assert(!key(Key.PageDown,Mods.Primary));assert(!key(Key.Character('s'),Mods.Primary))
    finally runtime.close()
  }

  test("field limits count UTF-16, reject whole edits and render recoverable feedback") {
    val f=new Fixture("old",multiple=false,limit=3,width=140,height=160)
    try
      f.key(Key.Character('a'),Mods.Primary);f.text("😀a")
      assertEquals(f.current,"😀a")
      val before=f.controller.state(f.id)
      f.clipboard="😀😀";f.key(Key.Character('a'),Mods.Primary)
      val selected=f.controller.state(f.id)
      f.key(Key.Character('v'),Mods.Primary)
      assertEquals(f.current,"😀a");assertEquals(f.controller.state(f.id),selected)
      assert(!f.controller.ready)
      val refused=f.settle(Some(Path.of("target/field-feedback/limit-refusal.png")));val problem=refused.fields.head.node.content.asInstanceOf[Content.Editable].error
      assert(problem.exists(_.contains("3 UTF-16")))
      val labels=refused.fieldLabels(f.id);val placed=refused.fields.head
      assert(labels.error.get.size.height.toDouble>20,"fixture must wrap its field diagnostic")
      val area=refused.editorArea(placed)
      val borderBottom=area.y+area.height+8
      assert(borderBottom+2+labels.error.get.size.height.toDouble<=placed.bounds.y+placed.bounds.height+0.001)
      assert(labels.error.nonEmpty);assert(f.controller.ready)
      assertEquals(f.controller.state(f.id).historySize,before.historySize)
      f.text("ok");val recovered=f.settle()
      assertEquals(f.current,"ok")
      assertEquals(recovered.fields.head.node.content.asInstanceOf[Content.Editable].error,None)
      assertEquals(f.controller.notice,None)
    finally f.close()
  }
  test("IME preedit can exceed the input limit but a refused commit preserves the draft and clears marked text") {
    val f=new Fixture("old",multiple=false,limit=3)
    try
      f.key(Key.Character('a'),Mods.Primary)
      val before=f.controller.state(f.id)
      f.controller.handle(HostInput.Composition("かなかな",4,0));f.update()
      assertEquals(f.current,"old");assertEquals(f.controller.state(f.id).snapshot.display,"かなかな")
      f.text("かなかな")
      assertEquals(f.current,"old");assertEquals(f.controller.state(f.id).snapshot.preedit,None)
      assertEquals(f.controller.state(f.id).historySize,before.historySize);assert(f.clears>0)
      assert(f.settle().fieldLabels(f.id).error.nonEmpty)
      f.text("語");assertEquals(f.current,"語")
      f.key(Key.Character('z'),Mods.Primary);assertEquals(f.current,"old")
    finally f.close()
  }
  test("current accessibility replacements share field limits and malformed text cannot escape the edit boundary") {
    val f=new Fixture("old",multiple=false,limit=3)
    try
      val before=f.controller.state(f.id)
      val oversized=new NativeWindow.AccessibilityAction(f.generation,f.id,3,0,0,"oversized")
      assert(!f.controller.accessibility(oversized));assertEquals(f.current,"old")
      assertEquals(f.controller.state(f.id),before);assert(f.settle().fieldLabels(f.id).error.nonEmpty)
      val malformed=new NativeWindow.AccessibilityAction(f.generation,f.id,3,0,0,"\ud800")
      assert(!f.controller.accessibility(malformed));assertEquals(f.current,"old")
      f.settle()
      val valid=new NativeWindow.AccessibilityAction(f.generation,f.id,3,0,0,"new")
      assert(f.controller.accessibility(valid));f.update();assertEquals(f.current,"new")
      assertEquals(f.settle().fields.head.node.content.asInstanceOf[Content.Editable].error,None)
      assertEquals(NativeApplication.liveApplications(),0)
    finally f.close()
  }
  test("tightening a limit rejects oversized undo while programmatic drafts remain recoverable") {
    val f=new Fixture("",multiple=false,limit=5)
    try
      f.text("12345");f.key(Key.Character('a'),Mods.Primary);f.text("x")
      f.runtime.transact {f.mounted.update(f.field.maxLength(3))};f.update()
      val before=f.controller.state(f.id)
      f.key(Key.Character('z'),Mods.Primary)
      assertEquals(f.current,"x");assertEquals(f.controller.state(f.id),before)
      f.runtime.transact {f.value.set("external draft")};f.update()
      assertEquals(f.current,"external draft");assert(f.settle().fieldLabels(f.id).error.nonEmpty)
      f.key(Key.Character('a'),Mods.Primary);f.text("ok")
      assertEquals(f.current,"ok");assertEquals(f.settle().fieldLabels(f.id).error,None)
    finally f.close()
  }

  test("bounded refusal feedback never introduces an unpaired surrogate into rendered text") {
    val reason="a"+"😀"*256
    val f=new Fixture("retained",txn => UiTxn.refuse(reason)(using txn))
    try
      f.key(Key.Enter,Mods.Primary)
      val field=f.controller.tree.content.asInstanceOf[Content.Editable]
      val message=field.error.get
      TextEditing.validate(message)
      assert(message.length<=512)
      assertEquals(f.current,"retained")
      assert(f.settle().fieldLabels(f.id).error.nonEmpty)
    finally f.close()
  }
