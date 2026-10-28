package sirin.render

import sirin.graphics.*
import sirin.graphics.text.NativeFont
import java.nio.file.{Files,Path as FilePath}
import java.util.concurrent.atomic.AtomicReference

class CurvesReuseSuite extends munit.FunSuite:
  private val scales=Vector(1.0,1.25,1.5,2.0)
  private val output=FilePath.of("/private/tmp/sirin-curves-reuse-pixels")
  private val red=Paint.Solid(Color(1,0,0))
  private def prepared(gpu: OffscreenGpu,d: Drawing,s: Double=1,width: Int=160,height: Int=120): PreparedDrawing =
    DrawingPreparation.prepare(d,gpu.target((width*s).toInt,(height*s).toInt,s)).fold(e => fail(e.toString),identity)
  private def encode(x: Double)=if x<=0.0031308 then 12.92*x else 1.055*math.pow(x,1/2.4)-0.055
  private def near(a: Color,b: Color): Unit =
    Vector(a.red-b.red,a.green-b.green,a.blue-b.blue,a.alpha-b.alpha).foreach(v => assert(math.abs(v)<=1.0/255,s"actual=$a expected=$b"))
  private def save(name: String,image: ImageSnapshot): Unit =
    Files.createDirectories(output)
    Files.write(output.resolve(s"$name-${image.width}x${image.height}.rgba"),image.pixelsCopy)
  private def run: ShapedRun =
    val bytes=FontData.copy(Files.readAllBytes(FilePath.of("graphics/src/test/resources/fonts/NotoSans-Regular.ttf")))
    val face=NativeFont.open(bytes,24)
    try face.shape("office",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en")) finally face.close()
  private def picture(color: Color): ImageSnapshot =
    ImageSnapshot.copyRgba8(4,4,Array.tabulate[Byte](64)(i =>
      (Vector(color.red,color.green,color.blue,color.alpha)(i%4)*255).round.toByte))

  test("four-scale actual curved stroke pixels agree with independent nearest-curve sample coverage") {
    val path=Path.build { p => p.moveTo(3,25);p.quadraticTo(Point(16,0),Point(29,25)) }
    val d=Drawing.record(_.stroke(path,red,StrokeStyle(1.5,LineCap.Round)))
    // A dense independent chord oracle has less than four micro-units of center error here.
    val reference=Vector.tabulate(2001) { i =>
      val t=i/2000.0;val q=1-t
      Point(q*q*3+2*q*t*16+t*t*29,q*q*25+t*t*25)
    }
    def distance(p: Point): Double =
      var best=Double.PositiveInfinity
      var i=0
      while i<reference.size-1 do
        val a=reference(i);val b=reference(i+1);val dx=b.x-a.x;val dy=b.y-a.y
        val t=math.max(0,math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)))
        val x=p.x-a.x-dx*t;val y=p.y-a.y-dy*t
        best=math.min(best,x*x+y*y);i+=1
      math.sqrt(best)
    val gpu=OffscreenGpu.open()
    try scales.foreach { scale =>
      val image=gpu.render(PixelProgram.compile(prepared(gpu,d,scale,32,32)));save("curve",image)
      var checked=0;var ink=0
      for py <- 0 until image.height by 3;px <- 0 until image.width by 3 do
        val distances=for sy <- 0 until 4;sx <- 0 until 4 yield
          distance(Point((px+(sx+0.5)/4)/scale,(py+(sy+0.5)/4)/scale))
        // Approximation-band boundaries are qualified geometrically, not
        // asserted to produce exact analytic pixels at every sample tie.
        if distances.forall(v => math.abs(v-0.75)>0.125/scale) then
          val coverage=distances.count(_<0.75)/16.0
          near(image.pixel(px,py),if coverage==0 then Color(0,0,0,0) else Color(1,0,0,coverage))
          checked+=1;if coverage>0 then ink+=1
      assert(checked>50 && ink>0,s"insufficient independent pixel coverage at $scale")
    }
    finally gpu.close()
  }

  test("four-scale gradient glyph coverage matches an independent FreeType and linear-light scalar oracle") {
    val shaped=run
    val gradient=Paint.LinearGradient(Point(0,0),Point(100,0),Vector(ColorStop(0,Color(1,0,0)),ColorStop(1,Color(0,0,1,0.25))))
    val d=Drawing.record(_.transformed(Affine.translation(4,6)) { c =>
      c.clipped(Path.rectangle(Rect(8,0,120,60)))(_.layer(0.6)(_.glyphs(shaped,Point(8.25,32.5),gradient)))
    })
    val gpu=OffscreenGpu.open()
    try scales.foreach { scale =>
      val p=PixelProgram.compile(prepared(gpu,d,scale))
      val image=gpu.render(p);save("gradient-text",image)
      val face=NativeFont.open(shaped.font.data,shaped.font.size)
      val masks=try shaped.glyphs.map(g => (g,face.rasterize(g.id,scale))) finally face.close()
      val coverages=masks.map((_,m) => m.coverageCopy)
      def oracle(px: Int,py: Int): Color =
        val samples=for sy <- 0 until 4;sx <- 0 until 4 yield
          val x=(px+(sx+0.5)/4)/scale-4;val y=(py+(sy+0.5)/4)/scale-6
          val t=math.max(0,math.min(1,x/100));val paint=Vector(1-t,0.0,t*0.25,1-t*0.75)
          val coverage=masks.zipWithIndex.foldLeft(Vector.fill(4)(0.0)) { case (dst,((glyph,mask),index)) =>
            val left=8.25+glyph.position.x+mask.left/mask.deviceScale
            val top=32.5+glyph.position.y-mask.top/mask.deviceScale
            val u=(x-left)*mask.deviceScale;val v=(y-top)*mask.deviceScale
            var a=0.0
            if x>=8 && x<128 && y>=0 && y<60 && u>=0 && u<mask.width && v>=0 && v<mask.height then
              val ix=math.floor(u-0.5).toInt;val iy=math.floor(v-0.5).toInt
              val fx=u-0.5-ix;val fy=v-0.5-iy
              def value(xx: Int,yy: Int)=(coverages(index)(math.max(0,math.min(mask.height-1,yy))*mask.width+math.max(0,math.min(mask.width-1,xx)))&255)/255.0
              a=(value(ix,iy)*(1-fx)+value(ix+1,iy)*fx)*(1-fy)+(value(ix,iy+1)*(1-fx)+value(ix+1,iy+1)*fx)*fy
            Vector.tabulate(4)(c => paint(c)*a+dst(c)*(1-paint(3)*a))
          }
          coverage.map(_*0.6)
        val value=Vector.tabulate(4)(i => samples.map(_(i)).sum/16)
        if value(3)==0 then Color(0,0,0,0) else Color(encode(value(0)/value(3)),0,encode(value(2)/value(3)),value(3))
      for y <- 0 until (50*scale).toInt by 3;x <- 0 until (100*scale).toInt by 3 do near(image.pixel(x,y),oracle(x,y))
      assert(intercept[IllegalStateException](gpu.render(p,thorvg=true)).getMessage.contains("status=14"))
    }
    finally gpu.close()
  }

  test("distinct drawings reuse masks and images across paints, overlays and snapshot replacement at four scales") {
    val shaped=run;val image=picture(Color(1,0,0));val replaced=picture(Color(0,0,1))
    val gradient=Paint.LinearGradient(Point(0,0),Point(100,0),Vector(ColorStop(0,Color(1,0,0)),ColorStop(1,Color(0,1,0))))
    def drawing(source: ImageSnapshot,paint: Paint,overlay: Boolean)=Drawing.record { c =>
      c.glyphs(shaped,Point(8.25,32.5),paint);c.image(source.full,Rect(100,10,20,20),Sampling.Nearest)
      if overlay then c.fill(Path.rectangle(Rect(10,60,20,10)),red)
    }
    val gpu=OffscreenGpu.open()
    val reports=Vector.newBuilder[String]
    try scales.foreach { scale =>
      val cold=gpu.compile(prepared(gpu,drawing(image,red,false),scale))
      val first=gpu.retain(cold)
      val rasterized=gpu.pixelCacheStats.rasterizations;val uploaded=gpu.assetStats.uploadedBytes
      val warm=gpu.compile(prepared(gpu,drawing(image,gradient,true),scale))
      assertEquals(gpu.pixelCacheStats.rasterizations,rasterized)
      val second=gpu.retain(warm)
      try
        assertEquals(gpu.assetStats.uploadedBytes,uploaded)
        val frame=second.render();save("shared-text",frame)
        val expected=frame.pixelsCopy.toVector
        val inline=gpu.render(PixelProgram.compile(prepared(gpu,drawing(image,gradient,true),scale)))
        assertEquals(inline.pixelsCopy.toVector,expected)
        val commandUploads=gpu.resourceStats.uploadedBytes
        for _ <- 0 until 5 do assertEquals(second.render().pixelsCopy.toVector,expected)
        assertEquals(gpu.resourceStats.uploadedBytes,commandUploads)
        assertEquals(gpu.assetStats.uploadedBytes,uploaded)
        first.close()
        assertEquals(second.render().pixelsCopy.toVector,expected)
        val replacement=gpu.retain(gpu.compile(prepared(gpu,drawing(replaced,gradient,true),scale)))
        try
          assert(gpu.assetStats.uploadedBytes>=uploaded)
          assertEquals(gpu.pixelCacheStats.rasterizations,rasterized)
          assertEquals(second.render().pixel((110*scale).toInt,(20*scale).toInt),Color(1,0,0))
          assertEquals(replacement.render().pixel((110*scale).toInt,(20*scale).toInt),Color(0,0,1))
        finally replacement.close()
        reports+=s"scale=$scale cpu=${gpu.pixelCacheStats} gpu=${gpu.assetStats} command-bytes=${warm.uploadBytes}"
      finally {first.close();second.close()}
      assertEquals(gpu.assetStats.pinned,0)
    }
    finally gpu.close()
    Files.createDirectories(output);Files.writeString(output.resolve("reuse.txt"),reports.result().mkString("\n")+"\n")
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("bounded GPU arena evicts only idle entries and rolls back leases after failed multi-asset admission") {
    val gpu=OffscreenGpu.open(SharedResourceLimits(gpuVec4=32,gpuEntries=2,cpuEntries=8))
    val a=picture(Color(1,0,0));val b=picture(Color(0,1,0));val c=picture(Color(0,0,1));val d=picture(Color(1,1,0))
    def drawing(image: ImageSnapshot)=Drawing.record(_.image(image.full,Rect(0,0,10,10),Sampling.Nearest))
    def retain(image: ImageSnapshot)=gpu.retain(gpu.compile(prepared(gpu,drawing(image),width=16,height=16)))
    val pinned=retain(a)
    try
      val idle=retain(b);idle.close()
      val later=retain(c);later.close()
      assert(gpu.assetStats.evictions>=1)
      assertEquals(pinned.render().pixel(3,3),Color(1,0,0))
      val reloaded=retain(b)
      try
        assert(gpu.assetStats.uploadedBytes>=4*256)
        val joined=Drawing.record { canvas => canvas.image(a.full,Rect(0,0,8,8));canvas.image(d.full,Rect(8,0,8,8)) }
        val failure=intercept[IllegalStateException](gpu.retain(gpu.compile(prepared(gpu,joined,width=16,height=16))))
        assert(failure.getMessage.contains("status=6"))
        assertEquals(gpu.assetStats.pinned,2)
        assertEquals(pinned.render().pixel(3,3),Color(1,0,0))
        assertEquals(reloaded.render().pixel(3,3),Color(0,1,0))
      finally reloaded.close()
      assertEquals(gpu.assetStats.capacityBytes,512L)
      assert(gpu.assetStats.usedBytes<=512L)
    finally pinned.close()
    assertEquals(gpu.assetStats.pinned,0)
    gpu.close();assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("CPU decoded cache bounds, explicit disposal, thread confinement and real generation replacement hold") {
    val gpu=OffscreenGpu.open(SharedResourceLimits(cpuBytes=256,cpuEntries=1))
    val a=picture(Color(1,0,0));val b=picture(Color(0,1,0))
    def drawing(image: ImageSnapshot)=Drawing.record(_.image(image.full,Rect(0,0,10,10)))
    val obsolete=gpu.compile(prepared(gpu,drawing(a)))
    gpu.compile(prepared(gpu,drawing(b)));gpu.compile(prepared(gpu,drawing(a)))
    assertEquals(gpu.pixelCacheStats.entries,1);assertEquals(gpu.pixelCacheStats.bytes,256L)
    assertEquals(gpu.pixelCacheStats.decodedImages,3L)
    val retained=gpu.retain(obsolete)
    val failure=new AtomicReference[Throwable]()
    val worker=new Thread(() => try {gpu.compile(obsolete.drawing);()} catch case e: Throwable => failure.set(e))
    worker.start();worker.join();assert(failure.get().isInstanceOf[IllegalArgumentException])
    gpu.close();retained.close();assert(retained.program==null)
    intercept[IllegalArgumentException](retained.render())
    assertEquals(OffscreenGpu.liveOwners,0)
    val fresh=OffscreenGpu.open()
    try
      intercept[IllegalArgumentException](fresh.retain(obsolete))
      val valid=fresh.retain(fresh.compile(prepared(fresh,drawing(a))))
      try
        assertEquals(valid.render().pixel(3,3),Color(1,0,0))
        assertEquals(fresh.assetStats.uploadedBytes,256L)
      finally valid.close()
    finally fresh.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("glyph reuse keys preserve exact font snapshot and size across independently resolved faces") {
    val original=run
    def resolve(data: FontData,size: Double): ShapedRun =
      val face=NativeFont.open(data,size)
      try face.shape("office",original.options) finally face.close()
    val same=resolve(original.font.data,original.font.size)
    val larger=resolve(original.font.data,original.font.size+2)
    val distinctSnapshot=resolve(FontData.copy(original.font.data.bytesCopy),original.font.size)
    assert(!(original.font eq same.font))
    def drawing(shaped: ShapedRun)=Drawing.record(_.glyphs(shaped,Point(8.25,32.5),red))
    val gpu=OffscreenGpu.open()
    val first=gpu.retain(gpu.compile(prepared(gpu,drawing(original))))
    try
      val initial=gpu.pixelCacheStats.rasterizations;val uploads=gpu.assetStats.uploadedBytes
      val equivalent=gpu.retain(gpu.compile(prepared(gpu,drawing(same))))
      try
        assertEquals(gpu.pixelCacheStats.rasterizations,initial)
        assertEquals(gpu.assetStats.uploadedBytes,uploads)
        assertEquals(equivalent.render().pixelsCopy.toVector,first.render().pixelsCopy.toVector)
      finally equivalent.close()
      val changed=gpu.retain(gpu.compile(prepared(gpu,drawing(larger))))
      try
        assert(gpu.pixelCacheStats.rasterizations>initial)
        assertNotEquals(changed.render().pixelsCopy.toVector,first.render().pixelsCopy.toVector)
      finally changed.close()
      val afterSize=gpu.pixelCacheStats.rasterizations
      val independent=gpu.retain(gpu.compile(prepared(gpu,drawing(distinctSnapshot))))
      try
        assert(gpu.pixelCacheStats.rasterizations>afterSize)
        assertEquals(independent.render().pixelsCopy.toVector,first.render().pixelsCopy.toVector)
      finally independent.close()
    finally {first.close();gpu.close()}
    assertEquals(OffscreenGpu.liveOwners,0)
  }
