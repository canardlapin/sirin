package sirin.specimen

import sirin.*

final case class DocumentId(value: String):
  require(value.matches("[a-z0-9][a-z0-9-]{0,63}"),"Invalid document ID")
final case class DocumentStamp(value: String):
  require(value.matches("[0-9a-f]{64}"),"Invalid document stamp")
final case class DocumentData(id: DocumentId,title: String,body: String):
  require(DocumentData.problem(title,body).isEmpty,"Invalid document data")
object DocumentData:
  /** Bounded UI preview; never split a surrogate pair introduced by truncation. */
  def preview(value: String,limit: Int=512): String =
    require(limit>=0,"Negative preview limit")
    val clipped=value.take(limit)
    if value.length>limit && clipped.lastOption.exists(Character.isHighSurrogate) then clipped.dropRight(1) else clipped
  def message(error: Throwable): String=preview(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
  def unicode(value: String): Boolean =
    var i=0
    while i<value.length do
      val c=value.charAt(i)
      if Character.isHighSurrogate(c) then
        i+=1
        if i>=value.length || !Character.isLowSurrogate(value.charAt(i)) then return false
      else if Character.isLowSurrogate(c) then return false
      i+=1
    true
  def problem(title: String,body: String): Option[String] =
    if title.trim.isEmpty then Some("A title is required.")
    else if title.length>256 || title.exists(_.isControl) then Some("Use a title of at most 256 characters on one line.")
    else if body.length>16384 then Some("This document exceeds the 16,384-character editing limit.")
    else if body.contains('\u0000') || !unicode(title) || !unicode(body) then Some("The document contains invalid text.")
    else None
final case class LoadedDocument(data: DocumentData,stamp: DocumentStamp)
final case class DocumentSummary(id: DocumentId,title: String,stamp: DocumentStamp)
trait DocumentSession:
  def initial: LoadedDocument
  /** Serialized against this session's own last successful write; detects external changes. */
  def save(data: DocumentData): Work[LoadedDocument]
trait DocumentRepository:
  def list(query: String): Work[Vector[DocumentSummary]]
  def create(data: DocumentData): Work[DocumentSession]
  def open(id: DocumentId): Work[DocumentSession]
final class DocumentConflict(val id: DocumentId,val expected: Option[DocumentStamp],val actual: Option[DocumentStamp])
    extends RuntimeException("This document changed on disk. Reload it or save a copy before continuing.")
final class DocumentBusy extends RuntimeException("This document is being saved elsewhere. Try again.")

final case class DocumentDraft(title: Versioned[String],body: Versioned[String])

/** Own this in the application/document scope; windows only render its view. */
final class DocumentEditor(scope: Scope,session: DocumentSession):
  val id=session.initial.data.id
  val title=scope.cell(session.initial.data.title)
  val body=scope.cell(session.initial.data.body)
  private val saved=scope.cell((Revision.initial,Revision.initial))
  private val submitted=scope.cell((Revision.initial,Revision.initial))
  val lastSaved=scope.cell(session.initial)
  val saveError=scope.cell(Option.empty[Throwable])
  val closePrompt=scope.cell(false)
  val titleError=title.map(DocumentData.problem(_,""))
  val bodyError=body.map(DocumentData.problem("Document",_))
  val validation=title.zip(body).map(DocumentData.problem)
  private val revisions=title.versioned.zip(body.versioned).map((t,b) => (t.revision,b.revision))
  val dirty=revisions.zip(saved).map((now,before) => now._1.isAfter(before._1) || now._2.isAfter(before._2))
  val save=scope.command[Unit,DocumentDraft,LoadedDocument]("document.save","Save",WorkPolicy.save(Capacity(2)),
    _ => dirty.zip(validation).map((changed,error) => changed && error.isEmpty)) { _ =>
    val snapshot=DocumentDraft(title.snapshot,body.snapshot)
    val data=DocumentData(id,snapshot.title.value,snapshot.body.value)
    submitted.set((snapshot.title.revision,snapshot.body.revision));saveError.set(None)
    Job(snapshot,session.save(data))
  } { (snapshot,result) => result.outcome match
    case Outcome.Succeeded(written) =>
      saved.update((a,b) => (a.max(snapshot.title.revision),b.max(snapshot.body.revision)))
      lastSaved.set(written)
    case Outcome.Failed(error) => saveError.set(Some(error))
    case Outcome.Cancelled => ()
  }
  val newerThanSubmitted=revisions.zip(submitted).map((now,before) => now._1.isAfter(before._1) || now._2.isAfter(before._2))
  val status=dirty.zip(save.running).zip(newerThanSubmitted).map { case ((changed,running),newer) =>
    if running>0 && newer then "Saving; newer edits remain unsaved."
    else if running>0 then "Saving…"
    else if changed then "Unsaved changes" else "Saved"
  }
  val notice=saveError.zip(save.lastRefusal).map { (error,refusal) =>
    error.map(DocumentData.message).orElse(refusal.map(DocumentData.preview(_)))
  }
  def requestClose()(using UiTxn): CloseDecision =
    if dirty.get then {closePrompt.set(true);CloseDecision.KeepOpen} else CloseDecision.Allow
  val view: View=Column(
    TextField.bind(title).maxLength(256).label("Title").error(titleError),
    TextField.bind(body).label("Document").error(bodyError).multiline.height(260.lp),
    Row(Button(save).primary,Text(status)).spacing(12.lp),
    Text(notice.map(_.getOrElse(""))).color(ColorRole.Danger)
  ).spacing(12.lp).padding(16.lp)
