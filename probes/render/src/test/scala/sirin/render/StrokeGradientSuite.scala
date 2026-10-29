package sirin.render

import sirin.graphics.*
import sirin.graphics.text.NativeFont
import java.nio.file.{Files,Path as FilePath}
import java.util.concurrent.atomic.AtomicReference

class StrokeGradientSuite extends munit.FunSuite:
  private val scales=Vector(1.0,1.25,1.5,2.0)
  private val output=FilePath.of("/private/tmp/sirin-render-next-pixels")
  private val red=Paint.Solid(Color(1,0,0,0.5))
  private def program(gpu: OffscreenGpu,d: Drawing,s: Double): PixelProgram =
    PixelProgram.compile(DrawingPreparation.prepare(d,gpu.target((160*s).toInt,(120*s).toInt,s)).fold(e => fail(e.toString),identity))
  private def encode(x: Double): Double = if x<=0.0031308 then 12.92*x else 1.055*math.pow(x,1/2.4)-0.055
  private def near(a: Color,b: Color): Unit =
    Vector(a.red-b.red,a.green-b.green,a.blue-b.blue,a.alpha-b.alpha).foreach(x => assert(math.abs(x)<=1.0/255,s"actual=$a expected=$b"))
  private def save(name: String,image: ImageSnapshot): Unit =
    Files.createDirectories(output)
    Files.write(output.resolve(s"$name-${image.width}x${image.height}.rgba"),image.pixelsCopy)
  private def line(x: Double,y: Double,end: Double)=Path.build { p => p.moveTo(x,y);p.lineTo(end,y) }

  test("four-scale caps and dashes match independent analytic sample coverage without alpha overdraw") {
    val gpu=OffscreenGpu.open()
    try scales.foreach { s =>
      val d=Drawing.record { c =>
        c.stroke(line(20,20,60),red,StrokeStyle(8,LineCap.Butt))
        c.stroke(line(20,45,60),red,StrokeStyle(8,LineCap.Round))
        c.stroke(line(20,70,60),red,StrokeStyle(8,LineCap.Square))
        c.stroke(line(90,20,150),red,StrokeStyle(4,LineCap.Butt,dashes=Vector(6,4),dashOffset=2))
        c.stroke(Path.rectangle(Rect(90,50,30,30)),red,StrokeStyle(8,join=LineJoin.Miter))
      }
      val image=gpu.render(program(gpu,d,s));save("strokes",image)
      def coverage(px: Int,py: Int,cap: LineCap,y: Double): Double =
        (for sy <- 0 until 4;sx <- 0 until 4 yield
          val x=(px+(sx+0.5)/4)/s;val dy=(py+(sy+0.5)/4)/s-y
          val in=cap match
            case LineCap.Butt => x>=20 && x<60 && math.abs(dy)<4
            case LineCap.Square => x>=16 && x<64 && math.abs(dy)<4
            case LineCap.Round =>
              val dx=math.max(20-x,math.max(0,x-60))
              dx*dx+dy*dy<16
          if in then 0.5 else 0.0).sum/16
      for (cap,y) <- Vector((LineCap.Butt,20.0),(LineCap.Round,45.0),(LineCap.Square,70.0));
          py <- ((y-5)*s).toInt to ((y+5)*s).toInt;
          px <- (15*s).toInt to (65*s).toInt do
        val a=coverage(px,py,cap,y)
        // Round polygon error is checked geometrically in the pure suite;
        // exact interior/exterior oracle pixels avoid its admitted edge band.
        val center=Point((px+0.5)/s,(py+0.5)/s)
        val dist=math.hypot(math.max(20-center.x,math.max(0,center.x-60)),center.y-y)
        if cap!=LineCap.Round || math.abs(dist-4)>0.8/s then
          near(image.pixel(px,py),if a==0 then Color(0,0,0,0) else Color(1,0,0,a))
      for x <- 92 until 149 do
        val px=(x*s).toInt;val py=(20*s).toInt
        val alpha=(0 until 4).map { i =>
          val position=(px+(i+0.5)/4)/s
          if (position-90+2)%10<6 then 0.5 else 0.0
        }.sum/4
        near(image.pixel(px,py),if alpha==0 then Color(0,0,0,0) else Color(1,0,0,alpha))
      near(image.pixel((90*s).toInt,(50*s).toInt),Color(1,0,0,0.5))
      assertEquals(image.pixel((105*s).toInt,(65*s).toInt),Color(0,0,0,0))
    }
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("four-scale transformed gradients interpolate linear premultiplied colors with hard stops and extensions") {
    val stops=Vector(ColorStop(0.2,Color(1,0,0)),ColorStop(0.5,Color(1,0,0)),
      ColorStop(0.5,Color(0,0,1,0.5)),ColorStop(0.8,Color(0,1,0,0)))
    val transform=Affine(1,0.2,0.4,1,20,8)
    val paintTransform=Affine.rotation(0.2).andThen(Affine.translation(3,2))
    val inverse=paintTransform.andThen(transform).inverse.get
    val gpu=OffscreenGpu.open()
    try scales.foreach { s =>
      for extend <- Extend.values do
        val gradient=Paint.LinearGradient(Point(0,0),Point(20,0),stops,paintTransform,extend)
        val d=Drawing.record(_.transformed(transform)(_.fill(Path.rectangle(Rect(-5,-3,85,60)),gradient)))
        val p=program(gpu,d,s)
        val image=gpu.render(p);save(s"gradient-$extend",image)
        def oracle(px: Int,py: Int): Color =
          val samples=for sy <- 0 until 4;sx <- 0 until 4 yield
            val q=inverse(Point((px+(sx+0.5)/4)/s,(py+(sy+0.5)/4)/s))
            val raw=q.x/20
            val t=extend match
              case Extend.Clamp => math.max(0,math.min(1,raw))
              case Extend.Repeat => raw-math.floor(raw)
              case Extend.Reflect => val v=raw-2*math.floor(raw/2);if v>1 then 2-v else v
            if t<0.2 then Vector(1.0,0.0,0.0,1.0)
            else if t<0.5 then Vector(1.0,0.0,0.0,1.0)
            else if t<0.8 then
              val f=(t-0.5)/0.3
              Vector(0.0,0.0,0.5*(1-f),0.5*(1-f))
            else Vector(0.0,0.0,0.0,0.0)
          val result=Vector.tabulate(4)(i => samples.map(_(i)).sum/16)
          if result(3)==0 then Color(0,0,0,0) else
            Color(encode(result(0)/result(3)),encode(result(1)/result(3)),encode(result(2)/result(3)),result(3))
        for y <- 24 until 45 by 3;x <- 30 until 85 by 2 do
          near(image.pixel((x*s).toInt,(y*s).toInt),oracle((x*s).toInt,(y*s).toInt))
        assert(intercept[IllegalStateException](gpu.render(p,thorvg=true)).getMessage.contains("status=14"))
    }
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }

  test("gradient stroke, nested clipping, isolation and instance transforms share the same paint") {
    val gradient=Paint.LinearGradient(Point(20,0),Point(60,0),Vector(ColorStop(0,Color(1,0,0)),ColorStop(1,Color(0,0,1))))
    val gpu=OffscreenGpu.open()
    try scales.foreach { s =>
      val d=Drawing.record { c =>
        c.clipped(Path.rectangle(Rect(25,10,30,30))) { clipped =>
          clipped.layer(0.5)(_.stroke(line(20,20,60),gradient,StrokeStyle(10)))
        }
        c.instances(Path.rectangle(Rect(20,50,40,10)),InstanceBatch(Vector(Affine.identity,Affine.translation(60,0)),InstanceValues.Constant(gradient)))
      }
      val image=gpu.render(program(gpu,d,s));save("gradient-stroke",image)
      assertEquals(image.pixel((22*s).toInt,(20*s).toInt),Color(0,0,0,0))
      val px=(40*s).toInt
      val t=((px+0.5)/s-20)/40
      near(image.pixel(px,(20*s).toInt),Color(encode(1-t),0,encode(t),0.5))
      near(image.pixel(px,(55*s).toInt),Color(encode(1-t),0,encode(t)))
      near(image.pixel((100*s).toInt,(55*s).toInt),Color(encode(1-t),0,encode(t)))
    }
    finally gpu.close()
  }

  test("retained replay uploads once, reproduces pixels, bounds inventory and retires with its owner") {
    val gpu=OffscreenGpu.open()
    var orphan: RetainedProgram=null
    var obsolete: PixelProgram=null
    try
      val image=ImageSnapshot.copyRgba8(2,1,Array[Byte](-1,0,0,-1,0,0,-1,-1))
      val d=Drawing.record { c => c.image(image.full,Rect(2,2,20,10));c.stroke(line(20,30,60),red,StrokeStyle(4,LineCap.Round)) }
      val p=program(gpu,d,1)
      obsolete=p
      val reference=gpu.render(p).pixelsCopy.toVector
      val before=gpu.resourceStats
      val resource=gpu.retain(p)
      val after=gpu.resourceStats
      assertEquals(after.programs,1)
      assertEquals(after.uploadedBytes-before.uploadedBytes,p.uploadBytes)
      assertEquals(after.retainedBytes,p.uploadBytes+160L*120*4)
      for _ <- 0 until 20 do assertEquals(resource.render().pixelsCopy.toVector,reference)
      assertEquals(gpu.resourceStats,after)
      val failure=new AtomicReference[Throwable]()
      val thread=new Thread(() => try {resource.render();()} catch case e: Throwable => failure.set(e))
      thread.start();thread.join()
      assert(failure.get().isInstanceOf[IllegalArgumentException])
      resource.close();resource.close()
      assertEquals(gpu.resourceStats.programs,0)
      assertEquals(gpu.resourceStats.retainedBytes,0L)
      intercept[IllegalArgumentException](resource.render())
      for i <- 0 until 20 do
        val replacement=program(gpu,d,scales(i%4));val r=gpu.retain(replacement)
        try assertEquals(r.render().pixel((3*scales(i%4)).toInt,(3*scales(i%4)).toInt),Color(1,0,0)) finally r.close()
      val tiny=PixelProgram.compile(DrawingPreparation.prepare(Drawing.empty,gpu.target(1,1,1)).toOption.get)
      val resources=Vector.fill(64)(gpu.retain(tiny))
      assert(intercept[IllegalStateException](gpu.retain(tiny)).getMessage.contains("status=6"))
      assertEquals(gpu.resourceStats.programs,64)
      resources.foreach(_.close())
      val large=PixelProgram.compile(DrawingPreparation.prepare(Drawing.empty,gpu.target(1024,1024,1)).toOption.get)
      val payloads=Vector.fill(15)(gpu.retain(large))
      assert(intercept[IllegalStateException](gpu.retain(large)).getMessage.contains("status=6"))
      assertEquals(gpu.resourceStats.programs,15)
      payloads.foreach(_.close())
      orphan=gpu.retain(p)
      Files.createDirectories(output)
      Files.writeString(output.resolve("retention.txt"),s"initial=$before\nafter-upload=$after\n20 replays: storage upload unchanged; byte-for-byte pixels equal\nresize/reprepare cycles=20; count budget=64 enforced\n")
    finally gpu.close()
    orphan.close()
    assert(orphan.program==null)
    intercept[IllegalArgumentException](orphan.render())
    assertEquals(OffscreenGpu.liveOwners,0)
    val replacement=OffscreenGpu.open()
    try
      intercept[IllegalArgumentException](replacement.retain(obsolete))
    finally replacement.close()
  }
  test("retained exact glyph masks and snapshot replacement remain immutable at all scales") {
    val face=NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),24)
    val run=try face.shape("office",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en")) finally face.close()
    val oldImage=ImageSnapshot.copyRgba8(1,1,Array[Byte](-1,0,0,-1))
    val newImage=ImageSnapshot.copyRgba8(1,1,Array[Byte](0,0,-1,-1))
    def drawing(image: ImageSnapshot)=Drawing.record { c =>
      c.glyphs(run,Point(8.25,32.5),Paint.Solid(Color(0,1,0)))
      c.image(image.full,Rect(100,10,20,20),Sampling.Nearest)
    }
    val gpu=OffscreenGpu.open()
    try scales.foreach { scale =>
      val original=program(gpu,drawing(oldImage),scale)
      assert(original.maskBytes>0)
      val old=gpu.retain(original)
      val fresh=gpu.retain(program(gpu,drawing(newImage),scale))
      try
        val uploaded=gpu.resourceStats.uploadedBytes
        val before=old.render()
        val changed=fresh.render()
        assertEquals(before.pixel((110*scale).toInt,(20*scale).toInt),Color(1,0,0))
        assertEquals(changed.pixel((110*scale).toInt,(20*scale).toInt),Color(0,0,1))
        for _ <- 0 until 3 do assertEquals(old.render().pixelsCopy.toVector,before.pixelsCopy.toVector)
        assertEquals(gpu.resourceStats.uploadedBytes,uploaded)
        val bytes=before.pixelsCopy
        assert(bytes.indices.exists(i => i%4==1 && (bytes(i)&255)>0))
        save("retained-glyphs",before)
      finally {old.close();fresh.close()}
      assertEquals(gpu.resourceStats.retainedBytes,0L)
    }
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }
