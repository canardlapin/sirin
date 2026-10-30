package sirin.desktop

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files,Path,StandardOpenOption}
import scala.jdk.CollectionConverters.*
import sirin.*
import sirin.engine.*
import sirin.specimen.*

class FileDocumentRepositorySuite extends munit.FunSuite:
  private val live=new Cancellation {def cancelled=false}
  private def run[A](work: Work[A],c: Cancellation=live): A=Work.execute(work,c)
  private def fixture(body: Path => Unit): Unit =
    val root=Files.createTempDirectory("sirin-document-test-")
    try body(root)
    finally
      val walk=Files.walk(root)
      try walk.iterator().asScala.toVector.reverse.foreach(Files.delete(_)) finally walk.close()
  private val id=DocumentId("first")
  private def data(body: String="initial")=DocumentData(id,"  Exact café 日本語  ",body)
  private def files(root: Path): Vector[Path] =
    val stream=Files.newDirectoryStream(root)
    try stream.iterator().asScala.toVector finally stream.close()
  private def noTemps(root: Path): Unit=assert(!files(root).exists(_.getFileName.toString.endsWith(".pending")))
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def value[A](r: HeadlessRuntime,signal: Signal[A]): A=r.read(signal) match
    case Value.Ok(result) => result
    case Value.Failed(error) => throw error
  test("work descriptions have no I/O; files reopen exactly in a fresh repository and search their content") {
    fixture { parent =>
      val root=parent.resolve("documents");val repo=new FileDocumentRepository(root)
      val create=repo.create(data("a\r\nb😀 日本語"));assert(!Files.exists(root))
      val first=run(create).initial
      assertEquals(first.data,data("a\r\nb😀 日本語"))
      val fresh=new FileDocumentRepository(root)
      assertEquals(run(fresh.open(id)).initial,first)
      assertEquals(run(fresh.list("日本語")).map(_.id),Vector(id))
      assertEquals(run(fresh.list("absent")),Vector.empty)
      assertEquals(run(fresh.list("CAFÉ")).map(_.title),Vector(first.data.title))
      noTemps(root)
    }
  }
  test("real queued saves persist revision 17 then 18 while newer edits stay dirty") {
    fixture { root =>
      val repo=new FileDocumentRepository(root);val session=run(repo.create(data()))
      val r=new HeadlessRuntime
      try
        val model=new DocumentEditor(r.scope("document"),session)
        ok(r.transact {model.body.set("17")});ok(r.transact {model.save.dispatch(())})
        ok(r.transact {model.body.set("18")});ok(r.transact {model.save.dispatch(())})
        val tickets=r.scheduler.tickets
        r.scheduler.run(tickets.head.id);ok(r.scheduler.deliver(tickets.head.id))
        assertEquals(run(repo.open(id)).initial.data.body,"17");assert(value(r,model.dirty))
        r.scheduler.run(tickets.last.id);ok(r.scheduler.deliver(tickets.last.id))
        assertEquals(run(new FileDocumentRepository(root).open(id)).initial.data.body,"18")
        assert(!value(r,model.dirty));assertEquals(value(r,model.saveError),None);noTemps(root)
      finally r.close()
    }
  }
  test("independent sessions detect external writes, preserve drafts/files and allow explicit reopen") {
    fixture { root =>
      val repo=new FileDocumentRepository(root);val first=run(repo.create(data()))
      val second=run(new FileDocumentRepository(root).open(id))
      val written=run(first.save(data("external")))
      val conflict=intercept[DocumentConflict](run(second.save(data("my draft"))))
      assertEquals(conflict.expected,Some(second.initial.stamp));assertEquals(conflict.actual,Some(written.stamp))
      assertEquals(run(repo.open(id)).initial,written)
      val reopened=run(repo.open(id));run(reopened.save(data("accepted reload")))
      assertEquals(run(repo.open(id)).initial.data.body,"accepted reload")
      intercept[DocumentConflict](run(repo.create(data("do not replace"))))
      assertEquals(run(repo.open(id)).initial.data.body,"accepted reload");noTemps(root)
    }
  }
  test("external deletion conflicts and data symlinks cannot read or overwrite their targets") {
    fixture { root =>
      val repo=new FileDocumentRepository(root);val session=run(repo.create(data()))
      val file=root.resolve("first.sirin-doc");Files.delete(file)
      val conflict=intercept[DocumentConflict](run(session.save(data("retain"))))
      assertEquals(conflict.actual,None);assert(!Files.exists(file))
      val outside=root.resolve("outside");Files.writeString(outside,"retain outside")
      Files.createSymbolicLink(file,outside)
      intercept[IOException](run(repo.open(id)))
      intercept[IOException](run(session.save(data("retain"))))
      assertEquals(Files.readString(outside),"retain outside");noTemps(root)
    }
  }
  test("strict format rejects malformed UTF-8, versions, trailing data, oversized files and wrong identities") {
    fixture { root =>
      val repo=new FileDocumentRepository(root);run(repo.create(data("x")))
      val file=root.resolve("first.sirin-doc");val original=Files.readAllBytes(file)
      val malformed=original.clone();malformed(malformed.length-1)=0xff.toByte
      val version=original.clone();ByteBuffer.wrap(version).putInt(8,99)
      for bytes <- Vector(malformed,version,original++Array[Byte](1),new Array[Byte](131073)) do
        Files.write(file,bytes);intercept[IOException](run(repo.open(id)))
      Files.write(file,original)
      Files.copy(file,root.resolve("other.sirin-doc"))
      intercept[IOException](run(repo.open(DocumentId("other"))))
      noTemps(root)
    }
  }
  test("cancellation after temporary write leaves no published document or pending file") {
    fixture { root =>
      var sawWrittenTemp=false
      val cancel=new Cancellation:
        def cancelled: Boolean =
          sawWrittenTemp=files(root).exists(p => p.getFileName.toString.endsWith(".pending") && Files.size(p)>0)
          sawWrittenTemp
      val repo=new FileDocumentRepository(root)
      var stopped=false
      try run(repo.create(data("cancelled")),cancel)
      catch case _: InterruptedException => stopped=true
      assert(stopped)
      assert(sawWrittenTemp);assert(!Files.exists(root.resolve("first.sirin-doc")));noTemps(root)
    }
  }
  test("a cooperating writer's lock causes a recoverable refusal without touching the file") {
    fixture { root =>
      val repo=new FileDocumentRepository(root);val session=run(repo.create(data()))
      val channel=FileChannel.open(root.resolve("first.sirin-lock"),StandardOpenOption.WRITE)
      try
        val held=channel.lock()
        try intercept[DocumentBusy](run(session.save(data("busy")))) finally held.release()
      finally channel.close()
      assertEquals(run(repo.open(id)).initial,session.initial);noTemps(root)
    }
  }
  test("document collection budget refuses the 513th creation without deleting any document") {
    fixture { root =>
      val repo=new FileDocumentRepository(root)
      (1 to 512).foreach(i => run(repo.create(DocumentData(DocumentId(s"doc-$i"),s"Document $i",""))))
      intercept[IOException](run(repo.create(DocumentData(DocumentId("one-too-many"),"Excess",""))))
      assertEquals(run(repo.list("")).size,512);assert(!Files.exists(root.resolve("one-too-many.sirin-doc")));noTemps(root)
    }
  }
