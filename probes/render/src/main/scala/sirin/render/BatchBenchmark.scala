package sirin.render

import sirin.graphics.*
import sirin.graphics.text.NativeFont
import java.nio.file.{Files,Path as FilePath}

/** Frozen 320x180 standalone authored corpus. No mounted UI dependency. */
object BatchBenchmark:
  private val output=FilePath.of("/private/tmp/sirin-batch-benchmark")
  private def shaped(file: String,text: String,script: Script,dir: TextDirection): ShapedRun =
    val data=FontData.copy(Files.readAllBytes(FilePath.of(s"graphics/src/test/resources/fonts/$file")))
    val face=NativeFont.open(data,10)
    try
      val run=face.shape(text,ShapingOptions(script,dir,"und"))
      require(run.glyphs.forall(_.id!=0),s"benchmark missing glyphs: $file")
      run
    finally face.close()
  private lazy val labels=Vector(
    shaped("NotoSans-Regular.ttf","office café",Script.Latin,TextDirection.LeftToRight),
    shaped("NotoNaskhArabic-Regular.ttf","مرحبا",Script.Arabic,TextDirection.RightToLeft),
    shaped("NotoSansHebrew-Regular.ttf","שלום",Script.Hebrew,TextDirection.RightToLeft),
    shaped("NotoSansDevanagari-Regular.ttf","नमस्ते",Script("Deva"),TextDirection.LeftToRight))
  private val paint=Paint.LinearGradient(Point(0,0),Point(320,180),Vector(
    ColorStop(0,Color(0.05,0.65,1)),ColorStop(0.5,Color(1,0.1,0.4,0.65)),ColorStop(1,Color(0.7,1,0.1))))
  private def image(revision: Int): ImageSnapshot =
    ImageSnapshot.copyRgba8(64,64,Array.tabulate[Byte](64*64*4) { i =>
      val p=i/4;val x=p%64;val y=p/64
      (i%4 match
        case 0 => (x*4+revision*7)%256
        case 1 => y*4
        case 2 => 255-x*3
        case _ => if x<2 || y<2 || x>=62 || y>=62 then 0 else 210).toByte
    })
  def corpus(source: ImageSnapshot,overlay: Boolean=false): Drawing = Drawing.record { c =>
    c.fill(Path.rectangle(Rect(0,0,320,180)),Paint.Solid(Color(0.07,0.09,0.13)))
    // Repeated geometry with ordered varying translucent paint.
    for i <- 0 until 512 do
      c.fill(Path.rectangle(Rect(2+(i%32)*9,2+(i/32)*4,12,6)),
        Paint.Solid(Color((i%7)/6.0,(i%11)/10.0,(i%13)/12.0,0.12+(i%5)*0.12)))
    for i <- 0 until 100 do
      val x=3+(i%25)*12;val y=75+(i/25)*11
      val p=Path.build { b => b.moveTo(x,y+7);b.cubicTo(Point(x+2,y),Point(x+7,y+10),Point(x+10,y+2)) }
      c.stroke(p,paint,StrokeStyle(0.8,LineCap.Round,LineJoin.Round))
    for i <- 0 until 16 do c.glyphs(labels(i%4),Point(4+(i%4)*59,128+(i/4)*14),paint)
    val hole=Path(Path.rectangle(Rect(246,118,66,54)).segments++Path.rectangle(Rect(256,128,46,34)).segments)
    c.clipped(Path.roundedRectangle(Rect(242,112,74,64),7)) { a =>
      a.layer(0.8) { b =>
        b.image(source.full,Rect(246,116,64,56),Sampling.Linear)
        b.clipped(hole,FillRule.EvenOdd)(_.layer(0.6)(_.fill(hole,paint,FillRule.NonZero)))
      }
    }
    if overlay then c.fill(Path.rectangle(Rect(6,6,28,10)),Paint.Solid(Color(1,1,1,0.5)))
  }
  private def percentile(values: Vector[Double],p: Double): Double = values.sorted.apply(math.ceil(values.size*p).toInt-1)
  private def distribution(values: Vector[Double]): String =
    s"{\"p50\":${percentile(values,0.50)},\"p95\":${percentile(values,0.95)},\"p99\":${percentile(values,0.99)}}"
  private def stats(s: BatchGpuStats): String =
    s"{\"payloadBytes\":${s.payloadBytes},\"meshUploadBytes\":${s.meshUploadBytes},\"textureUploadBytes\":${s.textureUploadBytes},\"programUploadBytes\":${s.programUploadBytes},\"entries\":${s.entries},\"evictions\":${s.evictions}}"
  def main(args: Array[String]): Unit =
    Files.createDirectories(output)
    val source=image(0);val scene=corpus(source);val results=Vector.newBuilder[String]
    val gpu=BatchGpu.open()
    try
      println(s"benchmark-adapter=${gpu.generation.adapter}")
      for scale <- Vector(1.0,1.25,1.5,2.0) do
        val cold=Vector.fill(10) {
          val compiler=new BatchCompiler;val start=System.nanoTime()
          try
            val target=gpu.target((320*scale).toInt,(180*scale).toInt,scale)
            val prepared=DrawingPreparation.prepare(scene,target).fold(e => throw new IllegalStateException(e.toString),identity)
            compiler.compile(prepared)
            (System.nanoTime()-start)/1e6
          finally compiler.close()
        }
        val prepared=DrawingPreparation.prepare(scene,gpu.target((320*scale).toInt,(180*scale).toInt,scale)).toOption.get
        val program=gpu.compile(prepared);val retained=gpu.retain(program)
        try
          val frame=retained.render();Files.write(output.resolve(s"corpus-${frame.width}x${frame.height}.rgba"),frame.pixelsCopy)
          for _ <- 0 until 120 do retained.submit()
          val before=gpu.stats;val beforeCpu=gpu.cacheStats
          val measured=Vector.fill(600) {val start=System.nanoTime();retained.submit();(System.nanoTime()-start)/1e6}
          val after=gpu.stats
          require(after.meshUploadBytes==before.meshUploadBytes && after.textureUploadBytes==before.textureUploadBytes && after.programUploadBytes==before.programUploadBytes,"warm frame uploaded resources")
          require(gpu.cacheStats.rasterizations==beforeCpu.rasterizations,"warm frame rasterized")
          val readback=Vector.fill(10) {val start=System.nanoTime();retained.render();(System.nanoTime()-start)/1e6}
          val reference=OffscreenGpu.open()
          val comparison=try
            val oracle=BatchOracle.render(reference,prepared)
            Files.write(output.resolve(s"reference-${frame.width}x${frame.height}.rgba"),oracle.pixelsCopy)
            BatchOracle.compare(frame,oracle,prepared)
          finally reference.close()
          println(s"corpus-comparison scale=$scale $comparison")
          require(comparison.checked>frame.width*frame.height/2 && comparison.mismatches==0,s"corpus pixel oracle failed: $comparison")
          val overlayDrawing=DrawingPreparation.prepare(corpus(source,true),gpu.target(frame.width,frame.height,scale)).toOption.get
          val beforeOverlay=gpu.stats;val overlay=gpu.retain(gpu.compile(overlayDrawing))
          val overlayDelta=try
            overlay.submit()
            require(gpu.stats.textureUploadBytes==beforeOverlay.textureUploadBytes,"overlay uploaded unchanged textures")
            gpu.stats.meshUploadBytes-beforeOverlay.meshUploadBytes
          finally overlay.close()
          val updates=Vector.tabulate(32) { revision =>
            val start=System.nanoTime()
            val d=Drawing.record(_.image(image(revision+1).full,Rect(246,116,64,56),Sampling.Linear))
            val p=DrawingPreparation.prepare(d,gpu.target(frame.width,frame.height,scale)).toOption.get
            val r=gpu.retain(gpu.compile(p));try r.submit() finally r.close()
            (System.nanoTime()-start)/1e6
          }
          val pass=percentile(cold,0.95)<=5000 && percentile(measured,0.95)<=33.34
          val row=s"{\"scale\":$scale,\"coldMs\":${distribution(cold)},\"submitCompleteMs\":${distribution(measured)},\"readbackMs\":${distribution(readback)},\"replacementMs\":${distribution(updates)},\"oracleChecked\":${comparison.checked},\"oracleExcluded\":${comparison.excluded},\"oracleWorstByteError\":${comparison.worst},\"triangles\":${program.triangleCount},\"drawBatches\":${program.drawBatches},\"meshResources\":${program.meshes.size},\"textureResources\":${program.assets.size},\"grayCpuBytes\":${program.assets.filter(_.kind==2).map(_.bytes).sum},\"overlayMeshUploadBytes\":$overlayDelta,\"warmUploadBytes\":0,\"warmRasterizations\":0,\"statsBeforeUpdates\":${stats(after)},\"timingBudgetPassed\":$pass}"
          results+=row;println(row)
        finally retained.close()
        require(gpu.stats.programs==0 && gpu.stats.pinnedEntries==0,"benchmark leaked pins")
      val report=s"{\"backend\":\"${gpu.generation.backendBuild}\",\"adapter\":\"${gpu.generation.adapter}\",\"rows\":[${results.result().mkString(",")}] }"
      Files.writeString(output.resolve("measurements.json"),report)
    finally gpu.close()
    require(BatchGpu.liveOwners==0,"benchmark leaked GPU owner")
