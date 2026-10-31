package sirin.desktop

import java.nio.file.{Files,Path}
import java.util.concurrent.atomic.AtomicBoolean
import sirin.*
import sirin.engine.*
import sirin.host.*
import scala.jdk.CollectionConverters.*

/** Main-thread input/application owner and a separate bounded native render owner. */
object DesktopWorkbench:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => throw new IllegalStateException(s"$other")
  def main(args: Array[String]): Unit =
    if args.headOption.contains("editor") then {EditorWorkbench.main(args.drop(1));return}
    if args.headOption.contains("canvas") then {CanvasWorkbench.main(args.drop(1));return}
    val mainEnteredAt=System.nanoTime()
    val root=Path.of(System.getProperty("sirin.repo", ".")).toAbsolutePath.normalize()
    val windowStarted=System.nanoTime()
    val window=new NativeWindow("Sirin Workbench",600,360)
    val windowSetupNanos=System.nanoTime()-windowStarted
    try {window.activate();run(window,root,args,mainEnteredAt,windowSetupNanos)}
    finally window.close()
    require(NativeWindow.liveWindows()==0 && NativeWindow.liveRequests()==0,"native window/request leaked")
    require(sirin.render.OffscreenGpu.liveOwners==0 && sirin.render.BatchGpu.liveOwners==0 && sirin.render.BatchGpu.liveHealthRecords==0 && sirin.render.GpuFrame.liveFrames==0 && sirin.render.GpuFrame.liveTiles==0,"render device/frame leaked")
  private def run(window: NativeWindow,root: Path,args: Array[String],mainEnteredAt: Long,windowSetupNanos: Long): Unit =
    val interactive=args.contains("interactive")
    val scenario=args.contains("scenario")
    val benchmark=args.contains("benchmark")
    val resizeControl=args.contains("benchmark-resize-control")
    require(!resizeControl || benchmark,"resize refusal control requires benchmark")
    val fixture=new BenchmarkFixture
    val runtime=new HeadlessRuntime(workCapacity=8)
    var pool=Option.empty[WorkerPool]
    var current=Option.empty[RenderedFrame]
    var renderer=Option.empty[RenderService]
    var subscription=Option.empty[Subscription]
    val coordinator=new FrameCoordinator
    val interaction=new Interaction
    val pendingWake=new AtomicBoolean(false)
    def wake(): Unit = if pendingWake.compareAndSet(false,true) then require(window.wake(),"native wake rejected")
    final case class PendingInput(kind: String,at: Long,event: String,target: String,label: String,handleNanos: Long)
    var sample=Option.empty[PendingInput]
    val phaseRows=scala.collection.mutable.ArrayBuffer.empty[Vector[String]]
    val gcBeans=java.lang.management.ManagementFactory.getGarbageCollectorMXBeans.asScala.toVector
    var firstPresentationNanos=0L
    var sessionSetupNanos=0L
    var benchmarkStep=0
    val traces=scala.collection.mutable.ArrayBuffer.empty[(String,Double)]
    val warmups=scala.collection.mutable.ArrayBuffer.empty[(String,Double)]
    val warmupSteps=40 // fixed 20 action + 20 scroll turns before the measured 100/class
    var recoveryPending=false;var consecutiveLosses=0;var recoveries=0
    var latestRequest=Option.empty[FrameRequest]
    val sharedRenderer=new java.util.concurrent.atomic.AtomicReference[java.lang.foreign.MemorySegment]()
    var samplesComplete=false
    var targetsMet=false
    var benchmarkFailure=Option.empty[Throwable]
    val runDirectory=root.resolve("desktop/target/benchmarks").resolve(s"run-${System.currentTimeMillis()}-${System.nanoTime()}")
    def writeBenchmarkReceipt(): Unit = if benchmark then
      Files.createDirectories(runDirectory)
      val csv="phase,kind,milliseconds\n"+
        (warmups.map((k,v) => s"warmup,$k,$v")++traces.map((k,v) => s"measured,$k,$v")).mkString("\n")
      def field(s: String): String=
        val quote=34.toChar.toString
        if s.exists(c => c==',' || c==34.toChar || c=='\n' || c=='\r') then quote+s.replace(quote,quote+quote)+quote else s
      val header=Vector("step","phase","kind","event","target","label","input_to_present_ms","input_handler_ms","last_render_queue_ms","last_worker_ms","present_call_ms","after_present_ms","gc_collections","gc_ms","worker_profile","logical_width","logical_height","pixel_width","pixel_height")
      Files.writeString(runDirectory.resolve("phases.csv"),(header+:phaseRows.toVector).map(_.map(field).mkString(",")).mkString("\n")+"\n")
      Files.writeString(runDirectory.resolve("input-to-present.csv"),csv)
      Files.writeString(root.resolve("desktop/target/input-to-present.csv"),csv)
      val status=if benchmarkFailure.nonEmpty then "failed" else if samplesComplete then "complete" else "incomplete"
      val failure=benchmarkFailure.map(e => s"${e.getClass.getName}: ${Option(e.getMessage).getOrElse("").replace('\n',' ')}").getOrElse("")
      Files.writeString(runDirectory.resolve("receipt.txt"),s"status=$status\nsamplesComplete=$samplesComplete\ntargetsMet=$targetsMet\nexpectedGeometry=${fixture.expected}\nobservedGeometry=${fixture.observed.map(_.toString).getOrElse("unobserved")}\ngeometryStable=${fixture.stable}\ngeometryChecks=${fixture.checks}\nsteps=$benchmarkStep\nwarmups=${warmups.size}\nmeasured=${traces.size}\nmainToFirstPresentationNs=$firstPresentationNanos\nwindowSetupNs=$windowSetupNanos\nrenderSessionSetupNs=$sessionSetupNanos\nfailure=$failure\n")
      println(s"benchmarkReceipt=$runDirectory status=$status targetsMet=$targetsMet")
    try
      pool=Some(runtime.scheduler.startWorkers(2))
      subscription=Some(runtime.onActivity(() => wake()))
      sharedRenderer.set(window.prepareRenderer(sirin.render.BatchGpu.shaderSource))
      renderer=Some(new RenderService(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/frames"),() => wake(),Some(() => {
        val gpu=sirin.render.BatchGpu.shared(sharedRenderer.get())
        try new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/frames"),batch=Some(gpu))
        catch case e: Throwable => {gpu.close();throw e}
      })))
      val scope=runtime.scope("workbench")
      val app=new VisibleWorkbench(scope)
      ok(runtime.transact {
        app.model.document.set("A retained document draft")
        app.model.rows.setAll(if args.contains("empty") then Vector.empty else (1 to 100).map(id => sirin.specimen.Record(id,s"Record $id")))
      })
      val mounted=runtime.mount(scope,app.view)
      runtime.observe(mounted.snapshot)(_ => ())
      def capture(): CapturedTree = runtime.withCommitted { reader => reader(mounted.snapshot) match
        case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot,reader)
        case Value.Failed(error) => throw error
      }
      def publishFocus()(using UiTxn): Unit =
        val (direct,ancestors,keyboard)=interaction.focusReferences
        runtime.publishFocus(direct,ancestors,keyboard)
      val viewports=scala.collection.mutable.Map.empty[Long,Viewport]
      val elements=new ElementInput(runtime,interaction)
      var bindings=capture();elements.update(bindings.elements)
      var facts=window.waitEvent(0)
      var presentedPayload=Option.empty[FramePayload]
      var repaint=true;var running=true;var presented=0;var feedbacks=0;var feedbackChain=0
      var scenarioStage=0;var lastFrameAt=0L;var lastGeometryAt=0L
      val deadline=System.nanoTime()+(if benchmark then 120000000000L else 30000000000L)
      def nativeInput(input: HostInput,timestamp: Long): Unit =
        val handlingStarted=System.nanoTime()
        if input==HostInput.KeyDown(Key.Escape,Mods.none,false) then running=false
        else
          val before=interaction.state
          // Scrolling belongs to the public scroll element, so wheel and keys reach elements first.
          val routed=input match
            case HostInput.Wheel(x,y,delta) => elements.wheel(x,y,delta,Mods.none).exists(ok)
            case HostInput.KeyDown(key,mods,repeat) if key!=Key.Tab => elements.key(KeyEvent(key,mods,repeat)).exists(ok)
            case _ => false
          val decision=if routed then InputDecision(changed=true) else interaction.handle(input)
          bindings=capture();elements.update(bindings.elements);elements.update(bindings.elements)
          val action=decision.activate.flatMap(bindings.bindings.get).filter(_.enabled)
          val result=runtime.transact {
            publishFocus()
            action.foreach(_.run(summon[UiTxn]))
          }
          result match
            case CommitResult.Committed(_) =>
              if decision.changed || action.nonEmpty then
                val target=input match
                  case HostInput.Wheel(x,y,_) => interaction.presented.targets.reverseIterator.find(t => t.element.exists(_.name=="scroll") && t.hit(x,y)).map(_.id)
                  case _ => decision.activate.flatMap(id => interaction.presented.targets.find(_.kind==TargetKind.Button(id)).map(_.id)).orElse(interaction.state.focused)
                def label(p: Placed): Option[String] =
                  val own=Option.when(target.contains(p.node.id))(p.node.content).flatMap {
                    case Content.Button(_,text,_,_,_) => Some(text)
                    case Content.Custom(frame) if frame.name=="scroll" => Some("scroll")
                    case _ => None
                  }
                  own.orElse(p.children.iterator.map(label).collectFirst {case Some(value) => value})
                val kind=if input.isInstanceOf[HostInput.Wheel] || input==HostInput.KeyDown(Key.PageDown,Mods.none,false) then "scroll" else "action"
                sample=Some(PendingInput(kind,timestamp,input.toString,target.map(_.path.mkString("/")).getOrElse(""),current.flatMap(f => label(f.layout.root)).getOrElse(""),System.nanoTime()-handlingStarted))
            case _ => interaction.rollbackPublication(before)
      def inject(kind: Int,key: Int=0,mods: Int=0,x: Double=0,y: Double=0,delta: Double=0): Unit = window.inject(kind,key,mods,x,y,delta)
      def nextBenchmark(): Unit =
        // Sequential actual SDL facts; one sample awaits its matching presentation.
        val step=if benchmarkStep<warmupSteps then benchmarkStep%20 else benchmarkStep-warmupSteps
        if benchmarkStep<20 || (benchmarkStep>=warmupSteps && step<100) then
          val key=if step%2==0 then 2 else 1
          if key==2 && !interaction.focused.exists(_.kind.isInstanceOf[TargetKind.Button]) then
            interaction.presented.targets.find(t => t.kind.isInstanceOf[TargetKind.Button] && t.enabled).foreach(t => interaction.state=interaction.state.copy(focused=Some(t.id),keyboard=true))
          inject(1,key)
        else if benchmarkStep<warmupSteps+200 then
          interaction.presented.targets.find(_.element.exists(_.name=="scroll")).foreach { t =>
            inject(6,x=t.clip.x+5,y=t.clip.y+5,delta=if benchmarkStep%2==0 then -1 else 1)
          }
        benchmarkStep+=1
      def recoverRenderer(): Unit =
        consecutiveLosses+=1;require(consecutiveLosses<=3,"repeated graphics loss without presentation")
        recoveryPending=true;current.foreach(_.close());current=None;presentedPayload=None
        renderer.get.quiesce()
        sharedRenderer.set(window.prepareRenderer(sirin.render.BatchGpu.shaderSource))
        recoveries+=1
        latestRequest.foreach(renderer.get.submit)
        repaint=true
        println(s"rendererRecovered=$recoveries")
      while running do
        if window.rendererLost() then recoverRenderer()
        if benchmark then fixture.observe(BenchmarkGeometry(facts.logicalWidth(),facts.logicalHeight(),facts.width(),facts.height()))
        pendingWake.set(false)
        val turn=runtime.drive(16)
        require(turn.failures.isEmpty && runtime.wakeFailures==0,s"driver failure: ${turn.failures}")
        if facts.quit() then running=false
        NativeInput.decode(facts).filter(_ => running && !recoveryPending).foreach { input =>
          val positional=facts.kind()==3 || facts.kind()==4 || facts.kind()==6
          if !positional || facts.timestamp()>=lastGeometryAt then nativeInput(input,facts.timestamp())
        }
        bindings=capture();elements.update(bindings.elements)
        if presentedPayload.exists(p => p.tree==bindings.tree && p.logicalWidth==facts.logicalWidth() && p.logicalHeight==facts.logicalHeight()) then
          runtime.takeFocusRequests().foreach { ref =>
            if interaction.focusRef(ref) then ok(runtime.transact {publishFocus()})
            else println(s"focusIntentDropped reference=$ref reason=not-focusable-in-presented-tree")
          }
        var recapture=false
        if running && facts.width()>0 && facts.height()>0 && facts.logicalWidth()>0 && facts.logicalHeight()>0 then
          val payload=FramePayload(bindings.tree,facts.logicalWidth(),facts.logicalHeight(),facts.width(),facts.height(),
            interaction.state.focused,interaction.state.keyboard,interaction.state.pressed.map(_.target),viewports.toMap)
          coordinator.request(payload).foreach { request => latestRequest=Some(request);renderer.get.submit(request) }
          renderer.get.poll().foreach {
            case (generation,Left(_: sirin.graphics.GraphicsDeviceLost)) if coordinator.acceptsGeneration(generation) => recoverRenderer()
            case (generation,Left(error)) if coordinator.acceptsGeneration(generation) => throw error
            case (_,Right(frame)) if coordinator.accepts(frame.request) =>
              val active=frame.layout.viewports.map(_.instance).toSet
              viewports.filterInPlace((instance,_) => active(instance))
              val changed=frame.layout.viewports.filter(v => !viewports.get(v.instance).contains(v.viewport))
              if changed.nonEmpty then
                ok(runtime.transact {changed.foreach(v => mounted.reportViewport(v.instance,v.viewport))})
                changed.foreach(v => viewports(v.instance)=v.viewport)
                feedbacks+=1;feedbackChain+=1;require(feedbackChain<20,"viewport feedback failed to settle")
                recapture=true
                // Empty feedback can leave the request unchanged; retain this result for presentation.
                current.foreach(_.close());frame.close();current=None
              else frame match
                case rendered: RenderedFrame => current.foreach(_.close());current=Some(rendered);repaint=true
                case _: LayoutFeedback => throw new IllegalStateException("settled feedback omitted pixels")
            case (_,Right(frame)) => frame.close()
            case _ => ()
          }
          current.filter(f => coordinator.accepts(f.request)).foreach { frame =>
            val settled=frame.layout.viewports.forall(v => viewports.get(v.instance).contains(v.viewport))
            var presentCallNanos=0L;var presentReturnedAt=0L
            def present(): Boolean =
              if benchmark then fixture.observe(BenchmarkGeometry(frame.request.payload.logicalWidth.toInt,frame.request.payload.logicalHeight.toInt,frame.width,frame.height))
              val began=System.nanoTime()
              val accepted=frame.present(window)
              presentReturnedAt=System.nanoTime();presentCallNanos=presentReturnedAt-began
              accepted
            if !recapture && settled && repaint && present() then
              recoveryPending=false;consecutiveLosses=0
              if firstPresentationNanos==0 then
                firstPresentationNanos=presentReturnedAt-mainEnteredAt
                sessionSetupNanos=frame.sessionSetupNanos
                println(s"startup mainToFirstPresentMs=${firstPresentationNanos/1e6} windowSetupMs=${windowSetupNanos/1e6} renderSessionSetupMs=${sessionSetupNanos/1e6}")
              presentedPayload=Some(frame.request.payload)
              val previousTargets=interaction.presented.targets
              interaction.install(InteractionSnapshot(frame.request.generation,frame.layout));elements.install(frame.layout)
              if interaction.presented.targets!=previousTargets then lastGeometryAt=NativeWindow.ticks()
              ok(runtime.transact {publishFocus()})
              recapture=true // one bounded follow-up settles focus intents and reactive focus observers
              feedbackChain=0;repaint=false;presented+=1;lastFrameAt=System.nanoTime()
              val sampled=sample.nonEmpty
              sample.foreach { input =>
                val kind=input.kind
                val elapsed=(NativeWindow.ticks()-input.at)/1000000.0
                val endpointAt=System.nanoTime()
                val warming=benchmark && benchmarkStep<=warmupSteps
                if warming then warmups+=((kind,elapsed)) else traces+=((kind,elapsed))
                if benchmark then
                  val collections=gcBeans.map(_.getCollectionCount)
                  val gcMillis=gcBeans.map(_.getCollectionTime)
                  phaseRows+=Vector(benchmarkStep.toString,if warming then "warmup" else "measured",kind,input.event,input.target,input.label,elapsed.toString,
                    (input.handleNanos/1e6).toString,(frame.queueNanos/1e6).toString,(frame.workerNanos/1e6).toString,(presentCallNanos/1e6).toString,
                    ((endpointAt-presentReturnedAt)/1e6).toString,(if collections.exists(_<0) then -1L else collections.sum).toString,
                    (if gcMillis.exists(_<0) then -1L else gcMillis.sum).toString,frame.profile,
                    frame.request.payload.logicalWidth.toInt.toString,frame.request.payload.logicalHeight.toInt.toString,frame.width.toString,frame.height.toString)
                println(s"inputToPresent phase=${if warming then "warmup" else "measured"} kind=$kind ms=$elapsed")
              };sample=None
              println(s"presented=$presented generation=${frame.request.generation} renderMs=${frame.workerNanos/1000000.0} uploadBytes=${frame.programBytes} readbackBytes=${frame.readbackBytes} pixels=${frame.width}x${frame.height} feedback=$feedbacks focus=${interaction.state.focused}")
              if scenario then
                scenarioStage match
                  case 0 => inject(1,1);scenarioStage=1
                  case 1 => inject(1,2);scenarioStage=2
                  case 2 =>
                    require(ok(runtime.transact {app.count.get})==1,"Enter did not activate")
                    ok(runtime.transact {app.moveRef.focus()});scenarioStage=3;recapture=true
                  case 3 => inject(1,2);scenarioStage=4
                  case 4 =>
                    require(ok(runtime.transact {app.right.get}),"retained move did not activate")
                    require(ok(runtime.transact {app.moveRef.hasFocus.get}),"retained focus lost")
                    ok(runtime.transact {app.listRef.focus()});scenarioStage=5;recapture=true
                  case 5 => inject(1,8);scenarioStage=6
                  case 6 =>
                    require(frame.layout.viewports.exists(_.viewport.visible.origin.y.toDouble>0),"PageDown did not scroll")
                    window.resize(800,600);scenarioStage=7
                  case 7 =>
                    require(frame.width<=1280 && frame.height<=800,"resize budget failed")
                    ok(runtime.transact {app.extra.set(true);app.extraRef.focus()})
                    scenarioStage=8;recapture=true
                  case 8 =>
                    if ok(runtime.transact {app.extraRef.hasFocus.get}) then {scenarioStage=9;inject(1,2)}
                  case 9 =>
                    require(ok(runtime.transact {app.count.get})==2,"newly mounted control lost activation/focus")
                    scenarioStage=10;inject(8)
                  case _ => ()
              if args.contains("recovery") then
                if presented==1 then window.destroyRenderer()
                else if recoveries>0 then inject(8)
              if args.contains("close") && presented==1 then inject(8)
              if resizeControl && presented==1 then window.resize(640,400)
              if benchmark && !resizeControl && benchmarkStep<warmupSteps+200 && (benchmarkStep==0 || sampled) then nextBenchmark()
          }
        if running && window.rendererLost() then recoverRenderer()
        if !running then ()
        else if !interactive && !benchmark && presented>0 && (!scenario || scenarioStage==10) && System.nanoTime()-lastFrameAt>2000000000L then running=false
        else if benchmark && traces.size>=200 then running=false
        else if !interactive && System.nanoTime()>deadline then throw new IllegalStateException(s"host deadline exceeded stage=$scenarioStage benchmark=$benchmarkStep")
        else if recapture || turn.budgetExceeded then facts=window.waitEvent(0)
        else
          val runtimeWait=runtime.activityWaitMillis
          val smokeWait=if interactive then -1 else
            val end=if presented>0 && !benchmark && (!scenario || scenarioStage==10) then math.min(deadline,lastFrameAt+2000000000L) else deadline
            math.max(0L,(end-System.nanoTime()+999999)/1000000).min(Int.MaxValue).toInt
          val idleTimeout=if runtimeWait<0 then smokeWait else if smokeWait<0 then runtimeWait else math.min(runtimeWait,smokeWait)
          val retry=repaint && current.exists(f => coordinator.accepts(f.request))
          val timeout=if retry then (if idleTimeout<0 then 16 else math.min(idleTimeout,16)) else idleTimeout
          facts=window.waitEvent(timeout);repaint = repaint || facts.redraw()
      require(!args.contains("recovery") || (recoveries==1 && presented>=2),"device recovery incomplete")
      require(!scenario || scenarioStage==10,"interaction scenario incomplete")
      if benchmark then
        require(benchmarkStep==warmupSteps+200 &&
          warmups.groupMapReduce(_._1)(_ => 1)(_+_)==Map("action"->20,"scroll"->20) &&
          traces.groupMapReduce(_._1)(_ => 1)(_+_)==Map("action"->100,"scroll"->100),"benchmark interrupted or sample counts incomplete")
        samplesComplete=true
        targetsMet=traces.groupBy(_._1).toVector.map { (kind,values) =>
          val sorted=values.map(_._2).sorted
          def percentile(p: Double)=sorted(math.min(sorted.size-1,math.ceil(sorted.size*p).toInt-1))
          val (p95Limit,p99Limit)=if kind=="action" then (50.0,100.0) else (100.0,200.0)
          val passed=percentile(0.95)<=p95Limit && percentile(0.99)<=p99Limit
          println(s"latency kind=$kind n=${sorted.size} p50=${percentile(0.5)} p95=${percentile(0.95)} p99=${percentile(0.99)} max=${sorted.last} overP95Budget=${sorted.count(_>p95Limit)} accepted=$passed")
          passed
        }.forall(identity)
        require(targetsMet,"frozen warmed latency targets missed; receipt retained")
      println(s"hostPresentations=$presented feedbackPasses=$feedbacks nativeWakeFailures=${runtime.wakeFailures} driver=${runtime.driverSnapshot}")
    catch case error: Throwable =>
      benchmarkFailure=Some(error)
      throw error
    finally
      try writeBenchmarkReceipt()
      catch case error: Throwable =>
        benchmarkFailure match
          case Some(original) => original.addSuppressed(error)
          case None => throw error
      finally
        current.foreach(_.close());current=None
        coordinator.retire()
        subscription.foreach(_.cancel())
        try renderer.foreach(_.close())
        finally
          try runtime.close()
          finally pool.foreach(workers => require(workers.awaitStopped(10000),"application workers did not retire"))
