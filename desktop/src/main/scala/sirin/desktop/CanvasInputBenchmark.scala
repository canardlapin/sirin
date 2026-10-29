package sirin.desktop

import java.nio.file.{Files,Path}
import sirin.graphics.PreparationCache
import sirin.render.{BatchGpu,GpuFrame}

/** One injected SDL event to its matching successful actual-window presentation. */
object CanvasInputBenchmark:
  def main(args: Array[String]): Unit =
    require(args.isEmpty,"frozen native canvas workload")
    val folder=Path.of(System.getProperty("sirin.repo","."),"desktop/target/camera-runs",s"input-${System.currentTimeMillis()}")
    Files.createDirectories(folder)
    val rows=scala.collection.mutable.ArrayBuffer("phase,kind,index,input_to_present_ms,worker_ms,compiled,mesh_upload,asset_upload,logical_width,logical_height,pixel_width,pixel_height")
    val window=new NativeWindow("Sirin camera latency",320,180)
    var completed=false
    try run(window,rows,() => completed=true)
    finally
      window.close()
      Files.writeString(folder.resolve("frames.csv"),rows.mkString("\n")+"\n")
      Files.writeString(folder.resolve("receipt.txt"),s"complete=$completed\ninputToPresent=true\nsamples=${rows.size-1}\n")
      println(s"cameraInputReceipt=$folder complete=$completed")
    require(BatchGpu.liveOwners==0 && BatchGpu.liveHealthRecords==0 && GpuFrame.liveFrames==0 && GpuFrame.liveTiles==0,"canvas input owners leaked")
  private def run(window: NativeWindow,rows: scala.collection.mutable.ArrayBuffer[String],complete: () => Unit): Unit =
    val handle=window.prepareRenderer(BatchGpu.shaderSource)
    var preparation=Option.empty[PreparationCache]
    val service=new OwnedRenderService[CanvasRequest,CanvasFrame](() => {window.wake();()},_.generation,() => {
      val cache=preparation.getOrElse {val value=new PreparationCache;preparation=Some(value);value}
      new RetainedCanvas(BatchGpu.shared(handle),cache)
    },(frame,_,_) => frame,() => preparation.foreach(_.close()))
    var current=Option.empty[CanvasFrame]
    try
      val initial=window.waitEvent(0)
      val geometry=(initial.logicalWidth(),initial.logicalHeight(),initial.width(),initial.height())
      require(geometry._1==320 && geometry._2==180 && geometry._3<=640 && geometry._4<=360,"canvas benchmark geometry")
      val scale=geometry._3.toDouble/geometry._1
      val scene=CanvasFixture.drawing()
      var camera=CanvasCamera();var serial=1L;var step=0;var pending=Option.empty[Long];var started=false
      val deadline=System.nanoTime()+120000000000L
      def submit(): Unit=service.submit(CanvasRequest(serial,scene,camera,geometry._3,geometry._4,scale))
      def inject(): Unit=
        if step<120 then window.inject(1,5,0,0,0,0)
        else window.inject(6,0,0,160,90,if step%2==0 then 1 else -1)
      submit()
      while step<240 do
        require(System.nanoTime()<deadline,"canvas input deadline")
        val event=window.waitEvent(1000)
        require(!event.quit(),"canvas benchmark closed early")
        require((event.logicalWidth(),event.logicalHeight(),event.width(),event.height())==geometry,"canvas benchmark resized")
        require(!window.rendererLost(),"canvas benchmark device lost")
        val accepted=if step<120 then event.kind()==1 && event.key()==5 else event.kind()==6
        if accepted then
          require(started && pending.isEmpty,"unexpected canvas input")
          pending=Some(event.timestamp())
          camera=if step<120 then camera.copy(x=camera.x+0.37,y=camera.y+0.2) else camera.zoomAt(if step%2==0 then 1.25 else 0.8,sirin.graphics.Point(160,90))
          serial+=1;submit()
        service.poll().foreach {
          case (id,Right(frame)) if id==serial =>
            current.foreach(_.close());current=Some(frame)
            require(frame.frame.present(window),"canvas presentation refused")
            pending match
              case Some(at) =>
                val index=step%120;val phase=if index<20 then "warmup" else "measured";val kind=if step<120 then "pan" else "zoom"
                rows+=s"$phase,$kind,$index,${(NativeWindow.ticks()-at)/1e6},${frame.nanos/1e6},${frame.compiled},${frame.meshUploaded},${frame.assetUploaded},${geometry._1},${geometry._2},${geometry._3},${geometry._4}"
                if index>=20 then require(frame.compiled==0 && frame.meshUploaded==0 && frame.assetUploaded==0,"warm native camera rebuilt")
                step+=1;pending=None
              case None => require(!started,"unmatched canvas presentation");started=true
            if step<240 then inject()
          case (_,Right(frame)) => frame.close()
          case (_,Left(error)) => throw error
        }
      complete()
    finally
      current.foreach(_.close());service.close()
