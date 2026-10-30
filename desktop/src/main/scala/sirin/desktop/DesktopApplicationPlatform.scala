package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.unicode.NativeUnicode
import sirin.graphics.GraphicsDeviceLost
import sirin.render.BatchGpu

/** JVM development provider. The model and host capture remain backend-neutral. */
final class DesktopApplicationPlatform extends ApplicationPlatform:
  def run(build: Scope => Windows): Unit=runWithServices((scope,_) => build(scope))
  override def runWithServices(build: (Scope,ApplicationServices) => Windows): Unit =
    val native=new NativeApplication // checks the process main thread before build
    val runtime=new HeadlessRuntime(workCapacity=128)
    var unicode=Option.empty[NativeUnicode]
    var session=Option.empty[DesktopApplicationSession]
    try
      unicode=Some(NativeUnicode.open(65536))
      session=Some(new DesktopApplicationSession(native,runtime,unicode.get,build))
      session.get.run()
    finally
      try session.foreach(_.close())
      finally
        try runtime.close()
        finally
          try {session.foreach(_.awaitWorkers());unicode.foreach(_.close())}
          finally native.close()

object DesktopApplicationPlatform:
  /** Development acceptance hook, outside the public application model. */
  private[desktop] var observer: Option[DesktopApplicationSession => Unit]=None
  private[desktop] def services(runtime: HeadlessRuntime)(owner: runtime.OwnedScope,requestQuit: () => Unit): ApplicationServices =
    new ApplicationServices:
      val quit=owner.action[Unit]("application.quit","Quit") { _ => owner.afterCommit(requestQuit) }

private[desktop] final class DesktopApplicationSession(val native: NativeApplication,
    val runtime: HeadlessRuntime,unicode: NativeUnicode,build: (Scope,ApplicationServices) => Windows):
  private def ok[A](result: CommitResult[A]): A=result match
    case CommitResult.Committed(value) => value
    case CommitResult.Failed(error) => throw error
    case other => throw new IllegalStateException(s"$other")
  val (application,mounted)=ok(runtime.transact {
    val owner=runtime.scope("application")
    (owner,runtime.mountWindows(owner,build(owner,DesktopApplicationPlatform.services(runtime)(owner,() => native.requestQuit()))))
  })
  application.observe(mounted.snapshot)(_ => ())
  private val root=Path.of(System.getProperty("sirin.repo",".")).toAbsolutePath.normalize()
  private val active=scala.collection.mutable.LinkedHashMap.empty[Long,ApplicationPeer]
  private val retiring=scala.collection.mutable.ArrayBuffer.empty[ApplicationPeer]
  private var workers=Option.empty[WorkerPool]
  private var subscription=Option.empty[Subscription]
  private var quitting=false
  private val commands=new OwnerCommandBoundary
  private var focusRequests=Vector.empty[Long]
  private var event=new NativeApplication.Event(false,false,false,0)
  var applicationWakes=0L
  var turns=0L
  private val menus=new ApplicationMenus(native)
  private var menuWindow=Option.empty[Long]
  private var failures=Vector.empty[Throwable]
  def reportedFailures: Vector[Throwable]=failures
  private def report(error: Throwable): Unit =
    failures=(failures :+ error).takeRight(64)
    error.printStackTrace()
  def peers: Vector[ApplicationPeer]=active.values.toVector
  def capture(): Vector[CapturedWindow]=captureApplication().windows
  private def captureApplication(): CapturedApplication =
    if application.isDisposed then CapturedApplication(CapturedMenus.empty,Vector.empty)
    else runtime.withCommitted(reader => reader(mounted.snapshot) match
      case Value.Ok(snapshot) => WindowCapture.captureApplication(snapshot,reader)
      case Value.Failed(error) => throw error)
  private def sync(): Unit =
    val captured=captureApplication()
    val windows=captured.windows
    val ids=windows.map(_.instance).toSet
    active.keys.filterNot(ids).toVector.foreach { id =>
      val peer=active.remove(id).get;peer.retire();retiring+=peer
    }
    windows.foreach { window =>
      active.get(window.instance) match
        case Some(peer) => peer.update(window)
        case None if NativeWindow.liveWindows()<16 =>
          active(window.instance)=new ApplicationPeer(this,window,unicode,root)
        case None => () // Existing retiring peers still own the native capacity.
    }
    menuWindow=active.valuesIterator.find(_.window.keyboardFocus()).map(_.instance)
      .orElse(menuWindow.filter(active.contains)).orElse(active.lastOption.map(_._1))
    menus.update(captured.menu,menuWindow.flatMap(id => windows.find(_.instance==id)))
  private def invoke(action: Activation): Unit =
    runtime.transact {action.run(summon[UiTxn])} match
      case CommitResult.Failed(error) => report(error)
      case _ => ()
  def shortcut(instance: Long,input: HostInput,controller: EditorController): Boolean=input match
    case HostInput.KeyDown(key,mods,_) if !controller.consumesKey(input) =>
      val captured=captureApplication()
      captured.windows.find(_.instance==instance).exists { window =>
        val chord=Chord(key,mods)
        val action=runtime.withCommitted(_ => KeyboardRouting.command(window.content,controller.interaction.state.focused,chord,window.menu,captured.menu))
        action match
          case Some(binding) => invoke(binding);true
          case None => ApplicationMenus.standard(chord) match
            case Some(kind) => native.standardMenu(kind);true
            case _ => false
      }
    case _ => false
  private def menuActions(): Unit =
    var count=0
    var more=true
    while more && count<64 do
      val fact=native.pollMenu()
      if fact==null then more=false
      else
        // Reconcile focus, owner retirement and command state before interpreting the ID.
        sync();menus.action(fact).foreach(invoke);count+=1
  def closeWindow(instance: Long): CommitResult[Unit]=
    val result=runtime.transact {mounted.requestWindowClose(instance)}
    result match
      case CommitResult.Failed(error) => report(error)
      case _ => ()
    result
  private def requestQuit(): Unit =
    quitting=true
    val ids=capture().map(_.instance)
    runtime.transact {
      val decision=mounted.requestApplicationQuit()
      if decision==CloseDecision.Allow && !application.isDisposed then ids.foreach(mounted.requestWindowClose)
      decision
    } match
      case CommitResult.Committed(decision) => if decision==CloseDecision.KeepOpen || capture().nonEmpty then quitting=false
      case CommitResult.Refused(_) => quitting=false
      case CommitResult.Failed(error) =>
        quitting=false
        report(error)
      case _ => throw new IllegalStateException("nested native quit")
  def run(): Unit =
    workers=Some(runtime.scheduler.startWorkers(2))
    subscription=Some(runtime.onActivity(() => {native.wake();()}))
    var running=true
    while running && !application.isDisposed do
      turns+=1
      if event.wake() then applicationWakes+=1
      if event.quit() then commands.requestQuit()
      val turn=runtime.drive(32)
      require(turn.failures.isEmpty,s"application driver failures: ${turn.failures}")
      sync()
      active.values.toVector.foreach(_.drain())
      sync()
      commands.drain(active.values.exists(_.hasPendingInput))(() => requestQuit(),() => menuActions())
      sync()
      val attached=runtime.attachments.map(_.reference).toSet
      focusRequests=(focusRequests++runtime.takeFocusRequests()).distinct.filter(attached)
      require(focusRequests.size<=256,"application focus-intent capacity")
      val settled=active.values.forall(_.controller.ready)
      focusRequests=focusRequests.filter { reference =>
        val accepted=active.valuesIterator.exists(_.focus(reference))
        !accepted && !settled
      }
      var recapture=false
      active.values.toVector.foreach(peer => if peer.render() then recapture=true)
      retiring.filter(_.retired).toVector.foreach { peer => peer.finishClose();retiring-=peer }
      DesktopApplicationPlatform.observer.foreach(_(this))
      // Opening a fresh view while draining cancels the old quit intent.
      if quitting && capture().nonEmpty then quitting=false
      if quitting && capture().isEmpty && runtime.scheduler.tickets.isEmpty then running=false
      if running && !application.isDisposed then
        val wait=if recapture || turn.budgetExceeded then 0
          else if retiring.nonEmpty || active.values.exists(_.needsRetry) then 16 else runtime.activityWaitMillis
        val bounded=if DesktopApplicationPlatform.observer.nonEmpty && (wait<0 || wait>100) then 100 else wait
        event=native.waitEvent(bounded)
  def close(): Unit =
    active.values.toVector.foreach(_.retire());retiring++=active.values;active.clear()
    try retiring.toVector.foreach(_.finishClose(force=true))
    finally {retiring.clear();subscription.foreach(_.cancel())}
  def awaitWorkers(): Unit=workers.foreach(w => require(w.awaitStopped(10000),"application workers did not retire"))

private[desktop] final class ApplicationPeer(session: DesktopApplicationSession,
    initial: CapturedWindow,unicode: NativeUnicode,root: Path):
  private val runtime=session.runtime
  val instance=initial.instance
  private def title(value: Value[String]): String=value match
    case Value.Ok(text) => text
    case Value.Failed(_) => "Window — title unavailable"
  val window=new NativeWindow(title(initial.title),640,400)
  private var captured=initial
  private var activeRenderer=java.lang.foreign.MemorySegment.NULL
  private val coordinator=new FrameCoordinator
  private var latest=Option.empty[FrameRequest]
  private var current=Option.empty[RenderedFrame]
  private var repaint=true
  private var live=true
  private var feedback=0
  private var losses=0
  private var focusActive=window.keyboardFocus()
  private var shownFocus=Option.empty[NodeId]
  def hasPresentedEditorFocus: Boolean=controller.focusedId.exists(id => shownFocus.contains(NodeId(Vector("editor",id))))
  private var geometryAt=0L
  private var viewports=Map.empty[Long,RowViewport]
  private val pending=new OwnerInputQueue[NativeWindow.Event]
  val controller=new EditorController(runtime,unicode.analyze(_),() => window.clipboard(),window.clipboard(_),() => window.textInput(2,0,0,1,1))
  private var renderer=Option.empty[RenderService]
  var presentations=0L
  var staleInputs=0L
  try
    activeRenderer=window.prepareRenderer(BatchGpu.shaderSource)
    renderer=Some(new RenderService(root.resolve("graphics/src/test/resources/fonts"),root.resolve(s"desktop/target/application-frames/$instance"),
      () => {session.native.wake();()},Some(() => new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve(s"desktop/target/application-frames/$instance"),
        batch=Some(BatchGpu.shared(activeRenderer)),editorSystemFonts=true))))
    controller.update(captured.content)
    window.activate()
  catch case error: Throwable =>
    renderer.foreach(_.close());window.close();throw error
  def update(next: CapturedWindow): Unit =
    if title(captured.title)!=title(next.title) then window.title(title(next.title))
    captured=next;controller.update(next.content)
  private def refresh(): Boolean=session.capture().find(_.instance==instance) match
    case Some(next) => update(next);true
    case None => false
  private def updateInputClient(): Unit=current.foreach(frame => EditorAccessibility.updateTextInput(window,controller,frame,focusActive))
  def drain(): Unit =
    var count=0
    var available=true
    while live && available && !pending.closing && count<64 do
      val fact=window.pollEvent()
      if !fact.found() then available=false
      else
        if fact.quit() then pending.add(fact,None)
        else
          repaint = repaint || fact.redraw()
          val focused=if fact.focusGained() then true else if fact.focusLost() then false else window.keyboardFocus()
          if focused!=focusActive || fact.focusGained() || fact.focusLost() then
            focusActive=focused;controller.nativeFocus(focused);updateInputClient();repaint=true
          NativeInput.decode(fact).foreach { input =>
            val positional=input match
              case _: HostInput.Down | _: HostInput.Up | _: HostInput.Move | _: HostInput.Wheel => true
              case _ => false
            if positional && fact.timestamp()<geometryAt then staleInputs+=1
            else
              pending.add(fact,Some(input))
          }
        count+=1
        if !refresh() then return
    if !pending.drain(() => controller.ready,controller.needsGeometry)((fact,input) => {
      if !session.shortcut(instance,input,controller) then
        controller.handle(input,if (fact.mods()&2)!=0 then Mods.Shift else Mods.none,math.max(1,fact.clicks()))
      val mounted=refresh()
      if mounted then updateInputClient()
      mounted
    },_ => {
      session.closeWindow(instance)
      val mounted=refresh()
      if mounted then updateInputClient()
      mounted
    }) then return
    if pending.nonEmpty then return
    var actions=0
    var more=true
    while more && actions<128 do
      val action=window.pollAccessibility()
      if action==null then more=false
      else
        controller.accessibility(action)
        if !refresh() then return
        actions+=1
  def focus(reference: Long): Boolean =
    if controller.ready && controller.focusReference(reference) then
      window.activate();focusActive=true;controller.nativeFocus(true);updateInputClient();true
    else false
  private def recover(): Unit =
    losses+=1;require(losses<=3,"repeated device loss without a successful presentation")
    controller.invalidatePresentation();current.foreach(_.close());current=None
    renderer.get.quiesce();activeRenderer=window.prepareRenderer(BatchGpu.shaderSource)
    latest.foreach(renderer.get.submit);repaint=true
  def render(): Boolean =
    if !live || !refresh() then return false
    if window.rendererLost() then recover()
    val geometry=window.geometry()
    if geometry.width()<=0 || geometry.height()<=0 then return false
    val state=controller.interaction.state
    val payload=FramePayload(controller.tree,geometry.logicalWidth(),geometry.logicalHeight(),geometry.width(),geometry.height(),state.scrolls,
      state.focused.filter(_ => focusActive),focusActive && state.keyboard,state.pressed.map(_.target),viewports,controller.snapshots)
    coordinator.request(payload).foreach { request => latest=Some(request);renderer.get.submit(request) }
    var changed=false
    renderer.get.poll().foreach {
      case (_,Right(frame)) if coordinator.accepts(frame.request) =>
        val active=frame.layout.viewports.map(_.instance).toSet
        viewports=viewports.filter((id,_) => active(id))
        val updates=frame.layout.viewports.filter(v => !viewports.get(v.instance).contains(v.viewport))
        if updates.nonEmpty then
          runtime.transact {updates.foreach(v => session.mounted.setViewport(v.instance,v.viewport))} match
            case CommitResult.Committed(_) => ()
            case other => throw new IllegalStateException(s"viewport feedback refused: $other")
          updates.foreach(v => viewports=viewports.updated(v.instance,v.viewport))
          feedback+=1;require(feedback<20,"window viewport feedback did not settle")
          current.foreach(_.close());current=None;frame.close();changed=true
        else frame match
          case rendered: RenderedFrame => current.foreach(_.close());current=Some(rendered);repaint=true
          case _ => frame.close();throw new IllegalStateException("settled layout omitted its frame")
      case (_,Right(frame)) => frame.close()
      case (generation,Left(_: GraphicsDeviceLost)) if coordinator.acceptsGeneration(generation) => recover()
      case (generation,Left(error)) if coordinator.acceptsGeneration(generation) => throw error
      case _ => ()
    }
    current.filter(frame => coordinator.accepts(frame.request)).foreach { frame =>
      if !changed && repaint && frame.present(window) then
        val oldTargets=controller.interaction.presented.targets
        controller.install(frame.request.generation,frame.layout)
        shownFocus=frame.request.payload.focus
        if oldTargets!=controller.interaction.presented.targets then geometryAt=NativeWindow.ticks()
        EditorAccessibility.publish(window,controller,frame,focusActive)
        repaint=false;feedback=0;losses=0;presentations+=1;changed=true
    }
    changed || (pending.nonEmpty && controller.ready)
  def hasPendingInput: Boolean=pending.nonEmpty
  def needsRetry: Boolean=live && repaint && current.nonEmpty
  def retire(): Unit = if live then
    live=false;window.hide();pending.clear();coordinator.retire()
    current.foreach(_.close());current=None;renderer.foreach(_.requestClose())
  def retired: Boolean=renderer.forall(_.isRetired)
  def finishClose(force: Boolean=false): Unit =
    require(force || retired,"renderer still owns window resources")
    try renderer.foreach(_.close()) finally window.close()
