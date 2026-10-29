package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode
import sirin.render.BatchGpu
import sirin.graphics.GraphicsDeviceLost

/** Public-API editable form using the same retained renderer as the workbench. */
object EditorWorkbench:
  val Draft="A retained document draft.\nSelect, edit and undo.\ncafé   שלום   مرحبا"
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case other => throw new IllegalStateException(s"$other")
  def main(args: Array[String]): Unit =
    val root=Path.of(System.getProperty("sirin.repo",".")).toAbsolutePath.normalize()
    val runtime=new HeadlessRuntime(workCapacity=8)
    val scope=runtime.scope("editor")
    val title=scope.cell("Untitled")
    val document=scope.cell(Draft)
    val message=scope.cell("Ready")
    val intent=scope.cell(("",0))
    val canUndo=scope.cell(false);val canRedo=scope.cell(false)
    val titleRef=scope.ref();val documentRef=scope.ref()
    def request(value: String)(using UiTxn): Unit=intent.set((value,intent.get._2+1))
    val undo=scope.action[Unit]("editor.undo","Undo",enabled=canUndo)(_ => request("undo"))
    val redo=scope.action[Unit]("editor.redo","Redo",enabled=canRedo)(_ => request("redo"))
    val restore=scope.action[Unit]("editor.restore","Restore draft")(_ => {document.set(Draft);message.set("Draft restored")})
    val view=Column(
      Row(Text("Sirin").style(TextStyle.Title).color(ColorRole.Accent),Spacer().grow,Text("Editable text").style(TextStyle.Caption)),
      TextField.bind(title).label("Title").placeholder("Name this document").error(title.map(t => Option.when(t.isEmpty)("A title is required"))).onCommit(message.set("Title committed")).ref(titleRef),
      TextField.bind(document).label("Document").multiline.onCommit(message.set("Document committed")).onBlur(message.set("Document saved")).ref(documentRef).grow,
      Row(Button(undo),Button(redo),Button(restore),Spacer().grow,Text(document.map(t => s"${t.length} characters")).style(TextStyle.Caption)).spacing(8.lp),
      Text(message.zip(title).map((m,t) => s"$m • $t • ⌘A select all · ⌘C/X/V clipboard · ⌘Z undo · ⇧⌘Z redo")).style(TextStyle.Caption).color(ColorRole.Muted)
    ).spacing(6.lp).padding(16.lp)
    val mounted=runtime.mount(scope,view)
    runtime.observe(mounted.snapshot)(_ => ())
    val window=new NativeWindow("Sirin Editor",640,400)
    val unicode=try NativeUnicode.open(65536) catch case e: Throwable => {window.close();runtime.close();throw e}
    var renderer=Option.empty[RenderService]
    var workers=Option.empty[WorkerPool]
    var subscription=Option.empty[Subscription]
    var current=Option.empty[RenderedFrame]
    val coordinator=new FrameCoordinator
    val controller=new EditorController(runtime,unicode.analyze(_),() => window.clipboard(),window.clipboard(_),() => window.textInput(2,0,0,1,1))
    val pending=scala.collection.mutable.Queue.empty[(HostInput,Mods,Int)]
    var latest=Option.empty[FrameRequest]
    var activeRenderer=java.lang.foreign.MemorySegment.NULL
    var lastEditor=Option.empty[Long];var lastIntent=0
    var focusRequests=Vector.empty[Long]
    var scenario=0;var presentations=0;var recoveries=0;var consecutiveLosses=0
    var running=true;var repaint=true;var facts=window.waitEvent(0)
    val scripted=args.contains("scenario")
    val tracePath=Option(System.getProperty("sirin.editor.trace")).map(Path.of(_))
    var traceCount=0
    var lastAttempt=Option.empty[(Long,Boolean)]
    def trace(detail: => String): Unit = tracePath.foreach { path =>
      if traceCount<512 then
        java.nio.file.Files.writeString(path,detail+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
        traceCount+=1
    }
    val deadline=System.nanoTime()+120000000000L
    var clipboardBefore=Option.empty[String]
    def capture(): CapturedTree=runtime.withCommitted { reader => reader(mounted.snapshot) match
      case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot,reader)
      case Value.Failed(error) => throw error
    }
    def value[A](signal: Signal[A]): A=runtime.withCommitted { reader => reader(signal) match
      case Value.Ok(v) => v
      case Value.Failed(e) => throw e
    }
    def refresh(): CapturedTree =
      val turn=runtime.drive(16);require(turn.failures.isEmpty,"editor driver failure")
      val tree=capture();controller.update(tree)
      if flags() then
        val indicators=runtime.drive(16);require(indicators.failures.isEmpty,"editor indicator driver failure")
        val updated=capture();controller.update(updated);updated
      else tree
    def flags(): Boolean = lastEditor.filter(controller.snapshots.contains).exists { id =>
      val state=controller.state(id)
      if value(canUndo)==state.canUndo && value(canRedo)==state.canRedo then false
      else {ok(runtime.transact {canUndo.set(state.canUndo);canRedo.set(state.canRedo)});true}
    }
    def input(event: HostInput,mods: Mods=Mods.none,clicks: Int=1): Unit =
      controller.focusedId.foreach(id => lastEditor=Some(id))
      if event==HostInput.KeyDown(Key.Escape,Mods.none,false) && controller.focusedId.forall(id => controller.state(id).snapshot.preedit.isEmpty) then running=false
      else controller.handle(event,mods,clicks)
      controller.focusedId.foreach(id => lastEditor=Some(id))
      controller.notice.foreach(n => ok(runtime.transact {message.set(n)}));controller.notice=None
    def makeRenderer(): RenderService=new RenderService(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/editor-frames"),() => {window.wake();()},
      Some(() => new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/editor-frames"),batch=Some(BatchGpu.shared(activeRenderer)),editorSystemFonts=true)))
    def recover(): Unit =
      recoveries+=1;consecutiveLosses+=1;require(consecutiveLosses<=3,"editor repeated device loss")
      controller.invalidatePresentation()
      current.foreach(_.close());current=None
      renderer.foreach(_.quiesce());activeRenderer=window.prepareRenderer(BatchGpu.shaderSource)
      latest.foreach(renderer.get.submit);repaint=true
    try
      window.activate()
      activeRenderer=window.prepareRenderer(BatchGpu.shaderSource)
      clipboardBefore=if scripted then Some(window.clipboard()) else None
      workers=Some(runtime.scheduler.startWorkers(2));subscription=Some(runtime.onActivity(() => {window.wake();()}));renderer=Some(makeRenderer())
      var tree=refresh()
      while running do
        var recapture=false
        if facts.quit() then running=false
        if window.rendererLost() then recover()
        NativeInput.decode(facts).foreach { event =>
          // Hover does not schedule an editor frame. Essential events retain order.
          val hover=event match
            case HostInput.Move(_,_,false) => true
            case _ => false
          if !hover then
            require(pending.size<256,"editor essential-input queue overflow")
            val mods=(if (facts.mods()&2)!=0 then Mods.Shift else Mods.none)
            pending.enqueue((event,mods,math.max(1,facts.clicks())))
            trace(s"input kind=${facts.kind()} key=${facts.key()} x=${facts.x()} y=${facts.y()} ready=${controller.ready} focused=${controller.focusedId} pending=${pending.size}")
        }
        tree=refresh()
        var continue=true
        while continue && pending.nonEmpty do
          val (event,mods,clicks)=pending.front
          if controller.needsGeometry(event) && !controller.ready then continue=false
          else {pending.dequeue();input(event,mods,clicks);tree=refresh()}
        val request=value(intent)
        if request._2!=lastIntent then
          lastIntent=request._2
          lastEditor.foreach { id =>
            controller.focusEditor(id)
            input(HostInput.KeyDown(Key.Character('z'),if request._1=="redo" then Mods.Primary|Mods.Shift else Mods.Primary))
          }
          tree=refresh()
        var action=window.pollAccessibility()
        while action!=null do
          val accepted=controller.accessibility(action)
          trace(s"ax kind=${action.kind()} revision=${action.revision()} shown=${controller.presentedRevision} readyAfter=${controller.ready} accepted=$accepted")
          lastEditor=controller.focusedId.orElse(lastEditor);tree=refresh();action=window.pollAccessibility()
        focusRequests++=runtime.takeFocusRequests()
        if controller.ready then {focusRequests.foreach(controller.focusReference);focusRequests=Vector.empty}
        if facts.width()>0 && facts.height()>0 then
          val state=controller.interaction.state
          val payload=FramePayload(tree.tree,facts.logicalWidth(),facts.logicalHeight(),facts.width(),facts.height(),state.scrolls,state.focused,state.keyboard,state.pressed.map(_.target),editors=controller.snapshots)
          coordinator.request(payload).foreach { req => latest=Some(req);trace(s"request generation=${req.generation}");renderer.get.submit(req) }
          renderer.get.poll().foreach {
            case (_,Right(frame: RenderedFrame)) if coordinator.accepts(frame.request) => trace(s"received generation=${frame.request.generation}");current.foreach(_.close());current=Some(frame);repaint=true
            case (_,Right(other)) => other.close()
            case (generation,Left(_: GraphicsDeviceLost)) if coordinator.acceptsGeneration(generation) => recover()
            case (generation,Left(error)) if coordinator.acceptsGeneration(generation) => throw error
            case _ => ()
          }
          current.filter(f => coordinator.accepts(f.request)).foreach { frame =>
            if repaint && {val accepted=frame.present(window);if !lastAttempt.contains((frame.request.generation,accepted)) then {trace(s"attempt generation=${frame.request.generation} success=$accepted size=${frame.width}x${frame.height}");lastAttempt=Some((frame.request.generation,accepted))};accepted} then
              repaint=false;presentations+=1;recapture=true;consecutiveLosses=0
              controller.install(frame.request.generation,frame.layout)
              trace(s"present generation=${frame.request.generation} ready=${controller.ready} sameTree=${frame.layout.root.node==tree.tree} sameEditors=${frame.layout.editors==controller.snapshots}")
              EditorAccessibility.publish(window,controller,frame)
              if scripted then
                println(s"editorStage=$scenario generation=${frame.request.generation}")
                val fields=frame.layout.fields
                val docId=fields.find(_.node.content match
                  case Content.Editable(_,_,"Document",_,_,_) => true
                  case _ => false).get.node.content.asInstanceOf[Content.Editable].instance
                scenario match
                  case 0 => controller.focusEditor(docId);lastEditor=Some(docId);scenario=1
                  case 1 => input(HostInput.KeyDown(Key.Character('a'),Mods.Primary));input(HostInput.TextInput("office café שלום مرحبا"));scenario=2
                  case 2 if window.keyboardFocus() =>
                    require(value(document)=="office café שלום مرحبا","committed text missing")
                    input(HostInput.KeyDown(Key.Character('a'),Mods.Primary));input(HostInput.KeyDown(Key.Character('c'),Mods.Primary));require(window.clipboard()==value(document),"clipboard copy")
                    input(HostInput.KeyDown(Key.Character('x'),Mods.Primary));require(value(document).isEmpty,"cut failed")
                    input(HostInput.KeyDown(Key.Character('z'),Mods.Primary));require(value(document)=="office café שלום مرحبا","undo cut failed")
                    input(HostInput.KeyDown(Key.Character('z'),Mods.Primary|Mods.Shift));require(value(document).isEmpty,"redo cut failed")
                    input(HostInput.KeyDown(Key.Character('v'),Mods.Primary))
                    // This sequence restores identical pixels; do not await a nonexistent new frame.
                    window.imeControl("にほ",2,0,false);scenario=4
                  case 4 if controller.state(docId).snapshot.preedit.nonEmpty =>
                    require(value(document)=="office café שלום مرحبا","preedit escaped to Cell")
                    window.imeControl("日本語",3,0,true);scenario=5
                  case 5 if controller.state(docId).snapshot.preedit.isEmpty && value(document).endsWith("日本語") =>
                    input(HostInput.KeyDown(Key.Character('z'),Mods.Primary));require(value(document)=="office café שלום مرحبا","IME commit was not one undo step")
                    scenario=6;window.resize(600,360)
                  case 6 =>
                    window.destroyRenderer();scenario=7
                  case 7 =>
                    require(recoveries==1,"editor recovery missing")
                    ok(runtime.transact {document.set(Draft)});scenario=8
                  case 8 =>
                    require(value(document)==Draft && !value(canUndo) && !value(canRedo) && !controller.state(docId).canUndo,"restored history indicators stale")
                    scenario=9;window.inject(8,0,0,0,0,0)
                  case _ => ()
          }
        if scripted then require(System.nanoTime()<deadline,s"editor scenario deadline stage=$scenario")
        if running then
          facts=window.waitEvent(if recapture || (pending.nonEmpty && controller.ready) then 0 else if repaint then 16 else if scripted then 1000 else -1)
          if facts.redraw() then repaint=true
      require(!scripted || scenario==9,s"editor scenario incomplete:$scenario")
      println(s"editorScenario=$scenario presentations=$presentations recoveries=$recoveries rejected=${controller.rejected}")
    finally
      clipboardBefore.foreach(window.clipboard(_))
      coordinator.retire();current.foreach(_.close());subscription.foreach(_.cancel())
      try renderer.foreach(_.close()) finally
        try {runtime.close();unicode.close();window.close()}
        finally workers.foreach(w => require(w.awaitStopped(10000),"editor workers leaked"))
    require(NativeWindow.liveWindows()==0 && BatchGpu.liveOwners==0 && BatchGpu.liveHealthRecords==0 && sirin.render.GpuFrame.liveFrames==0 && sirin.render.GpuFrame.liveTiles==0,"editor native ownership leaked")
