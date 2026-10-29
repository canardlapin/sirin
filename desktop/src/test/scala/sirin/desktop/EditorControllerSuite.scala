package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode

class EditorControllerSuite extends munit.FunSuite:
  private class Fixture(initial: String="alpha café שלום",commit: UiTxn => Unit = _ => (),multiple: Boolean=true) extends AutoCloseable:
    val runtime=new HeadlessRuntime;val scope=runtime.scope("editor-test")
    val value=scope.cell(initial);var clipboard="";var clears=0
    val unicode=NativeUnicode.open(65536)
    val base=TextField.bind(value).label("Document")
    val field=(if multiple then base.multiline else base).onCommit(commit(summon[UiTxn]))
    val mounted=runtime.mount(scope,field)
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
    def settle(): LayoutResult =
      update();generation+=1
      val state=controller.interaction.state
      val request=FrameRequest(generation,FramePayload(tree.tree,220,120,220,120,Map.empty,state.focused,state.keyboard,editors=controller.snapshots))
      val frame=renderer.render(request,() => false).asInstanceOf[RenderedFrame]
      try {controller.install(generation,frame.layout);frame.layout} finally frame.close()
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
