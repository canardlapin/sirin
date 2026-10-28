package sirin.graphics.desktop

import java.nio.file.{Files,Path as FilePath}
import sirin.graphics.*
import sirin.graphics.text.NativeFont
import sirin.render.{OffscreenGpu,PixelProgram}

class CachedRendererSuite extends munit.FunSuite:
  test("batched cached tiles match full renderer, omit only proven empty masks, and retire deterministically") {
    val face=NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("../graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),18)
    val gpu=OffscreenGpu.open()
    val cache=new TileCache(gpu,(font,id,scale,budget) => {
      assert(font eq face.font)
      face.rasterize(id,scale,maxPixels=budget)
    },32)
    try
      val known=face.shape("office cafe",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en"))
      // Missing ink metadata is not proof of zero coverage: actual masks decide.
      val unknown=new ShapedRun(known.source,known.font,known.options,known.glyphs.map(_.copy(ink=None)),known.range,known.context)
      def drawing(color: Color,run: ShapedRun)=Drawing.record { c =>
        c.fill(Path.rectangle(Rect(0,0,80,64)),Paint.Solid(color))
        c.clipped(Path.rectangle(Rect(3.25,1.5,72,60))) { c =>
          c.layer(0.7)(_.glyphs(run,Point(1.5,26),Paint.Solid(Color(1,1,1))))
        }
      }
      for scale <- Vector(1.0,2.0);run <- Vector(known,unknown) do
        val prepared=DrawingPreparation.prepare(drawing(Color(0.1,0.2,0.3),run),gpu.target((80*scale).toInt,(64*scale).toInt,scale)).toOption.get
        val expected=gpu.render(PixelProgram.compile(prepared)).pixelsCopy
        val first=cache.render(prepared,() => false)
        val actual=first.image.pixelsCopy
        assert(actual.indices.forall(i => math.abs((actual(i)&255)-(expected(i)&255))<=1))
        val uploaded=gpu.resourceStats.uploadedBytes
        val second=cache.render(prepared,() => false)
        assertEquals(second.programBytes,0L);assertEquals(second.readbackBytes,0L)
        assertEquals(gpu.resourceStats.uploadedBytes,uploaded)
        assertEquals(second.image.pixelsCopy.toVector,first.image.pixelsCopy.toVector)
        val changed=DrawingPreparation.prepare(drawing(Color(0.3,0.2,0.1),run),prepared.target).toOption.get
        val changedPixels=cache.render(changed,() => false).image.pixelsCopy
        assertEquals(changedPixels.toVector,gpu.render(PixelProgram.compile(changed)).pixelsCopy.toVector)
        assert(gpu.resourceStats.programs<=64 && gpu.resourceStats.retainedBytes<=48L*1024*1024)
      cache.close()
      assertEquals(gpu.resourceStats.programs,0);assertEquals(gpu.resourceStats.retainedBytes,0L)
    finally {cache.close();gpu.close();face.close()}
    assertEquals(OffscreenGpu.liveOwners,0)
  }
  test("cancelled partial batch stays bounded and can be retired without replay") {
    val gpu=OffscreenGpu.open()
    val cache=new TileCache(gpu,(_,_,_,_) => fail("no glyphs"),32)
    try
      val drawing=Drawing.record(_.fill(Path.rectangle(Rect(0,0,80,64)),Paint.Solid(Color(1,0,0))))
      val prepared=DrawingPreparation.prepare(drawing,gpu.target(80,64,1)).toOption.get
      var calls=0
      var cancelled=false
      try cache.render(prepared,() => {calls+=1;calls>2})
      catch case _: InterruptedException => cancelled=true
      assert(cancelled)
      assert(gpu.resourceStats.programs<=2)
      cache.close();assertEquals(gpu.resourceStats.programs,0)
    finally {cache.close();gpu.close()}
  }
  test("base image plus focus and press overlay matches the shared direct renderer at both scales") {
    import sirin.host.*
    import sirin.desktop.{RenderSession,RenderedFrame,DevelopmentTheme}
    val root=FilePath.of("..").toAbsolutePath.normalize()
    val id=NodeId(Vector("control",123))
    val node=Node(id,Content.Button(123,"Run",true,false,true))
    val session=new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/unused"),32)
    val gpu=OffscreenGpu.open()
    try
      for scale <- Vector(1.0,2.0);pressed <- Vector(None,Some(id)) do
        val payload=FramePayload(node,96,64,(96*scale).toInt,(64*scale).toInt,Map.empty,Some(id),true,pressed)
        val frame=session.render(FrameRequest(1,payload),() => false).asInstanceOf[RenderedFrame]
        val direct=Drawing.record { c =>
          c.draw(frame.layout.drawing(DevelopmentTheme))
          c.draw(frame.layout.overlay(DevelopmentTheme,Some(id),true,pressed))
        }
        val prepared=DrawingPreparation.prepare(direct,gpu.target(payload.pixelWidth,payload.pixelHeight,scale)).toOption.get
        val expected=gpu.render(PixelProgram.compile(prepared)).pixelsCopy
        val actual=frame.pixels.pixelsCopy
        assert(actual.indices.forall(i => math.abs((actual(i)&255)-(expected(i)&255))<=1))
    finally {session.close();gpu.close()}
    assertEquals(OffscreenGpu.liveOwners,0)
  }
  test("cancelled changed base cannot reuse partly replaced snapshots for the previous tree") {
    import sirin.host.*
    import sirin.desktop.{RenderSession,RenderedFrame}
    val root=FilePath.of("..").toAbsolutePath.normalize()
    val node=Node(NodeId(Vector("control",321)),Content.Button(321,"Run",true,false,true))
    val session=new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/unused"),32)
    val payload=FramePayload(node,96,64,96,64,Map.empty)
    try
      val expected=session.render(FrameRequest(1,payload),() => false).asInstanceOf[RenderedFrame].pixels.pixelsCopy
      var checks=0;var cancelled=false
      try session.render(FrameRequest(2,payload.copy(tree=node.copy(content=Content.Button(321,"Stop",true,false,true)))),() => {checks+=1;checks>6})
      catch case _: InterruptedException => cancelled=true
      assert(cancelled)
      val restored=session.render(FrameRequest(3,payload),() => false).asInstanceOf[RenderedFrame].pixels.pixelsCopy
      assertEquals(restored.toVector,expected.toVector)
    finally session.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }
