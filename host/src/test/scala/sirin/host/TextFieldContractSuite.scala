package sirin.host

import sirin.*
import sirin.engine.*

class TextFieldContractSuite extends munit.FunSuite:
  test("decorated field limits preserve bindings, callbacks and mounted editor identity") {
    val r=new HeadlessRuntime
    try
      val scope=r.scope("field");val text=scope.cell("draft");var commits=0
      val problem=Signal.const(Some("domain validation"))
      val field=TextField.bind(text).label("Title").error(problem).onCommit {commits+=1}.padding(4.lp).maxLength(256)
      val mounted=r.mount(scope,field);scope.observe(mounted.snapshot)(_ => ())
      def capture()=r.withCommitted(reader => reader(mounted.snapshot) match
        case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot,reader)
        case Value.Failed(error) => throw error)
      val description=field.asInstanceOf[View.FieldDecoration].view.asInstanceOf[TextField.Description]
      assert(description.value eq text);assert(description.problem eq problem)
      assertEquals(description.maxUnits,256)
      assertEquals(r.transact {description.commit(summon[UiTxn])},CommitResult.Committed(()))
      assertEquals(commits,1)
      val first=capture();val id=first.elements.keys.head
      assertEquals(first.elements.size,1)
      assertEquals(r.transact {mounted.update(field.maxLength(3))},CommitResult.Committed(()))
      val next=capture()
      assertEquals(next.elements.keySet,Set(id),"reconfiguring the field keeps its mounted element and draft owner")
      assertEquals(r.read(text),Value.Ok("draft"))
    finally r.close()
  }
  test("input limits reject invalid configuration and retain the existing hard default") {
    val r=new HeadlessRuntime
    try
      val text=r.scope("field").cell("")
      val field=TextField.bind(text)
      intercept[IllegalArgumentException](field.maxLength(-1))
      intercept[IllegalArgumentException](field.maxLength(16385))
      assertEquals(field.asInstanceOf[TextField.Description].maxUnits,16384)
      assertEquals(field.maxLength(0).asInstanceOf[TextField.Description].maxUnits,0)
    finally r.close()
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
      assertEquals(captured.elements.size,1)
      def plain(view: View): TextField.Description = view match
        case View.FieldDecoration(inner,_) => plain(inner)
        case View.Decorated(inner,_) => plain(inner)
        case d: TextField.Description => d
        case other => fail(s"unexpected view $other")
      val binding=plain(field)
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
      val old=capture().elements.keys.head
      runtime.transact {mounted.update(TextField.bind(b));UiTxn.refuse("cancel")}
      assertEquals(capture().elements.keys.head,old)
      runtime.transact {mounted.update(TextField.bind(b))}
      val replacement=capture().elements.keys.head
      assertNotEquals(replacement,old)
      runtime.transact {mounted.update(View.empty)}
      assertEquals(capture().elements,Map.empty[Long,ElementBinding])
    finally runtime.close()
  }
