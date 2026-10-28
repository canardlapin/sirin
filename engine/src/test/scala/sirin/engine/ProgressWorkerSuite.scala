package sirin.engine

import sirin.*
import munit.FunSuite
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

class ProgressWorkerSuite extends FunSuite:
  private def value[A](r: HeadlessRuntime, signal: Signal[A]): A = r.read(signal) match
    case Value.Ok(a) => a
    case Value.Failed(error) => throw error
  private def worker[A](body: => A): A =
    val result = new AtomicReference[Either[Throwable, A]]()
    val done = new CountDownLatch(1)
    val thread = new Thread(() => {
      try result.set(Right(body))
      catch case error: Throwable => result.set(Left(error))
      finally done.countDown()
    })
    thread.setDaemon(true)
    thread.start()
    assert(done.await(5, TimeUnit.SECONDS), "producer thread did not finish")
    result.get() match
      case Right(a) => a
      case Left(error) => throw error
  private def offer[P](producer: Progress[P], value: P): Offered = worker(producer.tryEmit(value))
  private def started[A](r: CommitResult[Dispatched[A]]): Invocation[A] = r match
    case CommitResult.Committed(Dispatched.Started(handle)) => handle
    case other => fail(s"expected accepted invocation, got $other")
  private def await(r: HeadlessRuntime)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 5000000000L
    while !condition && System.nanoTime() < deadline do r.awaitActivity(100)
    assert(condition, "worker deadline exceeded")

  test("UI reservation rollback preserves independently accepted worker offers in all policies") {
    for delivery <- Delivery.values do
      val r = new HeadlessRuntime
      val scope = r.scope("owner")
      val received = scope.cell(Vector.empty[Int])
      val channel = scope.progress[Int](delivery, Capacity(3))(n => received.update(_ :+ n))
      assertEquals(offer(channel.producer, 0), Offered.Accepted)
      assertEquals(r.transact {
        assertEquals(channel.offer(1), Offered.Accepted)
        assertEquals(offer(channel.producer, 2), Offered.Accepted)
        UiTxn.refuse("rollback")
      }, CommitResult.Refused("rollback"))
      assertEquals(r.drainProgress().delivered, 2)
      assertEquals(value(r, received), Vector(0, 2))
      assertEquals(r.progressQueues.head, ProgressQueueSnapshot(0, 0, 2, 2, 0, 0))
      r.close()
  }

  test("coalesced UI rollback restores only its replaced tail, including nested reservations") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val received = scope.cell(Vector.empty[Int])
    val channel = scope.progress[Int](Delivery.CoalesceLatest, Capacity(2))(n => received.update(_ :+ n))
    worker { channel.producer.tryEmit(0); channel.producer.tryEmit(1) }
    r.transact {
      UiTxn.attempt {
        channel.offer(2)
        channel.offer(3)
        assertEquals(offer(channel.producer, 4), Offered.Full)
        UiTxn.refuse("restore")
      }
      ()
    }
    assertEquals(r.drainProgress().delivered, 2)
    assertEquals(value(r, received), Vector(0, 1))
    r.transact { channel.offer(5); channel.offer(6); channel.offer(7) }
    assertEquals(r.progressQueues.head, ProgressQueueSnapshot(2, 0, 5, 2, 1, 0))
    r.drainProgress()
    assertEquals(value(r, received), Vector(0, 1, 5, 7))
    r.close()
  }

  test("graceful close counts concurrent accepted offers and drains in order") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val received = scope.cell(Vector.empty[Int])
    val channel = scope.progress[Int](Delivery.CompleteStream, Capacity(3))(n => received.update(_ :+ n))
    offer(channel.producer, 1)
    r.transact {
      channel.close()
      assertEquals(channel.state.get, ChannelState.Closing(1))
      assertEquals(channel.offer(2), Offered.Closed)
      assertEquals(offer(channel.producer, 2), Offered.Accepted)
    }
    assertEquals(value(r, channel.state), ChannelState.Closing(2))
    assertEquals(offer(channel.producer, 3), Offered.Closed)
    r.drainProgress(1)
    assertEquals(value(r, channel.state), ChannelState.Closing(1))
    r.drainProgress()
    assertEquals(value(r, channel.state), ChannelState.Drained)
    r.transact { channel.cancelDelivery(); channel.close() }
    assertEquals(value(r, channel.state), ChannelState.Drained)
    assertEquals(value(r, received), Vector(1, 2))
    r.close()
  }

  test("cancel is transactional, explicitly accounts abandonment and rejects later producers") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(3))(_ => ())
    offer(channel.producer, 1)
    r.transact {
      UiTxn.attempt {
        channel.cancelDelivery()
        assertEquals(channel.state.get, ChannelState.Cancelled(1))
        assertEquals(channel.offer(2), Offered.Cancelled)
        assertEquals(offer(channel.producer, 2), Offered.Accepted)
        UiTxn.refuse("keep")
      }
      ()
    }
    assertEquals(value(r, channel.state), ChannelState.Open)
    r.transact { channel.cancelDelivery() }
    assertEquals(value(r, channel.state), ChannelState.Cancelled(2))
    assertEquals(offer(channel.producer, 3), Offered.Cancelled)
    assertEquals(r.progressQueues.head, ProgressQueueSnapshot(0, 0, 2, 0, 0, 2))
    r.close()
  }

  test("receiver refusal and caught defect retain one accepted head for explicit retry") {
    for defect <- Vector(false, true) do
      val r = new HeadlessRuntime
      val scope = r.scope("owner")
      val received = scope.cell(Vector.empty[Int])
      var reject = true
      var attempts = 0
      val channel = scope.progress[Int](Delivery.Ordered, Capacity(2)) { n =>
        attempts += 1
        received.update(_ :+ n)
        if reject then
          if defect then
            try received.update(_ => throw new IllegalStateException("receive"))
            catch case _: IllegalStateException => ()
          else UiTxn.refuse("receive")
      }
      offer(channel.producer, 1)
      val refused = r.drainProgress(10)
      assertEquals(refused.delivered, 0)
      assertEquals(refused.pending, 1)
      assertEquals(refused.failures.size, 1)
      assertEquals(attempts, 1)
      assertEquals(value(r, received), Vector.empty[Int])
      reject = false
      assertEquals(r.drainProgress().delivered, 1)
      assertEquals(value(r, received), Vector(1))
      r.close()
  }

  test("uncommitted reservation and leased coalescing tail are delivery/order barriers") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val received = scope.cell(Vector.empty[Int])
    var channel: ProgressChannel[Int] = null
    channel = scope.progress[Int](Delivery.CoalesceLatest, Capacity(1)) { n =>
      assertEquals(offer(channel.producer, n + 1), Offered.Full)
      received.update(_ :+ n)
    }
    r.transact { channel.offer(1); assertEquals(offer(channel.producer, 2), Offered.Full) }
    assertEquals(r.drainProgress().delivered, 1)
    assertEquals(value(r, received), Vector(1))
    r.close()
  }

  test("publication freeze does not hold a transport lock while evaluating user derivations") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(2))(_ => ())
    val source = scope.cell(1)
    val derived = source.map { n =>
      if n == 2 then assertEquals(offer(channel.producer, 2), Offered.Full)
      n
    }
    r.read(derived)
    assertEquals(r.transact { source.set(2) }, CommitResult.Committed(()))
    assertEquals(value(r, derived), 2)
    assertEquals(offer(channel.producer, 2), Offered.Accepted)
    r.close()
  }

  test("drain rotates across channels and one failing receiver cannot starve the next drain") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val received = scope.cell(Vector.empty[String])
    val a = scope.progress[Int](Delivery.Ordered, Capacity(2))(n => received.update(_ :+ s"a$n"))
    val b = scope.progress[Int](Delivery.Ordered, Capacity(2))(n => received.update(_ :+ s"b$n"))
    worker { a.producer.tryEmit(1); a.producer.tryEmit(2); b.producer.tryEmit(1); b.producer.tryEmit(2) }
    r.drainProgress(3)
    assertEquals(value(r, received), Vector("a1", "b1", "a2"))
    r.close()
  }

  test("UI owner blocking emit reports Full instead of waiting") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(1))(_ => ())
    channel.producer.tryEmit(0)
    val work = Work(channel.producer.emit(1))
    val command = scope.command[Unit, Unit, Offered]("emit", "Emit", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
    val handle = started(r.transact { command.dispatch(()) })
    val ticket = r.scheduler.tickets.head.id
    r.scheduler.run(ticket)
    r.scheduler.deliver(ticket)
    assertEquals(value(r, handle.observe(scope)).map(_.outcome), Some(Outcome.Succeeded(Offered.Full)))
    r.close()
  }

  test("producer helpers cannot escape a UI transaction or derivation even when errors are caught") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(2))(_ => ())
    def emit(): Offered = channel.producer.tryEmit(1)
    val result = r.transact { try emit() catch case _: KernelError => Offered.Full }
    assertEquals(result, CommitResult.Failed(KernelError.ReadOnlyPhase))
    assertEquals(r.progressQueues.head.pending, 0)
    r.close()
  }

  test("runtime cancellation wakes a blocked emitter, including cancellation before wait registration") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val pool = r.scheduler.startWorkers(1)
    val entered = new CountDownLatch(1)
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(1))(_ => ())
    channel.producer.tryEmit(0)
    val work = Work {
      entered.countDown()
      if channel.producer.emit(1) == Offered.Cancelled then throw new InterruptedException("cancelled")
      1
    }
    try
      val command = scope.command[Unit, Unit, Int]("emit", "Emit", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
      val handle = started(r.transact { command.dispatch(()) })
      assert(entered.await(5, TimeUnit.SECONDS))
      r.transact { handle.cancel() }
      await(r)(r.scheduler.tickets.exists(_.completed))
      r.scheduler.deliver(r.scheduler.tickets.head.id)
      assertEquals(value(r, handle.observe(scope)).map(_.outcome), Some(Outcome.Cancelled))
      assertEquals(r.progressQueues.head.pending, 1)
    finally
      r.close()
      assert(pool.awaitStopped(5000))
  }

  test("scope disposal wakes blocked producers and physical retirement can be awaited after host close") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val pool = r.scheduler.startWorkers(1)
    val entered = new CountDownLatch(1)
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(1))(_ => ())
    channel.producer.tryEmit(0)
    val work = Work { entered.countDown(); channel.producer.emit(1) }
    val command = scope.command[Unit, Unit, Offered]("emit", "Emit", WorkPolicy.download)(_ => Job((), work))((_, _) => ())
    started(r.transact { command.dispatch(()) })
    assert(entered.await(5, TimeUnit.SECONDS))
    r.close()
    assert(pool.awaitStopped(5000))
    assertEquals(pool.snapshot.liveThreads, 0)
    assertEquals(channel.producer.tryEmit(2), Offered.Cancelled)
  }

  test("custom cancellation flags use a bounded worker-only wait fallback") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(1))(_ => ())
    channel.producer.tryEmit(0)
    val flag = new AtomicBoolean(false)
    val entered = new CountDownLatch(1)
    val done = new CountDownLatch(1)
    val outcome = new AtomicReference[Offered]()
    val thread = new Thread(() => {
      given Cancellation = new Cancellation { def cancelled: Boolean = flag.get() }
      entered.countDown()
      outcome.set(channel.producer.emit(1))
      done.countDown()
    })
    thread.setDaemon(true)
    thread.start()
    try
      assert(entered.await(5, TimeUnit.SECONDS))
      flag.set(true)
      assert(done.await(2, TimeUnit.SECONDS))
      assertEquals(outcome.get(), Offered.Cancelled)
    finally r.close()
  }

  test("real workers reject captured UI mutation and deliver only through the owner pump") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val pool = r.scheduler.startWorkers(2)
    val field = scope.cell(0)
    var txn: UiTxn = null
    val work = Work {
      try field.set(9)(using txn)
      catch case _: KernelError => ()
      1
    }
    try
      val command = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => field.set(1))
      val handle = started(r.transact { txn = summon[UiTxn]; command.dispatch(()) })
      await(r)(r.scheduler.tickets.exists(_.completed))
      assertEquals(value(r, field), 0)
      r.pump()
      assertEquals(value(r, field), 1)
      assertEquals(value(r, handle.observe(scope)).map(_.outcome), Some(Outcome.Failed(KernelError.WrongThread)))
    finally
      r.close()
      assert(pool.awaitStopped(5000))
  }

  test("logical disposal cannot overfill physical worker admission or publish a late result") {
    val r = new HeadlessRuntime(workCapacity = 2)
    val workspace = r.scope("workspace")
    val pool = r.scheduler.startWorkers(1)
    val gate = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val work = Work { entered.countDown(); gate.await(); 1 }
    def launch(scope: Scope): Command[Unit, Int] = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => fail("late completion"))
    try
      val first = workspace.child("first")
      started(r.transact { launch(first).dispatch(()) })
      assert(entered.await(5, TimeUnit.SECONDS))
      r.transact { first.dispose() }
      (1 to 30).foreach { _ =>
        val owner = workspace.child("temporary")
        started(r.transact { launch(owner).dispatch(()) })
        r.transact { owner.dispose() }
        assert(pool.snapshot.queued <= 2)
      }
      assert(r.scheduler.tickets.isEmpty)
      assertEquals(pool.snapshot.executing, 1)
      pool.close()
      assert(!pool.awaitStopped(1))
      gate.countDown()
      assert(pool.awaitStopped(5000))
      assertEquals(pool.snapshot.completed, 0)
      assert(r.pump().completions.isEmpty)
    finally
      gate.countDown()
      r.close()
      assert(pool.awaitStopped(5000))
  }

  test("executor shutdown terminalizes serial waiters without freeing the executing slot early") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val pool = r.scheduler.startWorkers(1)
    val gate = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val work = Work { entered.countDown(); gate.await(); 1 }
    val command = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.save(Capacity(2)))(_ => Job((), work))((_, _) => ())
    try
      started(r.transact { command.dispatch(()) })
      started(r.transact { command.dispatch(()) })
      started(r.transact { command.dispatch(()) })
      assert(entered.await(5, TimeUnit.SECONDS))
      pool.close()
      r.pump()
      assertEquals(value(r, command.running), 1)
      assertEquals(r.transact { command.dispatch(()) }, CommitResult.Committed(Dispatched.Refused("worker executor closed")))
      gate.countDown()
      assert(pool.awaitStopped(5000))
      r.pump()
      assertEquals(value(r, command.running), 0)
    finally
      gate.countDown()
      r.close()
      assert(pool.awaitStopped(5000))
  }

  test("worker completion and accepted progress drain separately with fair bounded pumps") {
    val r = new HeadlessRuntime
    val scope = r.scope("owner")
    val pool = r.scheduler.startWorkers(1)
    val received = scope.cell(Vector.empty[Int])
    val completed = scope.cell(false)
    val channel = scope.progress[Int](Delivery.Ordered, Capacity(4))(n => received.update(_ :+ n))
    val work = Work { channel.producer.emit(1); channel.producer.emit(2); 2 }
    try
      val command = scope.command[Unit, Unit, Int]("run", "Run", WorkPolicy.download)(_ => Job((), work))((_, _) => { completed.set(true); channel.close() })
      started(r.transact { command.dispatch(()) })
      await(r)(r.scheduler.tickets.exists(_.completed))
      r.pump(1)
      r.pump(1)
      assert(value(r, completed))
      r.drainProgress()
      assertEquals(value(r, received), Vector(1, 2))
      assertEquals(value(r, channel.state), ChannelState.Drained)
    finally
      r.close()
      assert(pool.awaitStopped(5000))
  }

  test("coalescing between availability inspection and head leasing chooses the latest committed item atomically") {
    val transport = new ProgressTransport[Int](Delivery.CoalesceLatest, 1, Thread.currentThread(), new Activity)
    transport.commit(false, false)
    assertEquals(offer(transport, 1), Offered.Accepted)
    val observed = transport.head.get
    assertEquals(offer(transport, 2), Offered.Accepted)
    val leased = transport.leaseHead().get
    assertEquals(observed.value, 1)
    assertEquals(leased.value, 2)
    transport.commit(false, false)
    assertEquals(transport.snapshot, ProgressQueueSnapshot(0, 0, 2, 1, 1, 0))
    transport.dispose()
  }

  test("committed reservations detach ownership metadata and replacement history") {
    val transport = new ProgressTransport[Int](Delivery.CoalesceLatest, 1, Thread.currentThread(), new Activity)
    transport.commit(false, false)
    val key = new Object
    transport.reserve(1, key)
    val entry = transport.reserve(2, key)._2.get
    assert(entry.reserver.nonEmpty)
    assert(entry.replaced.nonEmpty)
    transport.commit(false, false)
    assert(entry.committed)
    assertEquals(entry.reserver, None)
    assertEquals(entry.replaced, None)
    transport.dispose()
  }

  test("refused receiver wakes an emitter waiting on its leased coalescing tail") {
    val r = new HeadlessRuntime
    val owner = r.scope("receiver")
    val waiting = new CountDownLatch(1)
    val done = new CountDownLatch(1)
    val outcome = new AtomicReference[Offered]()
    val token = new ObservableCancellation:
      def cancelled: Boolean = { waiting.countDown(); false }
      def onCancel(wake: () => Unit): () => Unit = () => ()
    def emitter(endpoint: Progress[Int]): Thread = new Thread(() => {
      given Cancellation = token
      try outcome.set(endpoint.emit(2))
      finally done.countDown()
    })
    var channel: ProgressChannel[Int] = null
    channel = owner.progress[Int](Delivery.CoalesceLatest, Capacity(1)) { _ =>
      val thread = emitter(channel.producer)
      thread.setDaemon(true)
      thread.start()
      // cancelled is checked under the queue monitor, immediately before the
      // full offer and wait; rollback must acquire that monitor afterwards.
      assert(waiting.await(5, TimeUnit.SECONDS))
      UiTxn.refuse("retry receiver")
    }
    try
      assertEquals(offer(channel.producer, 1), Offered.Accepted)
      val drain = r.drainProgress(1)
      assertEquals(drain.delivered, 0)
      assertEquals(drain.failures, Vector(CommitResult.Refused("retry receiver")))
      assert(done.await(2, TimeUnit.SECONDS), "unleased tail did not wake emitter")
      assertEquals(outcome.get(), Offered.Accepted)
      assertEquals(r.progressQueues.head, ProgressQueueSnapshot(1, 0, 2, 0, 1, 0))
    finally r.close()
  }
