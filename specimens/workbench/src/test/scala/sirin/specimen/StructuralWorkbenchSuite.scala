package sirin.specimen

import sirin.*
import sirin.engine.*
import RunWorkbench.*

class StructuralWorkbenchSuite extends munit.FunSuite:
  test("public structural workbench filters and moves its retained editor") {
    val host = new HeadlessRuntime
    try
      val model = new StructuralWorkbench(host.scope("workbench"))
      val mounted = host.mount(host.scope("window"), model.view)
      accepted(host.transact { mounted.setViewport(RowViewport(0.lp, 140.lp)); model.draft.set("keep"); model.move.dispatch(()) })
      assertEquals(read(host, model.draft), "keep")
      assert(read(host, model.right))
      accepted(host.transact { model.minimum.set(900) })
      assertEquals(accepted(host.transact { model.filtered.size }), 100)
      assertEquals(host.attachments.size, 2)
    finally host.close()
  }
