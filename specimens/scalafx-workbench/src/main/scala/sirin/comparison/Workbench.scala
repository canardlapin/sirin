package sirin.comparison

import scalafx.application.Platform
import scalafx.beans.property.{BooleanProperty, ObjectProperty, StringProperty}
import scalafx.collections.ObservableBuffer
import javafx.animation.AnimationTimer
import javafx.concurrent.Task
import java.util.concurrent.{ArrayBlockingQueue, CancellationException, ThreadPoolExecutor, TimeUnit}
import scala.collection.mutable

final case class Record(id: Int, title: String)
final class Row(initial: Record):
  val id = initial.id
  val title = StringProperty(initial.title)
  val draft = StringProperty("")
  val editing = BooleanProperty(false)
  private val base = StringProperty("")
  val conflict = BooleanProperty(false)
  conflict <== editing && (title =!= base)
  def record: Record = Record(id, title.value)
  def refresh(record: Record): Unit = title.value = record.title
  def begin(): Unit = { base.value = title.value; draft.value = base.value; editing.value = true }
  def commit(): Unit =
    require(draft.value.trim.nonEmpty, "A title is required")
    title.value = draft.value.trim
    editing.value = false

trait Services:
  def search(query: String, cancelled: () => Boolean): Vector[Record]
  def save(text: String): Unit
  def importRows(emit: Record => Unit, cancelled: () => Boolean): Int

/** ScalaFX/JavaFX controller baseline. Construct and close on the FX thread.
  * Uses Task, properties, bounded executors and a pulse-drained FIFO, rather
  * than recreating Sirin's transaction or component framework.
  */
final class Workbench(services: Services) extends AutoCloseable:
  private def owner(): Unit = require(Platform.isFxApplicationThread, "FX thread required")
  private def executor(threads: Int, waiting: Int): ThreadPoolExecutor =
    new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](waiting), (r: Runnable) => {
        val thread = new Thread(r, "workbench")
        thread.setDaemon(true)
        thread
      }, new ThreadPoolExecutor.AbortPolicy)
  owner()
  private val workers = executor(2, 8)
  private val saves = executor(1, 2)
  private val tasks = mutable.Set.empty[Task[?]]
  private var alive = true
  private var generation = 0L
  private var revision = 0L
  private var savedRevision = 0L
  private var searchTask = Option.empty[Task[Vector[Record]]]
  private var importTask = Option.empty[Task[Int]]
  private val incoming = new ArrayBlockingQueue[(Long, Record)](2)
  private var importGeneration = 0L
  val query = StringProperty("")
  val document = StringProperty("")
  val dirty = BooleanProperty(false)
  val results = ObjectProperty(Vector.empty[Record])
  val searchPending = BooleanProperty(false)
  val error = ObjectProperty(Option.empty[String])
  val importing = BooleanProperty(false)
  val imported = ObjectProperty(0)
  val rows = ObservableBuffer(new Row(Record(1, "First")), new Row(Record(2, "Second")))
  private val documentSubscription = document.onChange { (_, _, _) =>
    revision += 1
    dirty.value = revision > savedRevision
  }
  private def submit[A](task: Task[A], pool: ThreadPoolExecutor, current: () => Boolean = () => true)(success: A => Unit): Boolean =
    tasks += task
    task.setOnSucceeded(_ => { tasks -= task; if alive && current() then success(task.getValue) })
    task.setOnFailed(_ => { tasks -= task; if alive && current() then error.value = Some(task.getException.getMessage) })
    task.setOnCancelled(_ => { tasks -= task; () })
    try { pool.execute(task); true }
    catch case _: java.util.concurrent.RejectedExecutionException =>
      tasks -= task
      error.value = Some("Worker queue full")
      false
  private def search(): Unit =
    owner()
    generation += 1
    val epoch = generation
    val input = query.value
    searchTask.foreach { task => task.cancel(true); workers.remove(task); () }
    val task = new Task[Vector[Record]]:
      def call(): Vector[Record] = services.search(input, () => isCancelled)
    searchTask = Some(task)
    searchPending.value = true
    if !submit(task, workers, () => epoch == generation) { value =>
      if epoch == generation then { results.value = value; searchPending.value = false }
    } then searchPending.value = false
    // Task event handlers also settle the pending indicator on failure/cancel.
    task.stateProperty.addListener((_, _, state) =>
      if epoch == generation && (state == javafx.concurrent.Worker.State.FAILED || state == javafx.concurrent.Worker.State.CANCELLED) then
        searchPending.value = false)
  private val querySubscription = query.onChange((_, _, _) => search())
  def refresh(values: Vector[Record]): Unit =
    owner()
    require(values.map(_.id).distinct.size == values.size, "Duplicate row ID")
    val retained = rows.map(row => row.record.id -> row).toMap
    rows.setAll(values.map { record =>
      retained.get(record.id).fold(new Row(record)) { row => row.refresh(record); row }
    }*)
  def save(): Boolean =
    owner()
    if !dirty.value then false
    else
      val snapshot = document.value
      val capturedRevision = revision
      val task = new Task[Unit]:
        def call(): Unit = services.save(snapshot)
      submit(task, saves) { _ =>
        savedRevision = savedRevision.max(capturedRevision)
        dirty.value = revision > savedRevision
      }
  private val pulse = new AnimationTimer:
    def handle(now: Long): Unit =
      var remaining = 8
      while remaining > 0 && !incoming.isEmpty do
        val entry = incoming.poll()
        if entry != null && entry._1 == importGeneration then
          val record = entry._2
          rows.find(_.record.id == record.id) match
            case Some(row) => row.refresh(record)
            case None => rows += new Row(record)
          imported.value += 1
        remaining -= 1
      if importTask.exists(_.isDone) && incoming.isEmpty then
        importing.value = false
        stop()
  def startImport(): Boolean =
    owner()
    if importing.value then false
    else
      incoming.clear()
      importGeneration += 1
      val epoch = importGeneration
      imported.value = 0
      val task = new Task[Int]:
        def call(): Int = services.importRows(record => {
          if isCancelled then throw new CancellationException
          incoming.put(epoch -> record)
        }, () => isCancelled)
      importTask = Some(task)
      importing.value = submit(task, workers)(_ => ())
      if importing.value then pulse.start()
      importing.value
  def cancelImport(): Unit =
    owner()
    importGeneration += 1
    importTask.foreach(_.cancel(true))
    incoming.clear()
    importing.value = false
    pulse.stop()
  def close(): Unit =
    owner()
    alive = false
    querySubscription.cancel()
    documentSubscription.cancel()
    tasks.toVector.foreach(_.cancel(true))
    tasks.clear()
    pulse.stop()
    incoming.clear()
    workers.shutdownNow()
    saves.shutdownNow()
  search()
