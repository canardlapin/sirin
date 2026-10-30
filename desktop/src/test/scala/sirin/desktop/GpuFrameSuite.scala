package sirin.desktop

import java.nio.file.{Files,Path as FilePath}
import sirin.graphics.*
import sirin.graphics.desktop.GpuTileCache
import sirin.graphics.text.NativeFont
import sirin.render.{BatchGpu,GpuFrame,OffscreenGpu,PixelProgram}

class GpuFrameSuite extends munit.FunSuite:
  override val munitTimeout=scala.concurrent.duration.Duration(5,"min")
  private val scales=Vector(1.0,1.25,1.5,2.0)
  private def same(a: ImageSnapshot,b: ImageSnapshot): Unit =
    assertEquals((a.width,a.height),(b.width,b.height))
    val actual=a.pixelsCopy;val expected=b.pixelsCopy
    val worst=actual.indices.map(i => math.abs((actual(i)&255)-(expected(i)&255))).max
    val bad=actual.indices.filter(i => math.abs((actual(i)&255)-(expected(i)&255))>2)
    if bad.nonEmpty then
      val i=bad.maxBy(i => math.abs((actual(i)&255)-(expected(i)&255)))
      println(s"size=${a.width}x${a.height} mismatchBytes=${bad.size} worst=$worst xy=${(i/4)%a.width},${i/4/a.width} actual=${a.pixel((i/4)%a.width,i/4/a.width)} expected=${b.pixel((i/4)%a.width,i/4/a.width)}")
      Files.createDirectories(FilePath.of("target/gpu-frame-failure"))
      Files.write(FilePath.of(s"target/gpu-frame-failure/actual-${a.width}.rgba"),actual)
      Files.write(FilePath.of(s"target/gpu-frame-failure/reference-${a.width}.rgba"),expected)
    assert(worst<=2,s"pixel byte difference=$worst")
  test("exported GPU tiles match independent reference at all four scales and survive eviction and resize") {
    val face=NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("../graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),12)
    val gpu=BatchGpu.open();val reference=OffscreenGpu.open();val cache=new GpuTileCache(gpu,32)
    val image=ImageSnapshot.copyRgba8(2,2,Array[Byte](-1,0,0,-1,0,-1,0,-1,0,0,-1,-1,-1,-1,0,-1))
    val run=face.shape("office",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en"))
    val gradient=Paint.LinearGradient(Point(0,0),Point(96,64),Vector(ColorStop(0,Color(1,0,0)),ColorStop(1,Color(0,0,1))))
    def scene(changed: Boolean)=Drawing.record { c =>
      c.fill(Path.rectangle(Rect(0,0,96,64)),Paint.Solid(if changed then Color(0.2,0.3,0.4) else Color(0.1,0.2,0.3)))
      c.clipped(Path.rectangle(Rect(3.3,2.7,85.1,58.2))) { c =>
        c.layer(0.7) { c =>
          c.fill(Path.rectangle(Rect(8.3,5.7,42.1,16.2)),gradient)
          c.glyphs(run,Point(6.3,39.7),Paint.Solid(Color(1,1,1)))
          c.image(image.full,Rect(57.3,7.7,19.1,25.2),Sampling.Linear)
        }
      }
    }
    try
      for scale <- scales do
        val d=scene(false);val target=gpu.target((96*scale).toInt,(64*scale).toInt,scale)
        val prepared=DrawingPreparation.prepare(d,target).toOption.get
        val expected=reference.render(PixelProgram.compile(DrawingPreparation.prepare(d,reference.target(target.width,target.height,scale)).toOption.get))
        val whole=gpu.retain(gpu.compile(prepared))
        try same(whole.render(),expected) finally whole.close()
        val old=cache.render(prepared,() => false)
        try
          val actual=old.readback();val presented=old.presentationReadback()
          same(actual,expected);same(presented,expected)
          val folder=FilePath.of("target/gpu-frame-evidence")
          Files.createDirectories(folder)
          for (name,value) <- Vector(("reference",expected),("batch-tiles",actual),("presentation",presented)) do
            val stem=s"$name-${target.width}x${target.height}"
            Files.write(folder.resolve(stem+".rgba"),value.pixelsCopy)
            FrameWorker.save(value,folder.resolve(stem+".png"))
          val before=gpu.stats
          val replay=cache.render(prepared,() => false)
          try
            assertEquals(cache.lastCompiled,0L)
            assertEquals(gpu.stats,before)
            same(replay.readback(),expected)
          finally replay.close()
          // Cache retirement must not invalidate the complete frame held by main.
          cache.close();assertEquals(gpu.stats.programs,0L)
          same(old.readback(),expected)
          val resized=cache.render(DrawingPreparation.prepare(Drawing.empty,gpu.target(16,16,1)).toOption.get,() => false)
          try same(old.readback(),expected) finally resized.close()
        finally old.close()
      cache.close();assertEquals(GpuFrame.liveTiles,0);assertEquals(GpuFrame.outputBytes,0L)
    finally {cache.close();gpu.close();reference.close();face.close()}
    assertEquals(GpuFrame.liveFrames,0);assertEquals(BatchGpu.liveOwners,0)
  }
  test("host unchanged requests prepare and upload nothing; focus and press preserve shared pixels") {
    import sirin.host.*
    val root=FilePath.of("..").toAbsolutePath.normalize()
    val gpu=BatchGpu.open();val reference=OffscreenGpu.open()
    val session=new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/unused"),32,Some(gpu))
    val id=NodeId(Vector("control",123));val node=Node(id,Content.Button(123,"Run",true,false,true))
    var generation=0L
    try
      for scale <- scales;pressed <- Vector(None,Some(id)) do
        val payload=FramePayload(node,96,64,(96*scale).toInt,(64*scale).toInt,Map.empty,Some(id),true,pressed)
        generation+=1
        val first=session.render(FrameRequest(generation,payload),() => false).asInstanceOf[RenderedFrame]
        try
          val d=Drawing.record { c => c.draw(first.layout.drawing(DevelopmentTheme));c.draw(first.layout.overlay(DevelopmentTheme,Some(id),true,pressed)) }
          val expected=reference.render(PixelProgram.compile(DrawingPreparation.prepare(d,reference.target(payload.pixelWidth,payload.pixelHeight,scale)).toOption.get))
          same(first.gpuFrame.get.readback(),expected)
          val before=gpu.stats;val cpu=gpu.cacheStats
          generation+=1
          val second=session.render(FrameRequest(generation,payload),() => false).asInstanceOf[RenderedFrame]
          try
            assert(second.profile.startsWith("prepared=0 tilePrepared=0 compiled=0 submissions=0"),second.profile)
            assertEquals(second.programBytes,0L);assertEquals(second.readbackBytes,0L)
            assertEquals(gpu.stats,before);assertEquals(gpu.cacheStats,cpu)
          finally second.close()
        finally first.close()
    finally {session.close();reference.close()}
    assertEquals(GpuFrame.liveFrames,0);assertEquals(GpuFrame.liveTiles,0);assertEquals(BatchGpu.liveOwners,0)
  }
  test("frame coverage, foreign generation and outstanding frame limits reject without leaked references") {
    val a=BatchGpu.open();val b=BatchGpu.open()
    def tile(g: BatchGpu)=
      val r=g.retain(g.compile(DrawingPreparation.prepare(Drawing.empty,g.target(16,16,1)).toOption.get))
      try {r.submit();r.exportTile()} finally r.close()
    val x=tile(a);val y=tile(b)
    try
      intercept[IllegalArgumentException](GpuFrame(Vector((0,0,x),(16,0,y)),32,16))
      intercept[IllegalArgumentException](GpuFrame(Vector((0,0,x)),32,16))
      intercept[IllegalArgumentException](GpuFrame(Vector((0,0,x),(0,0,x)),16,16))
      val frames=Vector.fill(3)(GpuFrame(Vector((0,0,x)),16,16))
      try intercept[IllegalArgumentException](GpuFrame(Vector((0,0,x)),16,16))
      finally frames.foreach(_.close())
    finally {x.close();y.close();a.close();b.close()}
    assertEquals(GpuFrame.liveFrames,0);assertEquals(GpuFrame.liveTiles,0)
  }
  test("cancelled pending tile groups retire and retry without incomplete reuse") {
    val gpu=BatchGpu.open();val cache=new GpuTileCache(gpu,32)
    val drawing=Drawing.record(_.fill(Path.rectangle(Rect(0,0,96,64)),Paint.Solid(Color(1,0,0))))
    val prepared=DrawingPreparation.prepare(drawing,gpu.target(96,64,1)).toOption.get
    try
      var checks=0
      var cancelled=false
      try cache.render(prepared,() => {checks+=1;checks>3})
      catch case _: InterruptedException => cancelled=true
      assert(cancelled,"pending group cancellation was not observed")
      assertEquals(gpu.stats.programs, 0L)
      assertEquals(gpu.stats.pinnedEntries, 0L)
      assertEquals(GpuFrame.liveFrames, 0)
      val before=gpu.stats.submissions
      val frame=cache.render(prepared,() => false)
      try
        assertEquals(gpu.stats.programs,0L)
        assertEquals(gpu.stats.submissions-before,2L)
        assertEquals(frame.readback().pixel(80,48),Color(1,0,0))
        cache.close()
        assertEquals(gpu.stats.programs,0L)
        assertEquals(frame.readback().pixel(80,48),Color(1,0,0))
      finally frame.close()
    finally {cache.close();gpu.close()}
    assertEquals(GpuFrame.liveTiles,0);assertEquals(GpuFrame.outputBytes,0L)
  }
  test("moving zero-ink spaces cannot invalidate disjoint tiles") {
    val face=NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("../graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),12)
    val gpu=BatchGpu.open();val reference=OffscreenGpu.open();val cache=new GpuTileCache(gpu,32)
    val run=face.shape("office ",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en"))
    assert(run.glyphs.exists(_.ink.isEmpty),"fixture needs a zero-ink space")
    def scene(y: Double)=Drawing.record { c =>
      c.fill(Path.rectangle(Rect(0,0,96,96)),Paint.Solid(Color(0.1,0.2,0.3)))
      c.glyphs(run,Point(4,y),Paint.Solid(Color(1,1,1)))
    }
    try
      val first=cache.render(DrawingPreparation.prepare(scene(18),gpu.target(96,96,1)).toOption.get,() => false)
      first.close()
      val second=cache.render(DrawingPreparation.prepare(scene(22),gpu.target(96,96,1)).toOption.get,() => false)
      try
        assert(cache.lastCompiled<=4,s"disjoint tiles were invalidated: ${cache.lastCompiled} of 9")
        val expected=reference.render(PixelProgram.compile(DrawingPreparation.prepare(scene(22),reference.target(96,96,1)).toOption.get))
        same(second.readback(),expected)
      finally second.close()
    finally {cache.close();gpu.close();reference.close();face.close()}
    assertEquals(GpuFrame.liveTiles,0)
  }
  test("obsolete and unpolled GPU frames retire without a later request") {
    import sirin.host.*
    import java.util.concurrent.{CountDownLatch,TimeUnit}
    val entered=new CountDownLatch(1);val release=new CountDownLatch(1)
    val ready=new java.util.concurrent.Semaphore(0)
    val node=Node(NodeId(Vector("test")),Content.Space(0))
    def request(id: Long)=FrameRequest(id,FramePayload(node,16,16,16,16,Map.empty))
    val service=new RenderService(FilePath.of("unused"),FilePath.of("unused"),() => ready.release(),Some(() => new RenderBackend:
      private val gpu=BatchGpu.open()
      private val retained=gpu.retain(gpu.compile(DrawingPreparation.prepare(Drawing.empty,gpu.target(16,16,1)).toOption.get))
      retained.submit()
      private val tile=retained.exportTile()
      retained.close()
      def stats="GPU frame retirement test"
      def close(): Unit={tile.close();gpu.close()}
      def render(r: FrameRequest,cancelled: () => Boolean): FrameOutput =
        val frame=GpuFrame(Vector((0,0,tile)),16,16)
        if r.generation==1 then
          entered.countDown()
          if !release.await(5,TimeUnit.SECONDS) then {frame.close();throw new IllegalStateException("release timeout")}
        RenderedFrame(r,sirin.host.Layout(node,16,16)(_ => fail("no text")),null,0,0,0,0,"test",1,FilePath.of("unused"),gpuFrame=Some(frame))
    ))
    try
      service.submit(request(1));assert(entered.await(5,TimeUnit.SECONDS))
      service.submit(request(2));release.countDown();assert(ready.tryAcquire(5,TimeUnit.SECONDS))
      val frame=service.poll().get._2.toOption.get
      assertEquals(frame.request.generation,2L);assertEquals(GpuFrame.liveFrames,1)
      frame.close();assertEquals(GpuFrame.liveFrames,0)
      service.submit(request(3));assert(ready.tryAcquire(5,TimeUnit.SECONDS))
      assertEquals(GpuFrame.liveFrames,1)
      // Close drains the unpolled mailbox and joins the resource-owning thread.
      service.close();assertEquals(GpuFrame.liveFrames,0);assertEquals(GpuFrame.liveTiles,0)
    finally {release.countDown();service.close()}
    assertEquals(BatchGpu.liveOwners,0)
  }
