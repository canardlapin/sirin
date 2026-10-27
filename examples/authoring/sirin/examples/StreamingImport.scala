// A-0b program 9. One panel owns channels, command and subscriptions. Progress
// may coalesce; records use bounded ordered delivery. Worker waits cooperate
// with cancellation/disposal. UI capacity failure is explicit. Closing the
// panel prevents publication and wakes waiting producers; no manual cleanup.
package sirin.examples.streaming

import sirin.*

final case class Record(id: Int, text: String)

object Importer:
  private def requireDelivery(result: Offered): Unit = result match
    case Offered.Accepted => ()
    case Offered.Cancelled | Offered.Closed => throw new InterruptedException("import stopped")
    case Offered.Full => throw new IllegalStateException("bounded delivery has no capacity")

  def read(progress: Progress[Int], records: Progress[Record]): Work[Int] = Work {
    var count = 0
    while count < 10000 && !summon[Cancellation].cancelled do
      requireDelivery(records.emit(Record(count, s"Record $count")))
      count += 1
      requireDelivery(progress.emit(count / 100))
    if summon[Cancellation].cancelled then throw new InterruptedException("cancelled")
    count
  }

val StreamingImport = Component[Unit]("StreamingImport") { (scope, _) =>
  val percent = scope.cell(0)
  val received = scope.cell(0)
  val message = scope.cell("")
  val closed = scope.cell(false)
  val active = scope.cell(Option.empty[Invocation[Int]])
  // The example consumes records without retaining an unbounded collection.
  val records = scope.progress[Record](Delivery.Ordered, Capacity(64)) { _ => received.update(_ + 1) }
  val progress = scope.progress[Int](Delivery.CoalesceLatest, Capacity(1))(percent.set(_))
  val run = scope.command[Unit, Unit, Int]("import.run", "Import",
    WorkPolicy(Concurrency.RefuseWhileRunning, Acceptance.EveryInvocation),
    enabled = status => status.running.map(_ == 0) && !closed) { _ =>
    Job((), Importer.read(progress.producer, records.producer))
  } { (_, result) =>
    active.set(None)
    result.outcome match
      case Outcome.Succeeded(count) => message.set(s"Worker read $count records; delivery drains independently")
      case Outcome.Cancelled => message.set("Cancelled")
      case Outcome.Failed(_) => message.set("Import failed. Retry or close.")
  }
  val start: Action = run.dispatch(()) match
    case Dispatched.Started(invocation) => active.set(Some(invocation))
    case Dispatched.Refused(reason) => message.set(reason)
  val cancel: Action = active.get.foreach(_.cancel())
  val close: Action =
    closed.set(true)
    active.get.foreach(_.cancel())
    records.cancelDelivery()
    progress.cancelDelivery()
    message.set("Channels closed; future publications are refused")
  val finish: Action =
    closed.set(true)
    records.close()
    progress.close()
  val inject: Action = records.offer(Record(-1, "UI record")) match
    case Offered.Accepted => message.set("Queued")
    case Offered.Full => message.set("Queue full; retry after it drains")
    case Offered.Closed => message.set("Import is closed")
    case Offered.Cancelled => message.set("Import cancelled")
  Column(
    Text(percent.map(p => s"$p%")), Text(received.map(n => s"$n records consumed")), Text(message),
    Button("Import").onPress(start), Button("Cancel").onPress(cancel),
    Text(records.state.map(_.toString)), Button("Drain delivery").onPress(finish),
    Button("Offer UI record").onPress(inject), Button("Close delivery").onPress(close)
  )
}
