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
      val first=capture();val (id,binding)=first.editors.head
      assert(binding.description.value eq text);assert(binding.description.problem eq problem)
      assertEquals(binding.description.maxUnits,256)
      assertEquals(r.transact {binding.description.commit(summon[UiTxn])},CommitResult.Committed(()))
      assertEquals(commits,1)
      assertEquals(r.transact {mounted.update(field.maxLength(3))},CommitResult.Committed(()))
      val next=capture()
      assertEquals(next.editors.keySet,Set(id));assertEquals(next.editors(id).description.maxUnits,3)
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
