package sirin.specimen

import sirin.*

final case class Record(id: Int, title: String)

trait Services:
  def search(query: String): Work[Vector[Record]]
  def save(text: String): Work[Unit]
  def importRows(output: Progress[Record]): Work[Int]

/** Application code uses public Sirin contracts. The driver supplies the host. */
final class Workbench(scope: Scope, services: Services):
  val query = scope.cell("")
  val search = scope.latest(query)(services.search)
  val rows = scope.keyed(Seq(Record(1, "First"), Record(2, "Second")))(_.id)
  val document = scope.cell("")
  private val saved = scope.cell(Revision.initial)
  val dirty: Signal[Boolean] = document.versioned.zip(saved).map((text, revision) => text.revision.isAfter(revision))
  val saveError = scope.cell(Option.empty[String])
  val save = scope.command[Unit, Versioned[String], Unit]("save", "Save", WorkPolicy.save(Capacity(2)), _ => dirty) { _ =>
    val snapshot = document.snapshot
    saveError.set(None)
    Job(snapshot, services.save(snapshot.value))
  } { (snapshot, result) => result.outcome match
    case Outcome.Succeeded(()) => saved.update(_.max(snapshot.revision))
    case Outcome.Failed(error) => saveError.set(Some(error.getMessage))
    case Outcome.Cancelled => ()
  }
  val rename = scope.action[(Int, String)]("rename", "Rename") { (id, title) =>
    UiTxn.require(title.trim.nonEmpty, "A title is required")
    UiTxn.require(rows.get(id).nonEmpty, "This row was removed")
    rows.update(id)(_.copy(title = title.trim))
  }
  def importing(owner: Scope): ImportSession = new ImportSession(owner, rows, services)

/** A single import operation owns its stream. Start a new session to retry. */
final class ImportSession(scope: Scope, rows: KeyedCell[Int, Record], services: Services):
  val received = scope.cell(0)
  val channel = scope.progress[Record](Delivery.CompleteStream, Capacity(2)) { row =>
    if rows.get(row.id).nonEmpty then rows.update(row.id)(_ => row) else rows.append(row)
    received.update(_ + 1)
  }
  val run = scope.command[Unit, Unit, Int]("import", "Import", WorkPolicy(Concurrency.RefuseWhileRunning, Acceptance.EveryInvocation),
    status => channel.state.zip(status.running).map((state, count) => state == ChannelState.Open && count == 0)) { _ =>
    Job((), services.importRows(channel.producer))
  } { (_, _) => channel.close() }
  def cancel(invocation: Invocation[Int])(using UiTxn): Unit =
    channel.cancelDelivery()
    invocation.cancel()

/** Per-component draft ownership: refreshed props never overwrite an edit. */
final class RowEditor(scope: Scope, val input: Inputs[Record], model: Workbench):
  val draft = scope.cell("")
  val editing = scope.cell(false)
  val error = scope.cell(Option.empty[String])
  private val base = scope.cell("")
  val conflict = input.zip(base).zip(editing).map { case ((row, title), active) => active && row.title != title }
  val begin: Action =
    draft.set(input.get.title)
    base.set(input.get.title)
    editing.set(true)
    error.set(None)
  val commit: Action = model.rename.dispatch(input.get.id -> draft.get) match
    case Dispatched.Started(_) => editing.set(false); error.set(None)
    case Dispatched.Refused(reason) => error.set(Some(reason))

object DemoServices extends Services:
  def search(query: String): Work[Vector[Record]] = Work {
    if summon[Cancellation].cancelled then throw new InterruptedException("search cancelled")
    Vector(Record(1, s"Result: $query"))
  }
  def save(text: String): Work[Unit] = Work { require(text.nonEmpty) }
  def importRows(output: Progress[Record]): Work[Int] = Work {
    var count = 0
    (3 to 8).foreach { id =>
      if output.emit(Record(id, s"Imported $id")) != Offered.Accepted then throw new InterruptedException("import stopped")
      count += 1
    }
    count
  }
