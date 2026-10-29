package sirin.desktop

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import sirin.*
import sirin.engine.*
import sirin.host.*

/** Main-thread owner plus bounded existing Work transport; no idle repaint loop. */
object DesktopWorkbench:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => throw new IllegalStateException(s"$other")
  private def read[A](runtime: HeadlessRuntime,signal: Signal[A]): A = runtime.read(signal) match
    case Value.Ok(value) => value
    case Value.Failed(error) => throw error
  def main(args: Array[String]): Unit =
    val smoke = !args.contains("interactive")
    val scenario=args.contains("scenario")
    val empty=args.contains("empty")
    val root=Path.of(System.getProperty("sirin.repo", ".")).toAbsolutePath.normalize()
    val fontRoot=root.resolve("graphics/src/test/resources/fonts")
    val window=new NativeWindow("Sirin JVM workbench",600,360)
    try run(window,root,fontRoot,smoke,scenario,empty)
    finally window.close()
    require(NativeWindow.liveWindows()==0,"window leaked")
    require(NativeWindow.liveRequests()==0,"native request leaked")
  private def run(window: NativeWindow,root: Path,fontRoot: Path,smoke: Boolean,scenario: Boolean,empty: Boolean): Unit =
    val runtime=new HeadlessRuntime(workCapacity=8)
    var pool=Option.empty[WorkerPool]
    try
      pool=Some(runtime.scheduler.startWorkers(2))
      val pendingWake=new AtomicBoolean(false)
      val wake=runtime.onActivity(() => { if pendingWake.compareAndSet(false,true) then require(window.wake(),"native wake rejected") })
      val coordinator=new FrameCoordinator
      var presented=0;var acceptedGeneration=0L;var lastFrameAt=0L;var feedbacks=0;var feedbackChain=0
      var facts=window.waitEvent(0)
      var repaint=true
      var scenarioStage=0
      try
        val scope=runtime.scope("workbench")
        val app=new VisibleWorkbench(scope)
        ok(runtime.transact {
          app.model.document.set("A retained document draft")
          app.model.rows.setAll(if empty then Vector.empty else (1 to 100).map(id => sirin.specimen.Record(id,s"Record $id")))
        })
        val mounted=runtime.mount(scope,app.view)
        runtime.observe(mounted.snapshot)(_ => ())
        val requests=scope.cell(Option.empty[FrameRequest])
        val output=scope.latest(requests)(request => Work { request.map(frame => FrameWorker.render(frame,fontRoot,root.resolve("desktop/target/frames"))) })
        val viewports=scala.collection.mutable.Map.empty[Long,RowViewport]
        var scrolls=Map.empty[Long,Double]
        var running=true
        val began=System.nanoTime()
        val deadline=began+30000000000L
        while running do
          var recapture=false
          pendingWake.set(false)
          val turn=runtime.drive(16)
          require(turn.failures.isEmpty,s"driver failed: ${turn.failures}")
          require(runtime.wakeFailures==0,"native wake callback failed")
          if facts.width()>0 && facts.height()>0 && facts.logicalWidth()>0 && facts.logicalHeight()>0 then
            val tree=runtime.withCommitted { reader =>
              reader(mounted.snapshot) match
                case Value.Ok(snapshot) => CommittedTree.capture(snapshot,reader)
                case Value.Failed(error) => throw error
            }
            val payload=FramePayload(tree,facts.logicalWidth(),facts.logicalHeight(),facts.width(),facts.height(),scrolls)
            coordinator.request(payload).foreach(request => ok(runtime.transact { requests.set(Some(request)) }))
            read(runtime,output) match
              case TaskState.Done(Completed(_,Outcome.Succeeded(Some(frame)),true)) if coordinator.accepts(frame.request) && (frame.request.generation!=acceptedGeneration || repaint) =>
                val active=frame.layout.viewports.map(_.instance).toSet
                viewports.filterInPlace((instance,_) => active(instance))
                val changed=frame.layout.viewports.filter(v => !viewports.get(v.instance).contains(v.viewport))
                if changed.nonEmpty then
                  ok(runtime.transact { changed.foreach(v => mounted.setViewport(v.instance,v.viewport)) })
                  changed.foreach(v => viewports(v.instance)=v.viewport)
                  recapture=true
                  feedbacks+=1;feedbackChain+=1
                  require(feedbackChain<20,"viewport feedback failed to settle")
                else if window.present(frame.pixels.pixelsCopy,frame.pixels.width,frame.pixels.height) then
                  acceptedGeneration=frame.request.generation;feedbackChain=0;repaint=false
                  presented+=1;lastFrameAt=System.nanoTime();scrolls=frame.layout.scrollOffsets.filter(_._2!=0)
                  println(s"presented=$presented generation=$acceptedGeneration pixels=${facts.width()}x${facts.height()} worker=${frame.worker} renderMs=${frame.workerNanos/1000000} tiles=${frame.tiles} programBytes=${frame.programBytes} readbackBytes=${frame.readbackBytes} surfaceUploadBytes=${frame.pixels.byteSize} feedbackPasses=$feedbacks capture=${frame.capture}")
                  if scenario then
                    scenarioStage match
                      case 0 =>
                        window.resize(800,600);scenarioStage=1
                      case 1 =>
                        require(facts.width()<=1280 && facts.height()<=800,"physical resize policy failed")
                        ok(runtime.transact { app.model.rows.setAll(Vector.empty);app.right.set(true) })
                        scenarioStage=2;recapture=true
                      case 2 =>
                        require(frame.layout.viewports.forall(_.viewport.height.toDouble==0),"empty viewport did not settle")
                        ok(runtime.transact { app.model.rows.setAll((1 to 100).map(id => sirin.specimen.Record(id,s"Record $id")));app.right.set(false) })
                        scrolls=frame.layout.scrollOffsets.keys.map(_ -> 1800.0).toMap
                        scenarioStage=3;recapture=true
                      case 3 =>
                        require(frame.layout.viewports.exists(_.viewport.offset.toDouble>=1800),"scrolled viewport did not settle")
                        scenarioStage=4
                      case _ => ()
              case TaskState.Done(Completed(_,Outcome.Failed(error),true)) => throw error
              case _ => ()
          if smoke && presented>0 && (!scenario || scenarioStage==4) && System.nanoTime()-lastFrameAt>2000000000L then running=false
          else if smoke && System.nanoTime()>deadline then throw new IllegalStateException("visible host deadline exceeded")
          else if !recapture && !turn.budgetExceeded then
            val now=System.nanoTime()
            val runtimeWait=runtime.activityWaitMillis
            val smokeWait=if smoke then math.max(0L,((if presented>0 && (!scenario || scenarioStage==4) then math.min(deadline,lastFrameAt+2000000000L) else deadline)-now)/1000000L+1).min(Int.MaxValue).toInt else -1
            val timeout=if runtimeWait<0 then smokeWait else if smokeWait<0 then runtimeWait else math.min(runtimeWait,smokeWait)
            facts=window.waitEvent(timeout)
            repaint=facts.redraw()
            if facts.quit() then running=false
        require(!scenario || scenarioStage==4,"scenario incomplete")
        require(!empty || (presented>0 && feedbacks>0),"empty first frame did not present")
        println(s"hostPresentations=$presented viewportFeedbackPasses=$feedbacks nativeWakeFailures=${runtime.wakeFailures} driver=${runtime.driverSnapshot}")
      finally
        coordinator.retire();wake.cancel()
    finally
      try runtime.close()
      finally pool.foreach(workers => require(workers.awaitStopped(10000),"frame workers failed to retire"))
