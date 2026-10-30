package sirin.desktop

import java.io.IOException
import java.nio.file.{Files,Path,LinkOption}
import sirin.*
import sirin.specimen.*

/** Sequential public Work composition; no privileged execution or silent overwrite. */
final class FileDocumentImporter(target: DocumentRepository) extends DocumentImporter:
  def prepare(source: String): Work[DocumentImportPlan]=Work {
    require(source.nonEmpty && source.length<=2048,"Invalid import directory")
    val path=Path.of(source)
    if !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) then throw new IOException("Import source must be an existing directory.")
    path.toRealPath()
  }.flatMap(path => new FileDocumentRepository(path,createDirectory=false).list("").map(entries => DocumentImportPlan(path.toString,entries)))
  def run(plan: DocumentImportPlan,output: Progress[DocumentImportRecord]): Work[DocumentImportReport] =
    val source=new FileDocumentRepository(Path.of(plan.source),createDirectory=false)
    plan.entries.foldLeft(Work {DocumentImportReport(plan.entries.size,0,0,0)}) { (previous,entry) =>
      previous.flatMap { report =>
        source.open(entry.id).flatMap { session =>
          if session.initial.stamp!=entry.stamp then Work {throw new IOException("Source document changed after review.")}
          else target.create(session.initial.data)
        }.attempt.flatMap { result => Work {
          val outcome=result match
            case Right(session) => DocumentImportOutcome.Created(DocumentSummary(session.initial.data.id,session.initial.data.title,session.initial.stamp))
            case Left(error) => DocumentImportOutcome.Failed(DocumentData.message(error))
          if output.emit(DocumentImportRecord(entry.id,outcome))!=Offered.Accepted then throw new InterruptedException("Import delivery cancelled or closed")
          val created=if result.isRight then 1 else 0
          DocumentImportReport(report.total,report.processed+1,report.created+created,report.failed+1-created)
        }}
      }
    }
