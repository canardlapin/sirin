package sirin.desktop

import java.nio.file.{Files,Path as FilePath}
import sirin.graphics.*
import sirin.render.{BatchGpu,GpuFrame}

/** Worker request-to-completion diagnostic. Excludes OS input and presentation. */
object CanvasBenchmark:
  def main(args: Array[String]): Unit =
    require(args.isEmpty,"canvas diagnostic has a frozen workload")
    val folder=FilePath.of(System.getProperty("sirin.repo","."),"desktop/target/canvas-runs",s"run-${System.currentTimeMillis()}-${System.nanoTime()}")
    Files.createDirectories(folder)
    val rows=scala.collection.mutable.ArrayBuffer("scale,kind,index,milliseconds,work,cache_hits,cache_misses,compiled,upload_bytes")
    var generation=0L
    for scale <- Vector(1.0,1.25,1.5,2.0) do
      val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep)
      val scene=CanvasFixture.drawing()
      var request=CanvasRequest(1,scene,CanvasCamera(),(320*scale).toInt,(180*scale).toInt,scale)
      def render(kind: String,index: Int): Unit =
        generation+=1;request=request.copy(generation=generation)
        val result=canvas.render(request,() => false)
        try
          rows+=s"$scale,$kind,$index,${result.nanos/1e6},${result.work},${result.cache.hits},${result.cache.misses},${result.compiled},${result.uploaded}"
          if kind=="unchanged" then require(!result.prepared && result.compiled==0 && result.uploaded==0,"unchanged canvas work")
        finally result.close()
      try
        render("cold",0)
        for _ <- 0 until 5 do render("warmup",0)
        for i <- 1 to 10 do render("unchanged",i)
        val beforePan=prep.stats.misses
        for i <- 1 to 10 do {request=request.copy(camera=CanvasCamera(i*0.37,i*0.2));render("pan",i)}
        require(prep.stats.misses==beforePan,"translation rebuilt source geometry")
        for i <- 1 to 10 do
          request=request.copy(drawing=CanvasFixture.drawing(CanvasFixture.paths.updated(49,Path.rectangle(Rect(140+i*0.01,50,10,12)))))
          render("edit",i)
        for i <- 1 to 10 do
          request=request.copy(camera=CanvasCamera(0.37,0.2,if i%2==0 then 1 else 1.25))
          render("zoom",i)
        require(prep.stats.entries<=256 && prep.stats.payloadBytes<=4194304,"CPU inventory")
      finally {canvas.close();prep.close()}
    Files.writeString(folder.resolve("frames.csv"),rows.mkString("\n")+"\n")
    require(BatchGpu.liveOwners==0 && BatchGpu.liveHealthRecords==0 && GpuFrame.liveFrames==0 && GpuFrame.liveTiles==0,"canvas diagnostic leaked")
    println(s"canvasDiagnostic=$folder samples=${rows.size-1} qualified=false inputToPresent=false")
