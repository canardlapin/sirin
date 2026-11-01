package sirin.comparison

import javafx.animation.{AnimationTimer, PauseTransition}
import javafx.concurrent.Task
import scalafx.application.Platform
import scalafx.beans.property.{BooleanProperty, IntegerProperty, ObjectProperty, StringProperty}
import scalafx.collections.ObservableBuffer
import java.nio.file.{Files, LinkOption, Path}
import java.util.UUID
import java.util.concurrent.{ArrayBlockingQueue, CancellationException, RejectedExecutionException}
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit}
import scala.collection.mutable
import scala.util.control.NonFatal

final class Draft(val session: StoreSession, changed: () => Unit) extends AutoCloseable:
  val id = session.initial.data.id
  val title = StringProperty(session.initial.data.title)
  val body = StringProperty(session.initial.data.body)
  val dirty = BooleanProperty(false)
  val pending = IntegerProperty(0)
  val problem = ObjectProperty(Option.empty[String])
  val notice = StringProperty("")
  val diskNotice = StringProperty("")
  val status = StringProperty("Saved")
  private var version = 0L
  private var savedVersion = 0L
  private var submitted = 0L
  private var saved = session.initial
  private var live = true
  private def edited(): Unit =
    version = Math.addExact(version, 1L)
    update()
  private val titleSubscription = title.onChange((_, _, _) => edited())
  private val bodySubscription = body.onChange((_, _, _) => edited())
  private def update(): Unit =
    dirty.value = version > savedVersion
    problem.value = Document.problem(title.value, body.value)
    status.value =
      if pending.value > 0 && version > submitted then "Saving; newer edits remain unsaved."
      else if pending.value > 0 then "Saving…"
      else if dirty.value then "Unsaved changes"
      else "Saved"
    changed()
  def revision: Long = version
  def lastSaved: StoredDocument = saved
  def alive: Boolean = live
  def snapshot: Document = Document(id, title.value, body.value)
  def started(revision: Long): Unit =
    pending.value += 1
    submitted = revision
    notice.value = ""
    update()
  def completed(revision: Long, value: StoredDocument): Unit =
    savedVersion = savedVersion.max(revision)
    saved = value
    update()
  def settled(): Unit =
    pending.value -= 1
    update()
  def close(): Unit =
    live = false
    titleSubscription.cancel()
    bodySubscription.cancel()

final case class ImportPlan(source: Path, entries: Vector[Summary])
final case class ImportReceipt(id: String, failure: Option[String])
final case class ImportResult(total: Int, delivered: Int, created: Int, failed: Int, cancelled: Boolean)

/** Application-owned state, independent of window and node lifetime.
  * Tasks receive immutable snapshots; observable state belongs to the FX thread.
  */
final class PersistentWorkspace(
    store: Store,
    freshId: () => String = () => UUID.randomUUID().toString
) extends AutoCloseable:
  private def owner(): Unit = require(Platform.isFxApplicationThread, "FX thread required")
  owner()
  private def executor(name: String, threads: Int, waiting: Int): ThreadPoolExecutor =
    new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](waiting), (work: Runnable) => {
        val thread = new Thread(work, name)
        thread.setDaemon(true)
        thread
      }, new ThreadPoolExecutor.AbortPolicy)
  private val workers = executor("scalafx-documents", 2, 8)
  private val saves = executor("scalafx-saves", 1, 96)
  private val tasks = mutable.Map.empty[Task[?], ThreadPoolExecutor]
  private val documentTasks = mutable.Map.empty[Draft, mutable.Set[Task[?]]]
  private val cache = mutable.LinkedHashMap.empty[String, Draft]
  private var live = true
  private var searchEpoch = 0L
  private var selectionEpoch = 0L
  private var searchTask = Option.empty[Task[?]]
  private var importEpoch = 0L
  private var importTask = Option.empty[Task[?]]
  private var importFinished = false
  private val incoming = new ArrayBlockingQueue[(Long, ImportReceipt)](2)
  val query = StringProperty("")
  val searching = BooleanProperty(false)
  val searchStatus = StringProperty("Search ready")
  val rows = ObservableBuffer.empty[Summary]
  val selected = ObjectProperty(Option.empty[Draft])
  val changed = IntegerProperty(0)
  val notice = StringProperty("")
  val importPlan = ObjectProperty(Option.empty[ImportPlan])
  val importReviewPending = BooleanProperty(false)
  val importReady = BooleanProperty(false)
  val importing = BooleanProperty(false)
  val importStatus = StringProperty("Choose a directory to review.")
  val receipts = ObservableBuffer.empty[ImportReceipt]
  val importHistory = ObservableBuffer.empty[ImportResult]
  private var importUsed = false
  private def changedNow(): Unit = changed.value += 1
  def documents: Vector[Draft] = cache.values.toVector
  def dirtyDocuments: Vector[Draft] = documents.filter(_.dirty.value)
  def isAlive: Boolean = live
  def caption(summary: Summary): String =
    cache.get(summary.id).fold(summary.title) { draft =>
      draft.title.value + (if draft.dirty.value then " *" else "")
    }

  private def cancel(task: Task[?]): Unit =
    task.cancel(true)
    tasks.get(task).foreach(_.remove(task))

  private def submit[A](pool: ThreadPoolExecutor, current: () => Boolean)(
      work: (() => Boolean) => A
  )(success: A => Unit, failure: Throwable => Unit, settled: () => Unit): Option[Task[A]] =
    owner()
    if !live then return None
    val task = new Task[A]:
      def call(): A = work(() => isCancelled)
    def finish(action: => Unit): Unit =
      tasks.remove(task)
      if live && current() then
        try action finally settled()
    task.setOnSucceeded(_ => finish(success(task.getValue)))
    task.setOnFailed(_ => finish(failure(task.getException)))
    task.setOnCancelled(_ => finish(()))
    tasks(task) = pool
    try
      pool.execute(task)
      Some(task)
    catch case error: RejectedExecutionException =>
      tasks.remove(task)
      if live && current() then
        failure(error)
        settled()
      None

  private def fail(error: Throwable): Unit = notice.value = Document.message(error)
  private val quiet = new PauseTransition(javafx.util.Duration.millis(150))
  quiet.setOnFinished(_ => searchNow())
  private val querySubscription = query.onChange { (_, _, _) =>
    searchEpoch += 1
    searchTask.foreach(cancel)
    searching.value = true
    searchStatus.value = "Searching… Previous results remain visible."
    quiet.playFromStart()
  }

  def refresh(): Unit =
    owner()
    quiet.stop()
    searchNow()

  private def searchNow(): Unit =
    owner()
    searchEpoch += 1
    val epoch = searchEpoch
    val input = query.value
    searchTask.foreach(cancel)
    searching.value = true
    searchStatus.value = "Searching… Previous results remain visible."
    searchTask = submit(workers, () => epoch == searchEpoch)(cancelled => store.list(input, cancelled))(
      found => {
        val order = rows.zipWithIndex.map((entry, index) => entry.id -> index).toMap
        rows.setAll(found.zipWithIndex.sortBy((entry, index) =>
          (order.getOrElse(entry.id, Int.MaxValue), index)).map(_._1)*)
        cache.values.foreach { draft =>
          found.find(_.id == draft.id) match
            case Some(summary) =>
              draft.diskNotice.value =
                if summary.stamp != draft.lastSaved.stamp then "Changed on disk; your draft is retained."
                else ""
            case None if input.isEmpty => draft.diskNotice.value = "Not found on disk; your draft is retained."
            case _ => ()
        }
        searchStatus.value = "Search ready"
        changedNow()
      },
      error => searchStatus.value = "Search failed: " + Document.message(error),
      () => searching.value = false
    )

  private def install(session: StoreSession): Draft =
    val id = session.initial.data.id
    cache.remove(id).foreach(retire)
    val draft = new Draft(session, () => changedNow())
    cache(id) = draft
    selected.value = Some(draft)
    changedNow()
    draft

  private def room(): Boolean =
    if cache.size < 32 then true
    else
      notice.value = "Close an open document before opening another."
      false

  def select(id: String): Unit =
    owner()
    selectionEpoch += 1
    val epoch = selectionEpoch
    cache.get(id) match
      case Some(draft) => selected.value = Some(draft)
      case None if room() =>
        notice.value = "Opening document…"
        submit(workers, () => epoch == selectionEpoch)(cancelled => store.open(id, cancelled))(
          session => {
            if cache.size < 32 || cache.contains(id) then
              install(session)
              notice.value = ""
          }, fail, () => ())
      case _ => ()

  def create(): Unit =
    owner()
    if !room() then return
    val data = Document(freshId(), "Untitled", "")
    selectionEpoch += 1
    val epoch = selectionEpoch
    submit(workers, () => true)(cancelled => store.create(data, cancelled))(
      session => {
        if epoch == selectionEpoch && cache.size < 32 then install(session)
        refresh()
      }, fail, () => ())

  def save(draft: Draft): Boolean =
    owner()
    if !draft.alive || !cache.get(draft.id).contains(draft) then return false
    if !draft.dirty.value || draft.problem.value.nonEmpty then return false
    if draft.pending.value >= 3 then
      draft.notice.value = "Save queue full; try again after a save completes."
      return false
    val data = draft.snapshot
    val revision = draft.revision
    draft.started(revision)
    val task = submit(saves, () => draft.alive && cache.get(draft.id).contains(draft))(
      cancelled => draft.session.save(data, cancelled)
    )(
      saved => {
        draft.completed(revision, saved)
        refresh()
      },
      error => draft.notice.value = Document.message(error),
      () => {
        draft.settled()
        documentTasks.get(draft).foreach(_.filterInPlace(task => !task.isDone))
      }
    )
    task.foreach(value => documentTasks.getOrElseUpdate(draft, mutable.Set.empty) += value)
    task.nonEmpty

  def saveCopy(draft: Draft): Unit =
    owner()
    if draft.problem.value.nonEmpty then return
    if !room() then return
    val data = draft.snapshot.copy(id = freshId())
    selectionEpoch += 1
    val epoch = selectionEpoch
    submit(workers, () => true)(cancelled => store.create(data, cancelled))(
      session => {
        if epoch == selectionEpoch && cache.size < 32 then install(session)
        notice.value = "Saved a copy; the original draft is retained."
        refresh()
      }, fail, () => ())

  def reload(draft: Draft, expectedRevision: Long): Unit =
    owner()
    if !draft.alive || draft.revision != expectedRevision then
      notice.value = "The draft changed. Review Reload again."
      return
    submit(workers, () => cache.get(draft.id).contains(draft))(cancelled => store.open(draft.id, cancelled))(
      session => {
        if draft.revision == expectedRevision then
          val wasSelected = selected.value.contains(draft)
          val previous = selected.value
          install(session)
          if !wasSelected then selected.value = previous
        else notice.value = "The draft changed while reloading; it is retained. Review Reload again."
      }, fail, () => ())

  private def retire(draft: Draft): Unit =
    documentTasks.remove(draft).foreach(_.toVector.foreach(cancel))
    draft.close()

  def closeDocument(draft: Draft, expectedRevision: Long): Boolean =
    owner()
    if !cache.get(draft.id).contains(draft) || draft.revision != expectedRevision then
      notice.value = "The draft changed. Review Close again."
      false
    else
      cache.remove(draft.id)
      retire(draft)
      if selected.value.contains(draft) then selected.value = None
      changedNow()
      true

  def move(id: String, delta: Int): Unit =
    owner()
    val index = rows.indexWhere(_.id == id)
    val target = index + delta
    if index >= 0 && target >= 0 && target < rows.size then
      val values = rows.toVector
      rows.setAll(values.updated(index, values(target)).updated(target, values(index))*)

  def reviewImport(source: Path): Unit =
    owner()
    if importing.value then return
    importEpoch += 1
    val epoch = importEpoch
    importTask.foreach(cancel)
    importPlan.value = None
    importReady.value = false
    importUsed = false
    importReviewPending.value = true
    importStatus.value = "Reviewing source…"
    importTask = submit(workers, () => epoch == importEpoch) { cancelled =>
      if !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) then
        throw new IllegalArgumentException("Import source must be an existing directory.")
      val path = source.toRealPath()
      val entries = new DocumentStore(path, createDirectory = false).list("", cancelled)
      require(entries.size <= 128, "Import up to 128 documents at a time.")
      ImportPlan(path, entries)
    }(
      plan => {
        importPlan.value = Some(plan)
        importReady.value = true
        importStatus.value = "Ready to import " + plan.entries.size +
          " documents. Existing IDs will not be replaced."
      },
      error => importStatus.value = Document.message(error),
      () => importReviewPending.value = false
    )

  private def finishImport(cancelled: Boolean): Unit =
    val total = importPlan.value.fold(0)(_.entries.size)
    val failures = receipts.count(_.failure.nonEmpty)
    val result = ImportResult(total, receipts.size, receipts.size - failures, failures, cancelled)
    importHistory += result
    while importHistory.size > 8 do importHistory.remove(0)
    importing.value = false
    pulse.stop()
    importStatus.value = result.delivered.toString + "/" + total + " receipts; " +
      result.created + " created, " + failures + " failed." +
      (if cancelled then " Cancelled; committed files remain. Review again to start a new import." else "")
    refresh()

  private val pulse = new AnimationTimer:
    def handle(now: Long): Unit =
      var remaining = 8
      while remaining > 0 && !incoming.isEmpty do
        val entry = incoming.poll()
        if entry != null && entry._1 == importEpoch then receipts += entry._2
        remaining -= 1
      if importFinished && incoming.isEmpty then finishImport(cancelled = false)

  def startImport(): Boolean =
    owner()
    if importing.value || importUsed || importPlan.value.isEmpty then return false
    val plan = importPlan.value.get
    val epoch = importEpoch
    incoming.clear()
    receipts.clear()
    importFinished = false
    importUsed = true
    importReady.value = false
    importing.value = true
    importStatus.value = "Importing " + plan.entries.size + " documents…"
    importTask = submit(workers, () => epoch == importEpoch) { cancelled =>
      val source = new DocumentStore(plan.source, createDirectory = false)
      plan.entries.foreach { entry =>
        if cancelled() then throw new CancellationException
        val failure = try
          val loaded = source.open(entry.id, cancelled).initial
          if loaded.stamp != entry.stamp then throw new IllegalStateException("Source changed after review.")
          store.create(loaded.data, cancelled)
          None
        catch
          case error: CancellationException => throw error
          case NonFatal(error) => Some(Document.message(error))
        if cancelled() then throw new CancellationException
        incoming.put(epoch -> ImportReceipt(entry.id, failure))
      }
    }(
      _ => (),
      error => notice.value = "Import stopped: " + Document.message(error),
      () => importFinished = true
    )
    pulse.start()
    importTask.nonEmpty

  def cancelImport(): Unit =
    owner()
    if importing.value then
      importEpoch += 1
      importTask.foreach(cancel)
      val abandoned = incoming.size()
      incoming.clear()
      finishImport(cancelled = true)
      notice.value = "Cancelled import; " + abandoned +
        " queued receipts abandoned. Refresh includes committed files."
    else if importReviewPending.value then
      importEpoch += 1
      importTask.foreach(cancel)
      importReviewPending.value = false
      importStatus.value = "Review cancelled."

  def close(): Unit =
    owner()
    if live then
      live = false
      quiet.stop()
      pulse.stop()
      querySubscription.cancel()
      tasks.keys.toVector.foreach(cancel)
      tasks.clear()
      cache.values.foreach(_.close())
      cache.clear()
      documentTasks.clear()
      incoming.clear()
      workers.shutdownNow()
      saves.shutdownNow()
  refresh()
