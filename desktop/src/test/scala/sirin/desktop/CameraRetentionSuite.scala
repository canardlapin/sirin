package sirin.desktop

import java.nio.file.{Files,Path as FilePath}
import sirin.graphics.*
import sirin.render.{BatchGpu,GpuFrame,OffscreenGpu}

class CameraRetentionSuite extends munit.FunSuite:
  override val munitTimeout=scala.concurrent.duration.Duration(5,"min")
  test("exact-scale camera preserves clipped glyphs, images, gradients and opacity at four scales") {
    val face=sirin.graphics.text.NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("../graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),12)
    val run=try face.shape("office",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en")) finally face.close()
    val scene=Drawing.record { c =>
      c.fill(Path.rectangle(Rect(-50,-50,200,150)),Paint.Solid(Color(0.08,0.12,0.2)))
      c.clipped(Path.rectangle(Rect(2.3,3.7,83.4,53.6))) { c =>
        c.layer(0.63) { c =>
          c.draw(CanvasFixture.drawing(CanvasFixture.paths.take(5)))
          c.glyphs(run,Point(3.7,42.3),Paint.Solid(Color(1,0.8,0.5)))
          c.image(CanvasFixture.image.full,Rect(-3.2,5.1,26.3,19.7),Sampling.Linear)
        }
      }
    }
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep);val ref=OffscreenGpu.open()
    val folder=FilePath.of("target/camera-pixels");Files.createDirectories(folder)
    try
      var serial=0L
      for scale <- Vector(1.0,1.25,1.5,2.0);camera <- Vector(CanvasCamera(),CanvasCamera(4/scale,-3/scale),CanvasCamera(-4/scale,3/scale),CanvasCamera(0.37,2.3),CanvasCamera(-8.2,-2.7,1.25),CanvasCamera(1.3,0.7,0.8)) do
        serial+=1
        val request=CanvasRequest(serial,scene,camera,(96*scale).toInt,(64*scale).toInt,scale)
        val frame=canvas.render(request,() => false)
        try
          val target=ref.target(request.width,request.height,scale).copy(logicalToDevice=camera.matrix(scale))
          val prepared=DrawingPreparation.prepare(scene,target).toOption.get
          val expected=BatchOracle.render(ref,prepared);val actual=frame.frame.readback()
          val comparison=BatchOracle.compare(actual,expected,prepared)
          val all=actual.pixelsCopy.zip(expected.pixelsCopy).map((a,b) => math.abs((a&255)-(b&255))).max
          println(s"cameraPixels sample=$serial scale=$scale camera=$camera result=$comparison allByteWorst=$all reuse=${frame.reusedPixels}")
          Files.write(folder.resolve(s"$serial-actual.rgba"),actual.pixelsCopy)
          Files.write(folder.resolve(s"$serial-reference.rgba"),expected.pixelsCopy)
          FrameWorker.save(actual,folder.resolve(s"$serial.png"))
          assertEquals(comparison.mismatches,0,comparison.firstMismatch.toString)
          assert(comparison.checked>request.width*request.height/2)
        finally frame.close()
    finally {canvas.close();prep.close();ref.close()}
    assertEquals(BatchGpu.liveOwners,0);assertEquals(GpuFrame.outputBytes,0L)
  }
  test("cached pan and zoom upload no geometry; overlap, edits and old snapshots stay exact") {
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep)
    val scene=CanvasFixture.drawing(CanvasFixture.paths.take(5))
    val request=CanvasRequest(1,scene,CanvasCamera(),96,64,1)
    val old=canvas.render(request,() => false)
    val bytes=old.frame.readback().pixelsCopy.toVector
    try
      val warm=canvas.render(request.copy(generation=2,camera=CanvasCamera(zoom=1.25)),() => false);warm.close()
      val base=canvas.render(request.copy(generation=21),() => false);base.close()
      val pan=canvas.render(request.copy(generation=3,camera=CanvasCamera(4,-3)),() => false)
      try
        assertEquals(pan.compiled,0L);assertEquals(pan.meshUploaded,0L);assertEquals(pan.assetUploaded,0L)
        assertEquals(pan.reusedPixels,92L*61)
      finally pan.close()
      val zoom=canvas.render(request.copy(generation=4,camera=CanvasCamera(0.37,1.3,1.25)),() => false)
      try {assertEquals(zoom.compiled,0L);assertEquals(zoom.meshUploaded,0L);assertEquals(zoom.assetUploaded,0L)} finally zoom.close()
      val reset=canvas.render(request.copy(generation=5),() => false);reset.close()
      val changed=CanvasFixture.drawing(CanvasFixture.paths.take(5).updated(3,Path.rectangle(Rect(48.3,6.7,9.2,12.4))))
      val edit=canvas.render(request.copy(generation=6,drawing=changed),() => false)
      try
        assert(edit.reusedPixels>96L*64*0.8)
        val reference=OffscreenGpu.open()
        try
          val prepared=DrawingPreparation.prepare(changed,reference.target(96,64,1)).toOption.get
          val expected=BatchOracle.render(reference,prepared);val actual=edit.frame.readback()
          val comparison=BatchOracle.compare(actual,expected,prepared)
          val folder=FilePath.of("target/camera-edited-pixels");Files.createDirectories(folder)
          Files.write(folder.resolve("actual.rgba"),actual.pixelsCopy);Files.write(folder.resolve("reference.rgba"),expected.pixelsCopy)
          FrameWorker.save(actual,folder.resolve("actual.png"))
          println(s"editedPixels result=$comparison reused=${edit.reusedPixels}")
          assertEquals(comparison.mismatches,0,comparison.firstMismatch.toString)
        finally reference.close()
      finally edit.close()
      assertEquals(old.frame.readback().pixelsCopy.toVector,bytes)
    finally {old.close();canvas.close();prep.close()}
    assertEquals(GpuFrame.liveFrames,0);assertEquals(GpuFrame.liveTiles,0)
  }
  test("one hundred edits and exact scales evict variants within original inventories") {
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep)
    try
      for i <- 1 to 100 do
        val scene=CanvasFixture.drawing(Vector(Path.rectangle(Rect(5+i*0.01,3,10,12))))
        val frame=canvas.render(CanvasRequest(i,scene,CanvasCamera(zoom=Vector(1.0,1.25,1.5)(i%3)),96,64,1),() => false)
        try
          assert(frame.variants<=2);assert(gpu.stats.programs<=2);assert(gpu.stats.payloadBytes<=201326592)
          assert(frame.cache.entries<=256 && frame.cache.payloadBytes<=4194304)
          assert(GpuFrame.outputBytes<=16777216)
        finally frame.close()
    finally {canvas.close();prep.close()}
    assertEquals(BatchGpu.liveOwners,0);assertEquals(BatchGpu.liveHealthRecords,0);assertEquals(GpuFrame.outputBytes,0L)
  }
  test("cancellation after GPU completion publishes no frame and preserves displayed snapshot") {
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep)
    val request=CanvasRequest(1,CanvasFixture.drawing(CanvasFixture.paths.take(2)),CanvasCamera(),96,64,1)
    val old=canvas.render(request,() => false);val expected=old.frame.readback().pixelsCopy.toVector
    try
      var checks=0;var interrupted=false
      try canvas.render(request.copy(generation=2,camera=CanvasCamera(0.37,1.3)),() => {checks+=1;checks>3})
      catch case _: InterruptedException => interrupted=true
      assert(interrupted);assertEquals(GpuFrame.liveFrames,1)
      assertEquals(old.frame.readback().pixelsCopy.toVector,expected)
      val next=canvas.render(request.copy(generation=3),() => false)
      try assertEquals(next.frame.readback().pixelsCopy.toVector,expected) finally next.close()
    finally {old.close();canvas.close();prep.close()}
    assertEquals(GpuFrame.liveFrames,0);assertEquals(GpuFrame.outputBytes,0L)
  }
  test("large targets use existing tiles and returning to camera retires cleanly") {
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep)
    val request=CanvasRequest(1,CanvasFixture.drawing(CanvasFixture.paths.take(1)),CanvasCamera(),96,64,1)
    try
      for (w,h,id) <- Vector((96,64,1L),(641,24,2L),(96,64,3L)) do
        val f=canvas.render(request.copy(generation=id,width=w,height=h),() => false)
        try assertEquals(f.frame.readback().width,w) finally f.close()
    finally {canvas.close();prep.close()}
    assertEquals(GpuFrame.outputBytes,0L);assertEquals(BatchGpu.liveOwners,0)
  }
  test("camera precision refusal rebuilds through the clipped tiled path") {
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep)
    val scene=Drawing.record(_.fill(Path.rectangle(Rect(-900000,-900000,1800000,1800000)),Paint.Solid(Color(0.2,0.4,0.6))))
    try
      val frame=canvas.render(CanvasRequest(1,scene,CanvasCamera(0.37,1.3),96,64,1),() => false)
      try
        assertEquals(frame.variants,0)
        val pixels=frame.frame.readback().pixelsCopy
        assertEquals(pixels.take(4).toVector,Vector(51,102,153,255).map(_.toByte))
        assert(pixels.grouped(4).forall(_.toVector==pixels.take(4).toVector))
      finally frame.close()
    finally {canvas.close();prep.close()}
    assertEquals(GpuFrame.outputBytes,0L);assertEquals(BatchGpu.liveOwners,0)
  }
  test("dense100-path exact-scale variants share meshes under unchanged admission and match reference") {
    val gpu=BatchGpu.open();val prep=new PreparationCache;val canvas=new RetainedCanvas(gpu,prep);val ref=OffscreenGpu.open()
    val scene=CanvasFixture.drawing()
    val folder=FilePath.of("target/camera-dense-pixels");Files.createDirectories(folder)
    try
      var serial=0L
      for scale <- Vector(1.0,1.25,1.5,2.0);zoom <- Vector(1.0,1.25) do
        serial+=1
        val request=CanvasRequest(serial,scene,CanvasCamera(0.37,0.2,zoom),(320*scale).toInt,(180*scale).toInt,scale)
        val frame=canvas.render(request,() => false)
        try
          val target=ref.target(request.width,request.height,scale).copy(logicalToDevice=request.camera.matrix(scale))
          val prepared=DrawingPreparation.prepare(scene,target).toOption.get
          val expected=BatchOracle.render(ref,prepared);val actual=frame.frame.readback()
          val comparison=BatchOracle.compare(actual,expected,prepared)
          val all=actual.pixelsCopy.zip(expected.pixelsCopy).map((a,b) => math.abs((a&255)-(b&255))).max
          println(s"densePixels sample=$serial scale=$scale zoom=$zoom result=$comparison allByteWorst=$all meshes=${gpu.cacheStats.meshEntries}")
          Files.write(folder.resolve(s"$serial-actual.rgba"),actual.pixelsCopy)
          Files.write(folder.resolve(s"$serial-reference.rgba"),expected.pixelsCopy)
          FrameWorker.save(actual,folder.resolve(s"$serial.png"))
          assertEquals(comparison.mismatches,0,comparison.firstMismatch.toString)
          assert(frame.variants<=2 && gpu.stats.programs<=2)
        finally frame.close()
        val moved=canvas.render(request.copy(generation=serial+100,camera=request.camera.copy(x=0.74)),() => false)
        try {assertEquals(moved.compiled,0L);assertEquals(moved.meshUploaded,0L);assertEquals(moved.assetUploaded,0L)} finally moved.close()
    finally {canvas.close();prep.close();ref.close()}
    assertEquals(BatchGpu.liveOwners,0);assertEquals(GpuFrame.outputBytes,0L)
  }
