package sirin.desktop

import java.nio.file.{Files,Path}
import sirin.graphics.PreparationCache
import sirin.render.{BatchGpu,GpuFrame}

/** Frozen warm renderer-completion measurement, separate from SDL input latency. */
object CameraMotionBenchmark:
  def main(args: Array[String]): Unit =
    require(args.isEmpty,"frozen camera workload")
    val folder=Path.of(System.getProperty("sirin.repo","."),"desktop/target/camera-runs",s"worker-${System.currentTimeMillis()}")
    Files.createDirectories(folder)
    val rows=scala.collection.mutable.ArrayBuffer("scale,phase,kind,index,milliseconds,compiled,mesh_upload,asset_upload,upload_bytes,work,reused_pixels,variants")
    var completed=false
    try
      for scale <- Vector(1.0,1.25,1.5,2.0) do
        val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep)
        val scene=CanvasFixture.drawing()
        var serial=0L
        try
          for kind <- Vector("pan","zoom");i <- 0 until 120 do
            serial+=1
            val camera=if kind=="pan" then CanvasCamera(i*0.37,i*0.2) else CanvasCamera(0.37,0.2,if i%2==0 then 1.25 else 1)
            val frame=canvas.render(CanvasRequest(serial,scene,camera,(320*scale).toInt,(180*scale).toInt,scale),() => false)
            try
              rows+=s"$scale,${if i<20 then "warmup" else "measured"},$kind,$i,${frame.nanos/1e6},${frame.compiled},${frame.meshUploaded},${frame.assetUploaded},${frame.uploaded},${frame.work},${frame.reusedPixels},${frame.variants}"
              if i>=20 then require(frame.compiled==0 && frame.meshUploaded==0 && frame.assetUploaded==0,"warm camera rebuilt GPU geometry")
            finally frame.close()
        finally {canvas.close();prep.close()}
      completed=true
    finally
      Files.writeString(folder.resolve("frames.csv"),rows.mkString("\n")+"\n")
      Files.writeString(folder.resolve("receipt.txt"),s"complete=$completed\ninputToPresent=false\nsamples=${rows.size-1}\n")
      println(s"cameraWorkerReceipt=$folder complete=$completed")
    require(BatchGpu.liveOwners==0 && BatchGpu.liveHealthRecords==0 && GpuFrame.liveFrames==0 && GpuFrame.liveTiles==0,"camera benchmark owners leaked")
