package sirin.desktop

import java.nio.file.{Files,Path as FilePath}
import sirin.graphics.*
import sirin.render.{BatchGpu,GpuFrame,OffscreenGpu}

class RetainedCanvasSuite extends munit.FunSuite:
  override val munitTimeout=scala.concurrent.duration.Duration(5,"min")
  test("four-scale translated, zoomed and edited canvas agrees with independent reference") {
    val gpu=BatchGpu.open();val reference=OffscreenGpu.open();val prep=new PreparationCache
    val canvas=new RetainedCanvas(gpu,prep,64)
    val scene=CanvasFixture.drawing(CanvasFixture.paths.take(8))
    val folder=FilePath.of("target/canvas-pixels-final");Files.createDirectories(folder)
    try
      var serial=0L
      val edited=CanvasFixture.drawing(CanvasFixture.paths.take(8).updated(3,Path.rectangle(Rect(48.3,6.7,9.2,12.4))))
      for scale <- Vector(1.0,1.25,1.5,2.0);(camera,drawing) <- Vector((CanvasCamera(),scene),(CanvasCamera(0.37,4.2),scene),(CanvasCamera(-3.7,-2.1,1.25),scene),(CanvasCamera(0.37,4.2),edited)) do
        serial+=1
        val request=CanvasRequest(serial,drawing,camera,(96*scale).toInt,(64*scale).toInt,scale)
        val result=canvas.render(request,() => false)
        try
          val target=reference.target(request.width,request.height,scale).copy(logicalToDevice=camera.matrix(scale))
          val prepared=DrawingPreparation.prepare(drawing,target).toOption.get
          val expected=BatchOracle.render(reference,prepared)
          val actual=result.frame.readback()
          val comparison=BatchOracle.compare(actual,expected,prepared)
          println(s"canvasComparison sample=$serial scale=$scale camera=$camera edited=${drawing eq edited} result=$comparison")
          assertEquals(comparison.mismatches,0,comparison.firstMismatch.toString)
          assert(comparison.checked>actual.width*actual.height/2)
          val stem=s"$scale-$serial"
          Files.write(folder.resolve(stem+"-actual.rgba"),actual.pixelsCopy)
          Files.write(folder.resolve(stem+"-reference.rgba"),expected.pixelsCopy)
          FrameWorker.save(actual,folder.resolve(stem+".png"))
        finally result.close()
    finally {canvas.close();prep.close();reference.close()}
    assertEquals(BatchGpu.liveHealthRecords,0)
  }
  test("100-path pan and edit reuse geometry; unchanged canvas prepares and uploads nothing") {
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep,64)
    val scene=CanvasFixture.drawing()
    var request=CanvasRequest(1,scene,CanvasCamera(),320,180,1)
    def render(): CanvasFrame=canvas.render(request,() => false)
    try
      val cold=render();cold.close();val baseline=prep.stats
      request=request.copy(generation=2,camera=CanvasCamera(4,0.37))
      val pan=render();pan.close()
      // The background path is recorded once in this immutable drawing too.
      assertEquals(prep.stats.misses,baseline.misses)
      val before=gpu.stats
      request=request.copy(generation=3)
      val same=render()
      try
        assert(!same.prepared);assertEquals(same.compiled,0L);assertEquals(same.uploaded,0L)
        assertEquals(gpu.stats,before)
      finally same.close()
      val edited=CanvasFixture.paths.updated(49,Path.rectangle(Rect(140,50,10,12)))
      request=request.copy(generation=4,drawing=CanvasFixture.drawing(edited))
      val edit=render();edit.close()
      // One edited path and the freshly recorded background are the only misses.
      assertEquals(prep.stats.misses,baseline.misses+2)
    finally {canvas.close();prep.close()}
    assertEquals(GpuFrame.liveTiles,0)
  }
  test("actual device destruction invokes native loss, rejects old resources and rebuilds pixels") {
    val prep=new PreparationCache
    val old=BatchGpu.open();val oldGeneration=old.generation
    val scene=CanvasFixture.drawing(CanvasFixture.paths.take(2))
    val request=CanvasRequest(1,scene,CanvasCamera(),96,64,1)
    val canvas=new RetainedCanvas(old,prep,32)
    val frame=canvas.render(request,() => false)
    val expected=frame.frame.readback().pixelsCopy.toVector;val cached=prep.stats
    try
      old.destroyDevice()
      assert(old.isLost);assert(old.lossReason!=0,"actual native device-lost callback missing")
      intercept[GraphicsDeviceLost](old.target(96,64,1))
      intercept[GraphicsDeviceLost](frame.frame.readback())
      canvas.close()
      val next=BatchGpu.open();assert(next.generation ne oldGeneration)
      val restored=new RetainedCanvas(next,prep,32)
      try
        val rendered=restored.render(request.copy(generation=2),() => false)
        try assertEquals(rendered.frame.readback().pixelsCopy.toVector,expected) finally rendered.close()
        assertEquals(prep.stats.misses,cached.misses)
        val stale=DrawingPreparation.prepare(scene,RenderTarget(oldGeneration,96,64,Affine.identity)).toOption.get
        intercept[IllegalArgumentException](next.compile(stale))
      finally restored.close()
    finally {frame.close();canvas.close();prep.close()}
    assertEquals(BatchGpu.liveOwners,0);assertEquals(BatchGpu.liveHealthRecords,0)
  }
  test("pan reuses shaped glyph masks and decoded images without texture upload") {
    val face=sirin.graphics.text.NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("../graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),12)
    val run=try face.shape("office ",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en")) finally face.close()
    val scene=Drawing.record { c =>
      c.fill(Path.rectangle(Rect(-10,-10,120,90)),Paint.Solid(Color(0.1,0.2,0.3)))
      c.glyphs(run,Point(4,24),Paint.Solid(Color(1,1,1)))
      c.image(CanvasFixture.image.full,Rect(50.3,4.7,20.2,20.6),Sampling.Linear)
    }
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep,32)
    try
      val cold=canvas.render(CanvasRequest(1,scene,CanvasCamera(),96,64,1),() => false);cold.close()
      val cpu=gpu.cacheStats;val uploaded=gpu.stats.textureUploadBytes
      val moved=canvas.render(CanvasRequest(2,scene,CanvasCamera(0.37,1.3),96,64,1),() => false)
      moved.close()
      assertEquals(gpu.cacheStats.rasterizations,cpu.rasterizations)
      assertEquals(gpu.cacheStats.decodedImages,cpu.decodedImages)
      assertEquals(gpu.stats.textureUploadBytes,uploaded)
    finally {canvas.close();prep.close()}
  }
  test("retirement quiesces an in-flight request before replacement on the same worker") {
    for _ <- 0 until 20 do
      val entered=new java.util.concurrent.CountDownLatch(1)
      val worker=new java.util.concurrent.atomic.AtomicReference[Thread]()
      val closed=new java.util.concurrent.atomic.AtomicInteger(0)
      val ready=new java.util.concurrent.Semaphore(0)
      val service=new OwnedRenderService[Long,AutoCloseable](() => ready.release(),identity,() => new OwnedRenderBackend[Long,AutoCloseable]:
        def stats="retire control"
        def close(): Unit={closed.incrementAndGet();()}
        def render(id: Long,cancelled: () => Boolean): AutoCloseable =
          val before=worker.getAndSet(Thread.currentThread())
          require(before==null || (before eq Thread.currentThread()),"render owner changed across retirement")
          if id==1 then
            entered.countDown()
            while !cancelled() do Thread.`yield`()
            throw new InterruptedException("retired")
          new AutoCloseable {def close(): Unit=()}
      ,(value,_,_) => value)
      try
        service.submit(1)
        val began=entered.await(5,java.util.concurrent.TimeUnit.SECONDS)
        if !began then Thread.getAllStackTraces.forEach((t,stack) => if t.getName=="sirin-render-owner" then println(s"owner=${t.getState} stack=${stack.toVector.mkString("\n")}"))
        assert(began,s"worker did not enter: ${service.poll()}")
        val retireAt=System.nanoTime()
        service.quiesce()
        println(s"workerCancellation retireNs=${System.nanoTime()-retireAt}")
        assertEquals(closed.get(),1)
        assertEquals(service.poll(),None)
        service.submit(2)
        val resumed=ready.tryAcquire(5,java.util.concurrent.TimeUnit.SECONDS)
        assert(resumed,s"replacement did not render: ${service.poll()}")
        service.poll().get._2.toOption.get.close()
      finally service.close()
      assertEquals(closed.get(),2)
  }
