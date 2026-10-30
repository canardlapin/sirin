package sirin.desktop

import java.nio.file.{Files,Path}
import java.util.concurrent.{CountDownLatch,TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import sirin.*
import sirin.engine.*

/** Public-API application; private hook supplies transport test input and observations. */
object ApplicationHostProbe:
  final case class WindowSpec(id: Int,title: String)
  def main(args: Array[String]): Unit =
    val base=Path.of(System.getProperty("sirin.probe.output",System.getProperty("java.io.tmpdir")))
    Files.createDirectories(base)
    val out=Files.createTempDirectory(base,"sirin-application-alpha-")
    val log=new java.io.PrintStream(Files.newOutputStream(out.resolve("stdout.log")),true,"UTF-8")
    System.setOut(log);System.setErr(log)
    val scripted= !args.contains("interactive")
    val release=new CountDownLatch(1)
    val started=new AtomicBoolean(false)
    val saveWork=Work {
      started.set(true)
      while !release.await(20,TimeUnit.MILLISECONDS) && !summon[Cancellation].cancelled do ()
      if summon[Cancellation].cancelled then throw new InterruptedException("save cancelled")
      "saved"
    }
    val reopenWork=Work { "reopen" }
    var shared: Cell[String]=null
    var saved: Cell[String]=null
    var windows: KeyedCell[Int,WindowSpec]=null
    var save: Command[Unit,String]=null
    var reopen: Command[Unit,String]=null
    val locals=scala.collection.mutable.ArrayBuffer.empty[(Inputs[WindowSpec],NodeRef)]
    var windowBuilds=0;var windowDisposals=0;var documentDisposals=0
    var stage=0;var firstInstance=0L;var secondInstance=0L;var newInstance=0L
    var wakeBefore=0L;var closedWindowBeforeSave=false;var zeroWindowsSeen=false
    var finalSaved="";var finalText="";var failure=Option.empty[Throwable]
    val deadline=System.nanoTime()+180000000000L
    def ok[A](result: CommitResult[A]): A=result match
      case CommitResult.Committed(value) => value
      case CommitResult.Failed(error) => throw error
      case other => throw new IllegalStateException(s"$other")
    if scripted then DesktopApplicationPlatform.observer=Some { host =>
      require(System.nanoTime()<deadline,s"application scenario deadline at stage $stage")
      val peers=host.peers
      def read[A](signal: Signal[A]): A=ok(host.runtime.transact {signal.get})
      def ready=peers.nonEmpty && peers.forall(p => p.presentations>0 && p.controller.ready)
      println(s"stage=$stage peers=${peers.size} native=${host.native.stats().windows()} presentations=${peers.map(_.presentations)} wakes=${host.applicationWakes}")
      stage match
        case 0 if peers.size==2 && ready =>
          firstInstance=peers.head.instance;secondInstance=peers(1).instance
          // A partial native open failure must preserve the existing application/peers.
          NativeWindow.failAt(2)
          try
            try {val unexpected=new NativeWindow("Failure control",200,120);unexpected.close();throw new AssertionError("fault did not fail")}
            catch case _: IllegalStateException => ()
          finally NativeWindow.failAt(0)
          require(NativeWindow.liveWindows()==2 && NativeApplication.liveApplications()==1,"failed peer open retired another owner")
          ok(host.runtime.transact {locals.head._2.focus()})
          stage=1
        case 1 if ready && peers.head.controller.focusedId.nonEmpty && peers.head.hasPresentedEditorFocus && peers.head.window.keyboardFocus() =>
          peers.head.window.imeControl("First window edit",17,0,true)
          stage=2
        case 2 if ready && read(shared)=="First window edit" =>
          require(peers.forall(_.controller.snapshots.values.exists(_.text=="First window edit")),"shared document did not reach both windows")
          ok(host.runtime.transact {save.dispatch(())})
          stage=3
        case 3 if started.get() && peers.size==2 =>
          peers.head.window.inject(8,0,0,0,0,0);stage=4
        case 4 if peers.size==1 && peers.head.instance==secondInstance =>
          require(documentDisposals==0 && windowDisposals==1,"window close retired the document")
          require(host.runtime.scheduler.tickets.nonEmpty && !host.runtime.scheduler.tickets.head.cancellationRequested,"window close cancelled workspace save")
          closedWindowBeforeSave=true;release.countDown();stage=5
        case 5 if read(saved)=="First window edit" && peers.size==1 && ready =>
          peers.head.window.inject(8,0,0,0,0,0);stage=6
        case 6 if peers.isEmpty && host.native.stats().windows()==0 =>
          require(documentDisposals==0 && NativeApplication.liveApplications()==1,"last window closed the application")
          zeroWindowsSeen=true;wakeBefore=host.applicationWakes
          ok(host.runtime.transact {reopen.dispatch(())});stage=7
        case 7 if peers.size==1 && ready && host.applicationWakes>wakeBefore =>
          newInstance=peers.head.instance
          require(newInstance!=firstInstance && newInstance!=secondInstance,"reopened window reused a stale instance")
          require(read(shared)=="First window edit" && read(saved)=="First window edit","workspace state lost across zero windows")
          ok(host.runtime.transact {windows.replace(WindowSpec(3,"Reopened document"))});stage=8
        case 8 if peers.size==1 && ready && peers.head.window.title()=="Reopened document" =>
          require(peers.head.instance==newInstance,"title update remounted native peer")
          finalSaved=read(saved);finalText=read(shared)
          host.native.requestQuit();stage=9
        case _ => ()
    }
    try
      Application.run { app =>
        val workspace=app.child("workspace")
        val document=workspace.child("document")
        document.onDispose(() => documentDisposals+=1)
        shared=document.cell("");saved=document.cell("")
        windows=workspace.keyed(Seq(WindowSpec(1,"Document"),WindowSpec(2,"Second view")))(_.id)
        save=document.command[Unit,String,String]("document.save","Save",WorkPolicy.save(Capacity(1)))(_ => Job(shared.get,saveWork)) {
          (snapshot,result) => if result.outcome==Outcome.Succeeded("saved") then saved.set(snapshot)
        }
        reopen=workspace.command[Unit,Unit,String]("window.reopen","Open document",WorkPolicy.download)(_ => Job((),reopenWork)) {
          (_,result) => if result.outcome==Outcome.Succeeded("reopen") then windows.append(WindowSpec(3,"Document"))
        }
        Windows(windows) { (scope,input) =>
          windowBuilds+=1
          val ref=scope.ref();val local=input -> ref;locals+=local
          scope.onDispose(() => {windowDisposals+=1;locals-=local;()})
          Window(input.map(_.title),MenuBar.empty)(Column(
            Text("Document").style(TextStyle.Title),
            TextField.bind(shared).label("Shared document").multiline.ref(ref).grow,
            Text(saved.map(text => if text.isEmpty then "Not saved" else "Saved")),
            Row(Button(save),Button("Close view").onPress(windows.remove(input.get.id))).spacing(8.lp)
          ).spacing(10.lp).padding(16.lp))
        }
      }
      require(!scripted || (stage==9 && windowBuilds==3 && windowDisposals==3 && documentDisposals==1 && closedWindowBeforeSave && zeroWindowsSeen),s"incomplete scenario stage=$stage")
      require(NativeWindow.liveWindows()==0 && NativeApplication.liveApplications()==0 && NativeWindow.liveRequests()==0,"native application ownership leaked")
      require(sirin.render.BatchGpu.liveOwners==0 && sirin.render.BatchGpu.liveHealthRecords==0 && sirin.render.GpuFrame.liveFrames==0 && sirin.render.GpuFrame.liveTiles==0,"render ownership leaked")
    catch case error: Throwable =>
      failure=Some(error)
      error.printStackTrace()
      throw error
    finally
      release.countDown();DesktopApplicationPlatform.observer=None
      val receipt=s"""{
  "scripted": $scripted,
  "success": ${failure.isEmpty},
  "stage": $stage,
  "window_builds": $windowBuilds,
  "window_disposals": $windowDisposals,
  "document_disposals": $documentDisposals,
  "closed_window_before_save_completed": $closedWindowBeforeSave,
  "zero_native_windows_with_live_application": $zeroWindowsSeen,
  "shared_document_saved": ${finalSaved=="First window edit" && finalText=="First window edit"},
  "fresh_reopened_instance": ${newInstance>0 && newInstance!=firstInstance && newInstance!=secondInstance},
  "source_public_entry": "Application.run",
  "scope": "JVM development backend functional transport; actual IME candidate UI and full alpha unqualified"
}
"""
      Files.writeString(out.resolve("result.json"),receipt)
      log.close()
