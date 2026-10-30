package sirin.host

import sirin.*
import sirin.engine.*

class WindowCaptureSuite extends munit.FunSuite:
  private def empty(id: Long,title: String="") = MountedSnapshot.Window(id,Value.Ok(title),MenuBar.empty,MountedSnapshot.Empty)
  private def forest(children: MountedSnapshot*) = MountedSnapshot.Rows(children.zipWithIndex.map((child,index) => index -> child).toVector)
  test("capture separates live window titles, content and bindings through replacement") {
    val r=new HeadlessRuntime
    try
      val app=r.scope("application")
      val source=app.keyed(Seq(1,2))(identity)
      val text=app.cell("shared draft")
      val title=app.cell("Document")
      val root=r.mountWindows(app,Windows(source)((_,_) => Window(title,MenuBar.empty)(TextField.bind(text))))
      app.observe(root.snapshot)(_ => ())
      def capture()=r.withCommitted(reader => reader(root.snapshot) match
        case Value.Ok(snapshot) => WindowCapture.capture(snapshot,reader)
        case Value.Failed(error) => throw error)
      val before=capture()
      assertEquals(before.size,2)
      assertEquals(before.map(_.title),Vector(Value.Ok("Document"),Value.Ok("Document")))
      assertEquals(before.map(_.content.editors.size),Vector(1,1))
      assert(before(0).content.editors.keySet.intersect(before(1).content.editors.keySet).isEmpty)
      r.transact { text.set("changed");title.set("Renamed");source.move(2,0) }
      val after=capture()
      assertEquals(after.map(_.instance),before.reverse.map(_.instance))
      assertEquals(after.map(_.title),Vector(Value.Ok("Renamed"),Value.Ok("Renamed")))
    finally r.close()
  }
  test("window capacity refuses the entire capture") {
    val r=new HeadlessRuntime
    try r.withCommitted(reader => intercept[IllegalArgumentException](WindowCapture.capture(forest(empty(1),empty(2)),reader,maxWindows=1)))
    finally r.close()
  }
  test("node budget spans windows and their content roots") {
    val r=new HeadlessRuntime
    try r.withCommitted { reader =>
      intercept[IllegalArgumentException](WindowCapture.capture(forest(empty(1),empty(2)),reader,maxNodes=4))
      assertEquals(WindowCapture.capture(forest(empty(1),empty(2)),reader,maxNodes=5).size,2)
    } finally r.close()
  }
  test("text budget spans window titles instead of resetting per window") {
    val r=new HeadlessRuntime
    try r.withCommitted(reader => intercept[IllegalArgumentException](WindowCapture.capture(forest(empty(1,"ab"),empty(2,"cd")),reader,maxTextUnits=3)))
    finally r.close()
  }
  test("failed title remains explicit while content and instance survive") {
    val r=new HeadlessRuntime
    val error=new IllegalStateException("title failed")
    try r.withCommitted { reader =>
      val result=WindowCapture.capture(MountedSnapshot.Window(1,Value.Failed(error),MenuBar.empty,MountedSnapshot.Empty),reader)
      assertEquals(result.head.title,Value.Failed(error));assertEquals(result.head.instance,1L)
    } finally r.close()
  }
  test("duplicate instances, unavailable forests and arbitrary view roots fail explicitly") {
    val r=new HeadlessRuntime
    val error=new IllegalStateException("structural source failed")
    try r.withCommitted { reader =>
      intercept[IllegalArgumentException](WindowCapture.capture(forest(empty(1),empty(1)),reader))
      assert(intercept[IllegalStateException](WindowCapture.capture(MountedSnapshot.Unavailable(error),reader)) eq error)
      intercept[IllegalArgumentException](WindowCapture.capture(MountedSnapshot.Leaf(View.empty),reader))
    } finally r.close()
  }
