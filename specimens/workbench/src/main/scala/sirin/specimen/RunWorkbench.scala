package sirin.specimen

import sirin.*
import sirin.engine.*

/** Headless, real-worker trace. Rendering and interactive JVM qualification follow later. */
object RunWorkbench:
  def read[A](host: HeadlessRuntime, signal: Signal[A]): A = host.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  def accepted[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => throw new IllegalStateException(s"transaction did not commit: $other")
  def requireStarted[A](result: Dispatched[A])(using UiTxn): Unit = result match
    case Dispatched.Started(_) => ()
    case Dispatched.Refused(reason) => UiTxn.refuse(reason)
  def settle(host: HeadlessRuntime)(done: => Boolean): Unit =
    val deadline = System.nanoTime() + 5000000000L
    while !done && System.nanoTime() < deadline do
      val receipt = host.pump(8)
      require(receipt.progress.failures.isEmpty && receipt.completions.forall {
        case CommitResult.Committed(_) => true
        case _ => false
      }, s"delivery failed: $receipt")
      if !done then host.awaitActivity(100)
    require(done, "workbench worker deadline exceeded")
  def trace(): Vector[String] =
    val host = new HeadlessRuntime(workCapacity = 8)
    val pool = host.scheduler.startWorkers(2)
    try
      val workspace = host.scope("workbench")
      val model = new Workbench(workspace, DemoServices)
      val session = model.importing(workspace.child("import"))
      accepted(host.transact { model.query.set("sirin"); model.document.set("revision one") })
      accepted(host.transact {
        requireStarted(model.save.dispatch(()))
        requireStarted(session.run.dispatch(()))
      })
      settle(host)(read(host, session.channel.state) == ChannelState.Drained && !read(host, model.search).pending && read(host, model.save.running) == 0)
      Vector(s"search=${read(host, model.search).shown.get.head.title}",
        s"imported=${read(host, session.received)} rows=${read(host, model.rows.snapshot).size}",
        s"dirty=${read(host, model.dirty)} channel=${read(host, session.channel.state)}")
    finally
      host.close()
      require(pool.awaitStopped(5000), "workers did not stop")
  def main(args: Array[String]): Unit = trace().foreach(println)
