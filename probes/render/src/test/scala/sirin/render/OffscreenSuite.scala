package sirin.render

import sirin.graphics.*
import sirin.graphics.text.NativeFont
import java.nio.file.{Files,Path as FilePath}
import java.util.concurrent.atomic.AtomicReference
import scala.scalanative.unsafe.*

class OffscreenSuite extends munit.FunSuite:
  private val scales=Vector(1.0,1.25,1.5,2.0)
  private val output=FilePath.of("/private/tmp/sirin-render-pixels")
  private val red=Paint.Solid(Color(1,0,0))
  private val green=Paint.Solid(Color(0,1,0))
  private def compile(gpu: OffscreenGpu,d: Drawing,scale: Double): PixelProgram =
    PixelProgram.compile(DrawingPreparation.prepare(d,gpu.target((160*scale).toInt,(120*scale).toInt,scale)).fold(e => fail(e.toString),identity))
  private def at(image: ImageSnapshot,x: Double,y: Double,scale: Double): Color = image.pixel((x*scale).toInt,(y*scale).toInt)
  private def near(actual: Color,expected: Color): Unit =
    Vector(actual.red-expected.red,actual.green-expected.green,actual.blue-expected.blue,actual.alpha-expected.alpha)
      .foreach(v => assert(math.abs(v)<=1.0/255,s"actual=$actual expected=$expected"))
  private def save(name: String,image: ImageSnapshot): Unit =
    Files.createDirectories(output)
    Files.write(output.resolve(s"$name-${image.width}x${image.height}.rgba"),image.pixelsCopy)
  private def encode(x: Double): Double = if x<=0.0031308 then 12.92*x else 1.055*math.pow(x,1/2.4)-0.055
  private def specimen: Drawing =
    val hole=Path(Path.rectangle(Rect(40,4,28,28)).segments++Path.rectangle(Rect(48,12,12,12)).segments)
    val edge=ImageSnapshot.copyRgba8(2,1,Array(255,0,0,255,0,255,0,0).map(_.toByte))
    Drawing.record { c =>
      c.fill(Path.rectangle(Rect(4,4,24,20)),Paint.Solid(Color(0,0,1)))
      c.layer(0.5) { g => g.fill(Path.rectangle(Rect(4,4,16,20)),red);g.fill(Path.rectangle(Rect(12,4,16,20)),red) }
      c.fill(hole,green,FillRule.EvenOdd)
      c.clipped(Path.rectangle(Rect(80,4,32,24))) { a =>
        a.transformed(Affine.translation(80,4)) { b =>
          b.clipped(Path.rectangle(Rect(4,4,16,16)))(_.fill(Path.rectangle(Rect(0,0,40,40)),green))
        }
      }
      c.fill(Path.build { p => p.moveTo(10,40);p.lineTo(30,40);p.lineTo(10,60);p.close() },red)
      c.fill(Path.roundedRectangle(Rect(40,40,28,20),8),green)
      c.image(edge.full,Rect(80,40,20,12),Sampling.Nearest)
      c.image(ImageRegion(edge,Rect(0,0,1,1)),Rect(110,40,12,12),Sampling.Nearest)
      c.image(edge.full,Rect(80,60,20,8),Sampling.Linear)
      c.fill(Path.rectangle(Rect(4.25,80.25,16,12)),red)
    }

  test("actual Metal device and shared ThorVG texture draw an opaque rectangle") {
    assertEquals(OffscreenGpu.liveOwners,0)
    val gpu=OffscreenGpu.open()
    try
      println(s"adapter=${gpu.generation.adapter}")
      val drawing=Drawing.record(_.fill(Path.rectangle(Rect(4,4,16,16)),Paint.Solid(Color(1,0,0))))
      val prepared=DrawingPreparation.prepare(drawing,gpu.target(32,32,1)).toOption.get
      val program=PixelProgram.compile(prepared)
      val direct=gpu.render(program)
      assertEquals(direct.pixel(10,10),Color(1,0,0,1))
      assertEquals(direct.pixel(0,0),Color(0,0,0,0))
      val delegated=gpu.render(program,thorvg=true)
      assertEquals(delegated.pixel(10,10),Color(1,0,0,1))
      assertEquals(delegated.pixel(0,0),Color(0,0,0,0))
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("four-scale pixels obey holes, nested clips, isolated linear opacity, sampling and coverage") {
    val gpu=OffscreenGpu.open()
    try scales.foreach { scale =>
      val p=compile(gpu,specimen,scale)
      val image=gpu.render(p)
      save("direct",image)
      near(at(image,8,8,scale),Color(encode(0.5),0,encode(0.5)))
      near(at(image,16,8,scale),Color(encode(0.5),0,encode(0.5)))
      assertEquals(at(image,52,16,scale),Color(0,0,0,0))
      assertEquals(at(image,44,8,scale),Color(0,1,0))
      assertEquals(at(image,90,14,scale),Color(0,1,0))
      assertEquals(at(image,82,6,scale),Color(0,0,0,0))
      assertEquals(at(image,12,42,scale),Color(1,0,0))
      assertEquals(at(image,30,58,scale),Color(0,0,0,0))
      assertEquals(at(image,54,50,scale),Color(0,1,0))
      assertEquals(at(image,40,40,scale),Color(0,0,0,0))
      assertEquals(at(image,84,44,scale),Color(1,0,0))
      assertEquals(at(image,96,44,scale),Color(0,0,0,0))
      assertEquals(at(image,114,44,scale),Color(1,0,0))
      val px=(90*scale).toInt
      val alpha=(0 until 4).map(i => {
        val local=(px+(i+0.5)/4)/scale
        math.max(0,math.min(1,1.5-(local-80)/10))
      }).sum/4
      near(at(image,90,63,scale),Color(1,0,0,alpha))
      val x=(4.25*scale).toInt;val y=(80.25*scale).toInt
      val covered=(for a <- 0 until 4;b <- 0 until 4 yield
        if x+(a+0.5)/4>=4.25*scale && y+(b+0.5)/4>=80.25*scale then 1 else 0).sum
      near(image.pixel(x,y),if covered==0 then Color(0,0,0,0) else Color(1,0,0,covered/16.0))
    }
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("ThorVG shares clips and texture ownership; color differences remain recorded findings") {
    // The comparison admits full-source images. Cropped-source failure is
    // separately asserted, never converted to a missing or successful draw.
    val drawing=Drawing.record { c =>
      c.fill(Path.rectangle(Rect(4,4,24,20)),Paint.Solid(Color(0,0,1)))
      c.layer(0.5) { a => a.fill(Path.rectangle(Rect(4,4,16,20)),red);a.fill(Path.rectangle(Rect(12,4,16,20)),red) }
      c.clipped(Path.rectangle(Rect(40,4,20,20)))(_.fill(Path.rectangle(Rect(36,0,30,30)),green))
    }
    val gpu=OffscreenGpu.open()
    try scales.foreach { scale =>
      val p=compile(gpu,drawing,scale)
      val actual=gpu.render(p,thorvg=true)
      save("thorvg-composition",actual)
      assertEquals(at(actual,48,12,scale),Color(0,1,0))
      assertEquals(at(actual,38,12,scale),Color(0,0,0,0))
      val expected=Color(encode(0.5),0,encode(0.5))
      val got=at(actual,16,8,scale)
      assert(math.abs(got.red-expected.red)>1.0/255,"pinned comparison's known color failure must remain visible")
      println(s"thorvg-composition scale=$scale expected-linear=$expected actual=$got")
      Files.writeString(output.resolve(s"thorvg-color-$scale.txt"),s"linear oracle=$expected\nactual=$got\n")
    }
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("numeric and expanded-work admission refuses unsafe GPU programs before submission") {
    val gpu=OffscreenGpu.open()
    try
      val big=1e13
      val large=Drawing.record(_.fill(Path.rectangle(Rect(big,big,10,10)),red))
      val unrebased=DrawingPreparation.prepare(large,gpu.target(32,32,1)).toOption.get
      assert(intercept[IllegalArgumentException](PixelProgram.compile(unrebased)).getMessage.contains("precision"))
      val rebased=Drawing.record(_.transformed(Affine.translation(-big,-big))(_.draw(large)))
      assertEquals(gpu.render(compile(gpu,rebased,1)).pixel(2,2),Color(1,0,0))
      val image=ImageSnapshot.copyRgba8(32,32,Array.fill[Byte](32*32*4)(-1))
      val imageDrawing=Drawing.record(_.image(image.full,Rect(0,0,20,20)))
      val prepared=DrawingPreparation.prepare(imageDrawing,gpu.target(32,32,1)).toOption.get
      assert(intercept[IllegalArgumentException](PixelProgram.compile(prepared,maxVec4=128)).getMessage.contains("image pixel budget"))
      val extreme=Paint.LinearGradient(Point(0,0),Point(math.pow(2,-126),0),
        Vector(ColorStop(0,Color(1,0,0)),ColorStop(1,Color(0,0,1))),extend=Extend.Repeat)
      val unsafeGradient=DrawingPreparation.prepare(Drawing.record(_.fill(Path.rectangle(Rect(0,0,8,8)),extreme)),gpu.target(32,32,1)).toOption.get
      assert(intercept[IllegalArgumentException](PixelProgram.compile(unsafeGradient)).getMessage.contains("gradient parameter range"))
      val costly=Drawing.record(c => (0 until 100).foreach(_ => c.fill(Path.rectangle(Rect(0,0,10,10)),red)))
      val huge=DrawingPreparation.prepare(costly,gpu.target(1024,1024,1)).toOption.get
      assert(intercept[IllegalArgumentException](PixelProgram.compile(huge)).getMessage.contains("sample work budget"))
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("native initialization failure releases its callback owner") {
    Zone.acquire { implicit zone =>
      val out=alloc[Ptr[Byte]]();val error=alloc[Byte](512)
      val status=GpuShim.sgpu_open(toCString("invalid WGSL"),out,error)
      assert(status!=0)
      assert((!out)==null)
      assert(fromCString(error).contains("Validation"))
    }
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("closed static faces supply exact positioned Latin and Arabic masks through both engines") {
    def run(file: String,text: String,options: ShapingOptions): ShapedRun =
      val path=FilePath.of("graphics/src/test/resources/fonts").resolve(file)
      val face=NativeFont.open(FontData.copy(Files.readAllBytes(path)),24)
      try face.shape(text,options) finally face.close()
    val latin=run("NotoSans-Regular.ttf","office",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en"))
    val arabic=run("NotoNaskhArabic-Regular.ttf","سلام",ShapingOptions(Script.Arabic,TextDirection.RightToLeft,"ar"))
    assertEquals(latin.glyphs.size,4)
    assert(!arabic.hasMissingGlyphs)
    val drawing=Drawing.record { c =>
      c.glyphs(latin,Point(8.25,32.5),red)
      c.transformed(Affine.rotation(0.15).andThen(Affine.translation(8,45)))(_.glyphs(arabic,Point(0.25,24.5),green))
    }
    val gpu=OffscreenGpu.open()
    try scales.foreach { scale =>
      val p=compile(gpu,drawing,scale)
      assert(p.maskBytes>0)
      assert(p.drawing.resources.fonts.contains(latin.font.data))
      for delegated <- Vector(false,true) do
        val image=gpu.render(p,thorvg=delegated)
        save(if delegated then "thorvg-text" else "direct-text",image)
        val pixels=image.pixelsCopy
        assert(pixels.indices.exists(i => i%4==3 && (pixels(i)&255)>128),"real glyph ink must reach output")
        assertEquals(image.pixel(0,0),Color(0,0,0,0))
        val latinInk=(for y <- 0 until (40*scale).toInt;x <- 0 until (90*scale).toInt yield image.pixel(x,y).alpha).sum
        val arabicInk=(for y <- (45*scale).toInt until (90*scale).toInt;x <- 0 until (100*scale).toInt yield image.pixel(x,y).alpha).sum
        assert(latinInk>60*scale*scale && arabicInk>30*scale*scale)
        if !delegated then
          // Independent scalar mask oracle checks fractional placement against
          // FreeType coverage, not just the presence of arbitrary visible ink.
          val face=NativeFont.open(latin.font.data,latin.font.size)
          val masks=try latin.glyphs.map(g => (g,face.rasterize(g.id,scale))) finally face.close()
          def expected(px: Int,py: Int): Double =
            (for sy <- 0 until 4;sx <- 0 until 4 yield
              val x=(px+(sx+0.5)/4)/scale;val y=(py+(sy+0.5)/4)/scale
              masks.foldLeft(0.0) { case (dst,(g,m)) =>
                val left=8.25+g.position.x+m.left/m.deviceScale
                val top=32.5+g.position.y-m.top/m.deviceScale
                val u=(x-left)*m.deviceScale;val v=(y-top)*m.deviceScale
                var coverage=0.0
                if u>=0 && u<m.width && v>=0 && v<m.height then
                  val ix=math.floor(u-0.5).toInt;val iy=math.floor(v-0.5).toInt
                  val fx=u-0.5-ix;val fy=v-0.5-iy
                  val bytes=m.coverageCopy
                  def value(a: Int,b: Int): Double =
                    (bytes(math.max(0,math.min(m.height-1,b))*m.width+math.max(0,math.min(m.width-1,a)))&255)/255.0
                  coverage=(value(ix,iy)*(1-fx)+value(ix+1,iy)*fx)*(1-fy)+
                    (value(ix,iy+1)*(1-fx)+value(ix+1,iy+1)*fx)*fy
                coverage+dst*(1-coverage)
              }
            ).sum/16
          for y <- 0 until (40*scale).toInt by 3;x <- 0 until (85*scale).toInt by 3 do
            assertEqualsDouble(image.pixel(x,y).alpha,expected(x,y),1.0/255,s"mask sample at $x,$y scale=$scale")
    }
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("program budgets, device generations, thread ownership, resize and disposal are enforced") {
    val gpu=OffscreenGpu.open()
    val drawing=Drawing.record(_.fill(Path.rectangle(Rect(0,0,10,10)),red))
    try
      val p=compile(gpu,drawing,1)
      intercept[IllegalArgumentException](PixelProgram.compile(p.drawing,maxVec4=1))
      val twice=Drawing.record(c => {c.draw(drawing);c.draw(drawing)})
      intercept[IllegalArgumentException](PixelProgram.compile(compile(gpu,twice,1).drawing,maxOps=1))
      val image=ImageSnapshot.copyRgba8(2,1,Array.fill[Byte](8)(-1))
      val cropped=compile(gpu,Drawing.record(_.image(ImageRegion(image,Rect(0,0,1,1)),Rect(0,0,4,4))),1)
      assert(intercept[IllegalStateException](gpu.render(cropped,thorvg=true)).getMessage.contains("status=13"))
      val foreign=DrawingPreparation.prepare(drawing,p.drawing.target.copy(device=new DeviceGeneration("test","test"))).toOption.get
      intercept[IllegalArgumentException](gpu.render(PixelProgram.compile(foreign)))
      val result=new AtomicReference[Throwable]()
      val thread=new Thread(() => try {gpu.render(p);()} catch case e: Throwable => result.set(e))
      thread.start();thread.join()
      assert(result.get().isInstanceOf[IllegalArgumentException])
      for i <- 0 until 20 do
        val resized=compile(gpu,drawing,scales(i%4))
        assertEquals(gpu.render(resized,thorvg=i%2==0).pixel(2,2),Color(1,0,0))
    finally gpu.close()
    gpu.close()
    intercept[IllegalArgumentException](gpu.target(32,32,1))
    assertEquals(OffscreenGpu.liveOwners,0)
    for _ <- 0 until 5 do
      val owner=OffscreenGpu.open()
      try assertEquals(owner.render(compile(owner,drawing,1)).pixel(2,2),Color(1,0,0)) finally owner.close()
      assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("bounded timing reports preparation, uploads and synchronized readback separately") {
    val gpu=OffscreenGpu.open()
    try
      val d=specimen
      val preparation=Vector.fill(10) {
        val start=System.nanoTime();val program=compile(gpu,d,1);(System.nanoTime()-start,program)
      }
      val p=preparation.last._2
      (0 until 3).foreach(_ => gpu.render(p))
      val timings=Vector.fill(10) {val start=System.nanoTime();gpu.render(p);System.nanoTime()-start}.sorted
      val report=s"adapter=${gpu.generation.adapter}\nprepare-ns=${preparation.map(_._1).mkString(",")}\n"+
        s"synchronized-render-readback-ns=${timings.mkString(",")}\nupload-bytes-per-render=${p.uploadBytes}\n"+
        s"padded-readback-bytes-per-render=${p.readbackBytes}\nmask-bytes=${p.maskBytes}\n"+
        "not GPU timestamps, input-to-display, allocations or production throughput\n"
      Files.writeString(output.resolve("timings.txt"),report)
      println(report)
      val shared=Drawing.record { c => c.fill(Path.rectangle(Rect(4,4,24,20)),red);c.clipped(Path.rectangle(Rect(40,4,20,20)))(_.fill(Path.rectangle(Rect(36,0,30,30)),green)) }
      val program=compile(gpu,shared,1)
      val comparison=Vector(false,true).map { delegated =>
        (0 until 3).foreach(_ => gpu.render(program,thorvg=delegated))
        val times=Vector.fill(10) { val start=System.nanoTime();gpu.render(program,thorvg=delegated);System.nanoTime()-start }.sorted
        s"thorvg=$delegated synchronized-render-readback-ns=${times.mkString(",")}"
      }.mkString("\n")
      Files.writeString(output.resolve("seam-timings.txt"),comparison+"\nidentical opaque two-fill path/clip seam; not production throughput\n")
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }
