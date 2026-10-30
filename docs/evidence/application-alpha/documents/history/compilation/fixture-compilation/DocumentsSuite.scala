package sirin.specimen

import sirin.*
import sirin.engine.*

class DocumentsSuite extends munit.FunSuite:
  private val id=DocumentId("first")
  private val initial=LoadedDocument(DocumentData(id,"Title","initial"),DocumentStamp("0"*64))
  private class Session extends DocumentSession:
    val initial=DocumentsSuite.this.initial
    var writes=Vector.empty[DocumentData]
    var failure=Option.empty[Throwable]
    def save(data: DocumentData): Work[LoadedDocument]=Work {
      failure.foreach(throw _)
      writes=writes:+data
      LoadedDocument(data,DocumentStamp(f"${writes.size}%064x"))
    }
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def value[A](r: HeadlessRuntime,signal: Signal[A]): A=r.read(signal) match
    case Value.Ok(result) => result
    case Value.Failed(error) => throw error
  private def finish(r: HeadlessRuntime,id: Long): Unit={r.scheduler.run(id);ok(r.scheduler.deliver(id))}
  test("queued saves associate committed title/body revisions and preserve newer edits") {
    val r=new HeadlessRuntime
    try
      val session=new Session;val model=new DocumentEditor(r.scope("document"),session)
      assert(!value(r,model.dirty));assertEquals(ok(r.transact {model.requestClose()}),CloseDecision.Allow)
      ok(r.transact {model.body.set("17")});ok(r.transact {model.save.dispatch(())})
      ok(r.transact {model.body.set("18");model.title.set("  Exact title  ")});ok(r.transact {model.save.dispatch(())})
      val tickets=r.scheduler.tickets
      assertEquals(tickets.map(_.ready),Vector(true,false))
      finish(r,tickets.head.id)
      assertEquals(session.writes.map(_.body),Vector("17"));assert(value(r,model.dirty))
      assertEquals(value(r,model.lastSaved).data.body,"17")
      finish(r,tickets.last.id)
      assertEquals(session.writes.last,DocumentData(id,"  Exact title  ","18"))
      assert(!value(r,model.dirty));assertEquals(value(r,model.status),"Saved")
    finally r.close()
  }
  test("invalid drafts remain editable, show validation and record save refusal") {
    val r=new HeadlessRuntime
    try
      val session=new Session;val model=new DocumentEditor(r.scope("document"),session)
      ok(r.transact {model.title.set("")})
      assertEquals(value(r,model.title),"");assert(value(r,model.titleError).nonEmpty)
      assert(!value(r,model.save.enabled));assert(value(r,model.dirty))
      assertEquals(ok(r.transact {model.save.dispatch(())}),Dispatched.Refused("command disabled"))
      assertEquals(value(r,model.notice),Some("command disabled"));assert(session.writes.isEmpty)
      assertEquals(ok(r.transact {model.requestClose()}),CloseDecision.KeepOpen)
      assert(value(r,model.closePrompt))
    finally r.close()
  }
  test("failed saves retain exact drafts and saved baseline, then a retry can succeed") {
    val r=new HeadlessRuntime
    try
      val session=new Session;val model=new DocumentEditor(r.scope("document"),session)
      val failure=new IllegalStateException("Disk unavailable")
      session.failure=Some(failure)
      ok(r.transact {model.body.set("retain 😀 日本語")});ok(r.transact {model.save.dispatch(())})
      finish(r,r.scheduler.tickets.head.id)
      assert(value(r,model.dirty));assertEquals(value(r,model.body),"retain 😀 日本語")
      assertEquals(value(r,model.lastSaved),initial);assertEquals(value(r,model.saveError),Some(failure))
      session.failure=None;ok(r.transact {model.save.dispatch(())});finish(r,r.scheduler.tickets.head.id)
      assert(!value(r,model.dirty));assertEquals(value(r,model.saveError),None)
    finally r.close()
  }
  test("removing a window leaves document-owned saves live, while document disposal retires them") {
    val r=new HeadlessRuntime
    try
      val app=r.scope("application");val document=app.child("document");val session=new Session
      val model=new DocumentEditor(document,session);val windows=app.keyed(Seq(1))(identity)
      val mounted=r.mountWindows(app,Windows(windows)((_,_) => Window("Document",MenuBar.empty)(model.view).onCloseRequest {model.requestClose()}))
      app.observe(mounted.snapshot)(_ => ())
      ok(r.transact {model.body.set("saving")});ok(r.transact {model.save.dispatch(())})
      val ticket=r.scheduler.tickets.head.id
      ok(r.transact {windows.remove(1)}) // explicit confirmed close removes the view, not document scope
      assert(!r.scheduler.tickets.head.cancellationRequested)
      finish(r,ticket);assert(!value(r,model.dirty));assertEquals(session.writes.map(_.body),Vector("saving"))
      ok(r.transact {model.body.set("cancelled")});ok(r.transact {model.save.dispatch(())})
      ok(r.transact {document.dispose()})
      assert(r.scheduler.tickets.isEmpty);assertEquals(session.writes.map(_.body),Vector("saving"))
    finally r.close()
  }
  test("save cannot label staged intermediate field values as a committed snapshot") {
    val r=new HeadlessRuntime
    try
      val session=new Session;val model=new DocumentEditor(r.scope("document"),session)
      val result=r.transact {model.body.set("intermediate");model.save.dispatch(())}
      assert(result.isInstanceOf[CommitResult.Failed[?]] || result.isInstanceOf[CommitResult.Refused])
      assert(session.writes.isEmpty);assert(r.scheduler.tickets.isEmpty);assertEquals(value(r,model.body),"initial")
    finally r.close()
  }
  test("shared document validation bounds Unicode, identities and content without normalizing data") {
    assertEquals(DocumentData(id,"  Exact title  ","a\r\nb😀").body,"a\r\nb😀")
    assert(DocumentData.problem("valid","x"*16385).nonEmpty)
    assert(DocumentData.problem("bad\nname","").nonEmpty)
    assert(DocumentData.problem("valid","\ud800").nonEmpty)
    intercept[IllegalArgumentException](DocumentId("../outside"))
    intercept[IllegalArgumentException](DocumentId("/absolute"))
    intercept[IllegalArgumentException](DocumentStamp("missing"))
  }
