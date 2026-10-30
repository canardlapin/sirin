package sirin.desktop

import java.nio.file.{Files,Path}
import scala.jdk.CollectionConverters.*
import sirin.*
import sirin.engine.*
import sirin.specimen.*

class FileDocumentImporterSuite extends munit.FunSuite:
  private val live=new Cancellation {def cancelled=false}
  private def execute[A](work: Work[A]): A=Work.execute(work,live)
  private def value[A](r: HeadlessRuntime,signal: Signal[A]): A=r.read(signal) match
    case Value.Ok(result) => result
    case Value.Failed(error) => throw error
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def fixture(body: Path => Unit): Unit =
    val root=Files.createTempDirectory("sirin-import-test-")
    try body(root)
    finally
      val walk=Files.walk(root)
      try walk.iterator().asScala.toVector.reverse.foreach(Files.delete(_)) finally walk.close()
  private def waitFor(r: HeadlessRuntime)(done: => Boolean): Unit =
    val deadline=System.nanoTime()+5000000000L
    while !done && System.nanoTime()<deadline do
      val turn=r.drive(32);assert(turn.failures.isEmpty,s"$turn")
      if !done then r.awaitActivity(10)
    assert(done,"import did not settle")
  private def data(i: Int)=DocumentData(DocumentId(s"doc-$i"),s"Document $i",s"body $i café 日本語")
  test("real import preserves existing target files and accounts for every reviewed entry") {
    fixture { root =>
      val source=new FileDocumentRepository(root.resolve("source"));val target=new FileDocumentRepository(root.resolve("target"))
      (1 to 3).foreach(i => execute(source.create(data(i))))
      val existing=execute(target.create(data(1).copy(body="keep existing"))).initial
      val importer=new FileDocumentImporter(target);val plan=execute(importer.prepare(root.resolve("source").toString))
      val r=new HeadlessRuntime;val pool=r.scheduler.startWorkers(2)
      try
        val owner=r.scope("import");val refreshes=owner.cell(0)
        val session=new WorkspaceImport(owner,plan,importer,() => refreshes.update(_+1))
        ok(r.transact {session.start.dispatch(())})
        waitFor(r)(value(r,session.channel.state)==ChannelState.Drained && value(r,session.run.running)==0 && value(r,refreshes)>0)
        assertEquals(value(r,session.report),Some(DocumentImportReport(3,3,2,1)))
        assertEquals(value(r,session.records).map(_.id).toSet,plan.entries.map(_.id).toSet)
        assertEquals(execute(target.open(data(1).id)).initial,existing)
        assertEquals(execute(target.open(data(2).id)).initial.data,data(2))
        assertEquals(execute(source.open(data(1).id)).initial.data,data(1))
      finally {r.close();assert(pool.awaitStopped(5000))}
    }
  }
  test("a changed source fails its frozen entry instead of importing a different generation") {
    fixture { root =>
      val source=new FileDocumentRepository(root.resolve("source"));val target=new FileDocumentRepository(root.resolve("target"))
      val original=execute(source.create(data(1)));val importer=new FileDocumentImporter(target)
      val plan=execute(importer.prepare(root.resolve("source").toString))
      execute(original.save(data(1).copy(body="changed after review")))
      val records=scala.collection.mutable.ArrayBuffer.empty[DocumentImportRecord]
      val output=new Progress[DocumentImportRecord]:
        def tryEmit(record: DocumentImportRecord)(using scala.util.NotGiven[UiTxn]): Offered={records+=record;Offered.Accepted}
        def emit(record: DocumentImportRecord)(using Cancellation,scala.util.NotGiven[UiTxn]): Offered={records+=record;Offered.Accepted}
      assertEquals(execute(importer.run(plan,output)),DocumentImportReport(1,1,0,1))
      assert(records.head.outcome.isInstanceOf[DocumentImportOutcome.Failed])
      assert(execute(target.list("")).isEmpty)
    }
  }
  test("cancelling a blocked import retains committed files and reports abandoned receipts separately") {
    fixture { root =>
      val source=new FileDocumentRepository(root.resolve("source"));val target=new FileDocumentRepository(root.resolve("target"))
      (1 to 8).foreach(i => execute(source.create(data(i))))
      val importer=new FileDocumentImporter(target);val plan=execute(importer.prepare(root.resolve("source").toString))
      val r=new HeadlessRuntime;val pool=r.scheduler.startWorkers(2)
      try
        val owner=r.scope("import");val refreshed=owner.cell(false)
        val session=new WorkspaceImport(owner,plan,importer,() => refreshed.set(true))
        ok(r.transact {session.start.dispatch(())})
        val deadline=System.nanoTime()+5000000000L
        while r.progressQueues.head.pending<2 && System.nanoTime()<deadline do r.awaitActivity(10)
        assertEquals(r.progressQueues.head.pending,2)
        ok(r.transact {session.cancel.dispatch(())})
        waitFor(r)(value(r,session.run.running)==0 && value(r,refreshed))
        assertEquals(value(r,session.channel.state),ChannelState.Cancelled(2))
        assertEquals(value(r,session.records),Vector.empty)
        assertEquals(value(r,session.report),None)
        val retained=execute(target.list("")).size
        assert(retained>=2 && retained<=3,s"expected two receipts plus at most one in-flight write, found $retained files")
        assert(value(r,session.status).contains("0/8"));assert(value(r,session.status).contains("Files already written are retained"))
      finally {r.close();assert(pool.awaitStopped(5000))}
    }
  }
  test("missing sources are not created during review or after their reviewed directory disappears") {
    fixture { root =>
      val missing=root.resolve("missing");val target=new FileDocumentRepository(root.resolve("target"))
      val importer=new FileDocumentImporter(target)
      intercept[java.io.IOException](execute(importer.prepare(missing.toString)))
      assert(!Files.exists(missing))
      val sourcePath=root.resolve("source");val source=new FileDocumentRepository(sourcePath)
      execute(source.create(data(1)))
      val plan=execute(importer.prepare(sourcePath.toString))
      val walk=Files.walk(sourcePath)
      try walk.iterator().asScala.toVector.reverse.foreach(Files.delete(_)) finally walk.close()
      val output=new Progress[DocumentImportRecord]:
        def tryEmit(record: DocumentImportRecord)(using scala.util.NotGiven[UiTxn]): Offered=Offered.Accepted
        def emit(record: DocumentImportRecord)(using Cancellation,scala.util.NotGiven[UiTxn]): Offered=Offered.Accepted
      assertEquals(execute(importer.run(plan,output)),DocumentImportReport(1,1,0,1))
      assert(!Files.exists(sourcePath))
    }
  }
