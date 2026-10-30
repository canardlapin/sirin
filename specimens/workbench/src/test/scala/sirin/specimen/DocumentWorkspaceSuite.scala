package sirin.specimen

import sirin.*
import sirin.engine.*
import scala.concurrent.duration.*

class DocumentWorkspaceSuite extends munit.FunSuite:
  private val a=DocumentId("first");private val b=DocumentId("second")
  private class Repository extends DocumentRepository:
    private var serial=0
    private var disk=Map.empty[DocumentId,LoadedDocument]
    def write(data: DocumentData): LoadedDocument=this.synchronized {
      serial+=1;val loaded=LoadedDocument(data,DocumentStamp(f"$serial%064x"));disk=disk.updated(data.id,loaded);loaded
    }
    write(DocumentData(a,"First","original"));write(DocumentData(b,"Second","other"))
    def read(id: DocumentId): LoadedDocument=this.synchronized {disk(id)}
    def list(query: String): Work[Vector[DocumentSummary]]=Work {this.synchronized {
      disk.values.toVector.filter(d => (d.data.title+d.data.body).toLowerCase.contains(query.toLowerCase)).sortBy(_.data.id.value)
        .map(d => DocumentSummary(d.data.id,d.data.title,d.stamp))
    }}
    private class Session(val initial: LoadedDocument) extends DocumentSession:
      private var expected=initial.stamp
      def save(data: DocumentData): Work[LoadedDocument]=Work {Repository.this.synchronized {
        val actual=disk.get(data.id).map(_.stamp)
        if !actual.contains(expected) then throw new DocumentConflict(data.id,Some(expected),actual)
        val result=write(data);expected=result.stamp;result
      }}
    def create(data: DocumentData): Work[DocumentSession]=Work {this.synchronized {
      if disk.contains(data.id) then throw new DocumentConflict(data.id,None,Some(disk(data.id).stamp))
      new Session(write(data))
    }}
    def open(id: DocumentId): Work[DocumentSession]=Work {new Session(read(id))}
  private class Importer extends DocumentImporter:
    def prepare(source: String): Work[DocumentImportPlan]=Work {
      val entry=DocumentSummary(DocumentId("imported"),source,DocumentStamp("0"*64))
      DocumentImportPlan(source,Vector(entry))
    }
    def run(plan: DocumentImportPlan,output: Progress[DocumentImportRecord]): Work[DocumentImportReport]=Work {
      plan.entries.foreach { entry =>
        if output.emit(DocumentImportRecord(entry.id,DocumentImportOutcome.Created(entry)))!=Offered.Accepted then throw new InterruptedException("cancelled")
      }
      DocumentImportReport(plan.entries.size,plan.entries.size,plan.entries.size,0)
    }
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def value[A](r: HeadlessRuntime,signal: Signal[A]): A=r.read(signal) match
    case Value.Ok(result) => result
    case Value.Failed(error) => throw error
  private class Fixture extends AutoCloseable:
    val clock=new ManualClock;val r=new HeadlessRuntime(clock=clock);val app=r.scope("application")
    val repository=new Repository;val quitRequests=app.cell(0);var generated=0
    val services=new ApplicationServices:
      val quit=app.action[Unit]("quit","Quit")(_ => quitRequests.update(_+1))
    val model=new DocumentWorkspace(app,repository,new Importer,services,() => {generated+=1;DocumentId(s"new-$generated")})
    val mounted=r.mountWindows(app,model.windows);app.observe(mounted.snapshot)(_ => ())
    ok(r.transact {mounted.setViewport(RowViewport(0.lp,200.lp))})
    def settle(): Unit =
      var turns=0;var done=false
      while !done && turns<100 do
        val receipt=r.drive(64);assert(receipt.failures.isEmpty,s"$receipt")
        r.scheduler.tickets.filter(t => t.ready && !t.completed).foreach(t => r.scheduler.run(t.id))
        done=r.scheduler.tickets.isEmpty && !receipt.budgetExceeded
        turns+=1
      assert(done,s"workspace did not settle: ${r.scheduler.tickets}")
    settle()
    def open(id: DocumentId): WorkspaceDocument =
      ok(r.transact {model.open.dispatch(id)});settle()
      ok(r.transact {model.documents.get(id).get})
    def selected: Option[DocumentId]=value(r,model.selected)
    def bodyIds(document: WorkspaceDocument): Vector[Long] =
      def loop(snapshot: MountedSnapshot): Vector[Long]=snapshot match
        case MountedSnapshot.Control(id,field: TextField.Description) if field.value eq document.editor.body => Vector(id)
        case MountedSnapshot.Group(children,_,_,_) => children.flatMap(loop)
        case MountedSnapshot.Component(_,_,child) => loop(child)
        case MountedSnapshot.Window(_,_,_,child) => loop(child)
        case MountedSnapshot.Styled(child,_) => loop(child)
        case MountedSnapshot.Rows(rows) => rows.flatMap((_,child) => loop(child))
        case MountedSnapshot.Branch(_,_,child) => loop(child)
        case MountedSnapshot.Outlet(_,child) => loop(child)
        case MountedSnapshot.Reparented(_,_,child) => loop(child)
        case MountedSnapshot.Scroll(_,child) => loop(child)
        case MountedSnapshot.VirtualRows(_,_,_,_,_,_,_,child) => loop(child)
        case _ => Vector.empty
      loop(value(r,mounted.snapshot))
    def close(): Unit=r.close()
  test("visible workspace retains drafts and actual field identities through refresh, reorder, filter and outlet move") {
    val f=new Fixture
    try
      val doc=f.open(a);val ids=f.bodyIds(doc);assertEquals(ids.size,1)
      ok(f.r.transact {doc.editor.body.set("my retained draft");f.model.moveRow.dispatch(a -> 1);f.model.moveEditor.dispatch(())})
      f.repository.write(DocumentData(a,"Refreshed title","external"))
      ok(f.r.transact {f.model.refresh.dispatch(())});f.settle()
      assertEquals(value(f.r,doc.editor.body),"my retained draft");assert(value(f.r,doc.diskNotice).nonEmpty)
      assertEquals(f.bodyIds(doc),ids)
      assertEquals(value(f.r,f.model.rows.snapshot).map(_.id),Vector(b,a))
      ok(f.r.transact {f.model.query.set("Second")});f.clock.advanceBy(200.millis);f.settle()
      assertEquals(value(f.r,f.model.rows.snapshot).map(_.id),Vector(b));assertEquals(f.selected,Some(a))
      assertEquals(f.bodyIds(doc),ids)
    finally f.close()
  }
  test("an obsolete file open cannot steal selection or overwrite an already-open draft") {
    val f=new Fixture
    try
      val first=f.open(a)
      ok(f.r.transact {first.editor.body.set("retained")})
      ok(f.r.transact {f.model.open.dispatch(b)})
      ok(f.r.transact {f.model.open.dispatch(a)})
      f.settle()
      assertEquals(f.selected,Some(a));assertEquals(value(f.r,first.editor.body),"retained")
      assertEquals(ok(f.r.transact {f.model.documents.size}),1)
    finally f.close()
  }
  test("reload requires a fresh decision and rejects edits made while disk I/O is pending") {
    val f=new Fixture
    try
      val doc=f.open(a)
      f.repository.write(DocumentData(a,"New title","disk replacement"))
      ok(f.r.transact {f.model.reloadSelected.dispatch(())})
      ok(f.r.transact {doc.editor.body.set("new edit after prompt")})
      ok(f.r.transact {f.model.confirmDecision.dispatch(())})
      assert(value(f.r,f.model.prompt).nonEmpty)
      ok(f.r.transact {f.model.confirmDecision.dispatch(())})
      ok(f.r.transact {doc.editor.body.set("new edit during load")})
      f.settle()
      assertEquals(value(f.r,doc.editor.body),"new edit during load")
      assert(ok(f.r.transact {f.model.documents.get(a).get}) eq doc)
      assert(value(f.r,f.model.notice).exists(_.contains("newer edit")))
    finally f.close()
  }
  test("accepted reload replaces the document scope and retargets additional views under stable window keys") {
    val f=new Fixture
    try
      val old=f.open(a)
      ok(f.r.transact {f.model.openView.dispatch(())});f.settle()
      val viewIds=value(f.r,f.model.views.snapshot).map(_.id)
      f.repository.write(DocumentData(a,"Reloaded","disk content"))
      ok(f.r.transact {f.model.reloadSelected.dispatch(())});ok(f.r.transact {f.model.confirmDecision.dispatch(())});f.settle()
      val fresh=ok(f.r.transact {f.model.documents.get(a).get})
      assert(fresh ne old);assertEquals(value(f.r,fresh.editor.body),"disk content")
      assertEquals(value(f.r,f.model.views.snapshot).map(_.id),viewIds)
      assert(value(f.r,f.model.views.snapshot).flatMap(_.document).forall(_ eq fresh))
      intercept[KernelError](value(f.r,old.editor.body))
    finally f.close()
  }
  test("zero-window drafts and saves survive; application quit reopens a decision surface and resumes only after consent") {
    val f=new Fixture
    try
      val doc=f.open(a);val ids=f.bodyIds(doc)
      ok(f.r.transact {doc.editor.body.set("17")});ok(f.r.transact {doc.editor.save.dispatch(())})
      ok(f.r.transact {doc.editor.body.set("18")})
      assertEquals(ok(f.r.transact {f.model.requestWindowClose(1)}),CloseDecision.KeepOpen)
      ok(f.r.transact {f.model.confirmDecision.dispatch(())});assertEquals(ok(f.r.transact {f.model.views.size}),0)
      f.settle();assertEquals(f.repository.read(a).data.body,"17");assert(value(f.r,doc.editor.dirty))
      assertEquals(ok(f.r.transact {f.mounted.requestApplicationQuit()}),CloseDecision.KeepOpen)
      assertEquals(ok(f.r.transact {f.model.views.size}),1)
      assertEquals(f.bodyIds(doc),ids)
      ok(f.r.transact {doc.editor.body.set("newest")});ok(f.r.transact {f.model.confirmDecision.dispatch(())})
      assertEquals(value(f.r,f.quitRequests),0);assert(value(f.r,f.model.prompt).nonEmpty)
      ok(f.r.transact {f.model.confirmDecision.dispatch(())})
      assertEquals(value(f.r,f.quitRequests),1)
      assertEquals(ok(f.r.transact {f.mounted.requestApplicationQuit()}),CloseDecision.Allow)
    finally f.close()
  }
  test("saving a copy freezes its snapshot and preserves newer edits in the original document") {
    val f=new Fixture
    try
      val original=f.open(a)
      ok(f.r.transact {original.editor.body.set("copy snapshot")})
      ok(f.r.transact {f.model.saveCopy.dispatch(())})
      ok(f.r.transact {original.editor.body.set("newer original")});f.settle()
      val copy=ok(f.r.transact {f.model.documents.get(DocumentId("new-1")).get})
      assertEquals(value(f.r,copy.editor.body),"copy snapshot")
      assertEquals(value(f.r,original.editor.body),"newer original");assert(value(f.r,original.editor.dirty))
      assertEquals(f.repository.read(a).data.body,"original")
      assertEquals(f.repository.read(copy.id).data.body,"copy snapshot")
    finally f.close()
  }
  test("reviewed import shows its denominator, complete receipts and a retained one-shot result") {
    val f=new Fixture
    try
      ok(f.r.transact {f.model.importSource.set("source")})
      ok(f.r.transact {f.model.prepareImport.dispatch(())});f.settle()
      val imported=value(f.r,f.model.imports).last
      assertEquals(imported.plan.entries.size,1)
      ok(f.r.transact {imported.start.dispatch(())});f.settle()
      assertEquals(value(f.r,imported.records).size,1)
      assertEquals(value(f.r,imported.report),Some(DocumentImportReport(1,1,1,0)))
      assertEquals(value(f.r,imported.channel.state),ChannelState.Drained)
      assertEquals(ok(f.r.transact {imported.start.dispatch(())}),Dispatched.Refused("command disabled"))
    finally f.close()
  }
  test("closing a dirty document invalidates stale confirmation authority before releasing its scope") {
    val f=new Fixture
    try
      val doc=f.open(a)
      ok(f.r.transact {doc.editor.body.set("draft")});ok(f.r.transact {f.model.closeDocument.dispatch(())})
      ok(f.r.transact {doc.editor.body.set("newer")});ok(f.r.transact {f.model.confirmDecision.dispatch(())})
      assertEquals(ok(f.r.transact {f.model.documents.size}),1)
      ok(f.r.transact {f.model.confirmDecision.dispatch(())});f.settle()
      assertEquals(ok(f.r.transact {f.model.documents.size}),0);assertEquals(f.selected,None)
      intercept[KernelError](value(f.r,doc.editor.body))
    finally f.close()
  }

  test("import has its own reopenable view and quitting cancels active import without deleting receipts") {
    val f=new Fixture
    try
      ok(f.r.transact {f.model.showImport.dispatch(())})
      val first=value(f.r,f.model.views.snapshot).find(_.mode==WorkspaceViewMode.Import).get
      assertEquals(ok(f.r.transact {f.model.requestWindowClose(first.id)}),CloseDecision.Allow)
      ok(f.r.transact {f.model.showImport.dispatch(())})
      assert(value(f.r,f.model.views.snapshot).exists(v => v.mode==WorkspaceViewMode.Import && v.id!=first.id))
      ok(f.r.transact {f.model.importSource.set("source")});ok(f.r.transact {f.model.prepareImport.dispatch(())});f.settle()
      val session=value(f.r,f.model.imports).last
      ok(f.r.transact {session.start.dispatch(())})
      assertEquals(ok(f.r.transact {f.model.requestApplicationQuit()}),CloseDecision.Allow)
      f.settle()
      assert(value(f.r,session.channel.state).isInstanceOf[ChannelState.Cancelled])
      assertEquals(value(f.r,f.model.imports).size,1)
    finally f.close()
  }
