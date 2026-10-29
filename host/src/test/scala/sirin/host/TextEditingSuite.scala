package sirin.host

import sirin.*
import sirin.engine.*

class TextEditingSuite extends munit.FunSuite:
  private def ascii(s: String): Vector[Int]=(0 to s.length).toVector
  private def selected(a: Int,b: Int)=EditSelection(TextPosition(a,TextAffinity.Downstream),TextPosition(b,TextAffinity.Upstream))
  test("directional replacement, undo and redo restore text and selection") {
    val initial=TextEditing("hello").select(selected(4,1),ascii)
    val next=initial.replace("i",ascii)
    assertEquals(next.snapshot.text,"hio");assertEquals(next.snapshot.selection,EditSelection.at(2))
    assertEquals(next.undo.snapshot,initial.snapshot)
    assertEquals(next.undo.redo.snapshot,next.snapshot)
    assert(!next.undo.replace("different",ascii).canRedo)
  }
  test("typing coalesces until navigation or a command boundary") {
    val first=TextEditing("").replace("a",ascii,true).replace("b",ascii,true).replace("c",ascii,true)
    assertEquals(first.undo.snapshot.text,"")
    val moved=first.select(EditSelection.at(1),ascii).replace("x",ascii,true)
    assertEquals(moved.undo.snapshot.text,"abc")
    assertEquals(moved.undo.undo.snapshot.text,"")
  }
  test("preedit updates never change committed text and commit is one undo step") {
    val original=TextEditing("before after").select(selected(7,12),ascii)
    val preedit=original.mark("にほ",2,2).mark("日本語",3,3)
    assertEquals(preedit.snapshot.text,"before after")
    assertEquals(preedit.snapshot.display,"before 日本語")
    assertEquals(preedit.snapshot.visibleSelection.head.utf16Offset,10)
    assertEquals(preedit.cancel.snapshot,original.snapshot)
    val committed=preedit.commit("日本語",ascii)
    assertEquals(committed.snapshot.text,"before 日本語")
    assertEquals(committed.undo.snapshot,original.snapshot)
  }
  test("composition cancellation and external replacement invalidate stale history") {
    val edited=TextEditing("a").replace("b",ascii).mark("x",1,1)
    val external=edited.sync("external",ascii)
    assertEquals(external.snapshot.preedit,None);assert(!external.canUndo && !external.canRedo)
    assertEquals(external.snapshot.selection.head.utf16Offset,1)
  }
  test("grapheme-safe deletion treats combining and surrogate sequences atomically") {
    val text="a\u0301😀b"
    def boundaries(s: String)=s match
      case `text` => Vector(0,2,4,5)
      case "a\u0301b" => Vector(0,2,3)
      case "b" => Vector(0,1)
      case other => ascii(other)
    val initial=TextEditing(text).select(EditSelection.at(4),boundaries)
    val noEmoji=initial.delete(true,false,boundaries,Vector.empty)
    assertEquals(noEmoji.snapshot.text,"a\u0301b")
    val noAccent=noEmoji.delete(true,false,boundaries,Vector.empty)
    assertEquals(noAccent.snapshot.text,"b")
    intercept[IllegalArgumentException](initial.select(EditSelection.at(1),boundaries))
    intercept[IllegalArgumentException](initial.select(EditSelection.at(3),boundaries))
  }
  test("insertion merging a cluster chooses a valid following boundary") {
    def boundary(s: String)=if s=="a\u0301" then Vector(0,2) else ascii(s)
    val state=TextEditing("a").select(EditSelection.at(1),boundary).replace("\u0301",boundary)
    assertEquals(state.snapshot.selection,EditSelection.at(2))
    assertEquals(state.undo.snapshot.text,"a")
  }
  test("history and document budgets remain bounded and invalid edits leave original usable") {
    var state=TextEditing("")
    for i <- 0 until 100 do state=state.selectAll.replace(i.toString,ascii)
    assertEquals(state.historySize,64);assert(state.historyBytes<=2L*1024*1024)
    intercept[IllegalArgumentException](state.replace("x"*16385,ascii))
    intercept[IllegalArgumentException](state.replace("\u0000",ascii))
    intercept[IllegalArgumentException](state.replace("\uD800",ascii))
    assertEquals(state.snapshot.text,"99");assertEquals(state.undo.snapshot.text,"98")
  }
  test("line ending normalization is one undoable edit") {
    val state=TextEditing("").replace("a\r\nb\rc",ascii)
    assertEquals(state.snapshot.text,"a\nb\nc");assertEquals(state.undo.snapshot.text,"")
  }
  test("public TextField builder captures declared values, error and transaction binding") {
    val runtime=new HeadlessRuntime
    val scope=runtime.scope("fields");val value=scope.cell("draft");val commits=scope.cell(0)
    val field=TextField.bind(value).label("Document").placeholder("Type here").multiline.error(value.map(v => Option.when(v.isEmpty)("Required")))
      .onCommit(commits.set(commits.get+1)).width(200.lp).padding(4.lp).ref(scope.ref())
    val mounted=runtime.mount(scope,field)
    runtime.observe(mounted.snapshot)(_ => ())
    try
      runtime.drive(32)
      val captured=runtime.withCommitted { r => CommittedTree.captureWithBindings(r(mounted.snapshot).asInstanceOf[Value.Ok[MountedSnapshot]].value,r) }
      assertEquals(captured.editors.size,1)
      val binding=captured.editors.head._2.description
      assertEquals(binding.fieldLabel,"Document");assertEquals(binding.hint,"Type here");assert(binding.multiple)
      runtime.transact {binding.value.set("edited");binding.commit(summon[UiTxn])} match
        case CommitResult.Committed(_) => ()
        case other => fail(s"$other")
      assertEquals(runtime.transact {value.get},CommitResult.Committed("edited"))
      assertEquals(runtime.transact {commits.get},CommitResult.Committed(1))
    finally runtime.close()
  }
  test("changing a field's bound Cell replaces identity even when text is equal; rollback preserves old identity") {
    val runtime=new HeadlessRuntime
    val scope=runtime.scope("fields");val a=scope.cell("same");val b=scope.cell("same")
    val mounted=runtime.mount(scope,TextField.bind(a))
    runtime.observe(mounted.snapshot)(_ => ())
    def capture(): CapturedTree =
      runtime.drive(32)
      runtime.withCommitted { r => r(mounted.snapshot) match
        case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot,r)
        case Value.Failed(error) => throw error
      }
    try
      val old=capture().editors.keys.head
      runtime.transact {mounted.update(TextField.bind(b));UiTxn.refuse("cancel")}
      assertEquals(capture().editors.keys.head,old)
      runtime.transact {mounted.update(TextField.bind(b))}
      val replacement=capture().editors.keys.head
      assertNotEquals(replacement,old)
      runtime.transact {mounted.update(View.empty)}
      assertEquals(capture().editors,Map.empty[Long,TextBinding])
    finally runtime.close()
  }
