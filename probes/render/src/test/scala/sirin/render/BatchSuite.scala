package sirin.render

import sirin.graphics.*
import sirin.graphics.text.NativeFont
import java.nio.file.{Files,Path as FilePath}
import java.util.concurrent.atomic.AtomicReference

class BatchSuite extends munit.FunSuite:
  override val munitTimeout=scala.concurrent.duration.Duration(5,"min")
  private val scales=Vector(1.0,1.25,1.5,2.0)
  private val output=FilePath.of("/private/tmp/sirin-batch-pixels")
  private val red=Paint.Solid(Color(1,0,0))
  private val blue=Paint.Solid(Color(0,0,1,0.5))
  private val gradient=Paint.LinearGradient(Point(0,0),Point(64,32),Vector(ColorStop(0,Color(1,0,0,0.8)),ColorStop(0.4,Color(0,1,0)),ColorStop(1,Color(0,0,1))),extend=Extend.Reflect)
  private def prepared(gpu: BatchGpu,d: Drawing,s: Double=1,w: Int=64,h: Int=48): PreparedDrawing =
    DrawingPreparation.prepare(d,gpu.target((w*s).toInt,(h*s).toInt,s)).fold(e => fail(e.toString),identity)
  private def run(name: String="NotoSans-Regular.ttf",text: String="office",script: Script=Script.Latin,direction: TextDirection=TextDirection.LeftToRight,size: Double=12): ShapedRun =
    val data=FontData.copy(Files.readAllBytes(FilePath.of(s"graphics/src/test/resources/fonts/$name")))
    val face=NativeFont.open(data,size)
    try face.shape(text,ShapingOptions(script,direction,"und")) finally face.close()
  private def picture(color: Color=Color(1,0,0)): ImageSnapshot =
    ImageSnapshot.copyRgba8(4,4,Array.tabulate[Byte](64)(i => (Vector(color.red,color.green,color.blue,color.alpha)(i%4)*255).round.toByte))
  private def save(name: String,image: ImageSnapshot): Unit =
    Files.createDirectories(output);Files.write(output.resolve(s"$name-${image.width}x${image.height}.rgba"),image.pixelsCopy)
  private def edges(p: FlatPath): Vector[(Point,Point)]=p.contours.filter(_.size>1).flatMap(ps => ps.zip(ps.drop(1):+ps.head))
  private def inside(p: Point,path: FlatPath,rule: FillRule): Boolean =
    val w=edges(path).foldLeft(0) { case (n,(a,b)) =>
      val cross=(b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x)
      if a.y<=p.y && b.y>p.y && cross>0 then n+1
      else if b.y<=p.y && a.y>p.y && cross<0 then n-1 else n
    }
    if rule==FillRule.NonZero then w!=0 else math.abs(w)%2==1
  private def boundaries(prepared: PreparedDrawing): Vector[(Point,Point)] =
    def rect(r: Rect,m: Affine)=edges(FlatPath(Vector(Vector(m(Point(r.x,r.y)),m(Point(r.x+r.width,r.y)),m(Point(r.x+r.width,r.y+r.height)),m(Point(r.x,r.y+r.height))))))
    prepared.commands.flatMap {
      case PreparedCommand.Fill(p,_,_,cs) => edges(p)++cs.flatMap(c => edges(c.path))
      case PreparedCommand.Image(_,dest,_,_,m,cs) => rect(dest,m)++cs.flatMap(c => edges(c.path))
      case PreparedCommand.Glyphs(run,origin,_,m,cs) =>
        // Only the quad edge, not every mask texel, is a geometric tie.
        val a=m.a;val b=m.b;val c=m.c;val d=m.d
        val tr=a*a+b*b+c*c+d*d;val det=a*d-b*c
        val scale=math.sqrt((tr+math.sqrt(math.max(0,tr*tr-4*det*det)))/2)
        val face=NativeFont.open(run.font.data,run.font.size,run.font.faceIndex)
        try run.glyphs.flatMap { g =>
          val mask=face.rasterize(g.id,scale)
          rect(Rect(origin.x+g.position.x+mask.left/mask.deviceScale,origin.y+g.position.y-mask.top/mask.deviceScale,mask.width/mask.deviceScale,mask.height/mask.deviceScale),m)
        }++cs.flatMap(c => edges(c.path)) finally face.close()
      case _ => Vector.empty
    }
  private def compare(actual: ImageSnapshot,reference: ImageSnapshot,p: PreparedDrawing,name: String): Unit =
    save(s"$name-reference",reference)
    val a=actual.pixelsCopy;val b=reference.pixelsCopy;val es=boundaries(p)
    // Mark exact-tie pixels edge by edge. This is the identical distance test
    // over the identical 4x4 grid, restricted by each edge's conservative AABB.
    val ties=Array.fill(actual.width*actual.height)(false)
    es.foreach { (u,v) =>
      val minx=math.max(0,math.floor(math.min(u.x,v.x)-0.0001).toInt)
      val maxx=math.min(actual.width-1,math.floor(math.max(u.x,v.x)+0.0001).toInt)
      val miny=math.max(0,math.floor(math.min(u.y,v.y)-0.0001).toInt)
      val maxy=math.min(actual.height-1,math.floor(math.max(u.y,v.y)+0.0001).toInt)
      val dx=v.x-u.x;val dy=v.y-u.y;val length=dx*dx+dy*dy
      var y=miny
      while y<=maxy do
        var x=minx
        while x<=maxx do
          val index=y*actual.width+x;var sy=0
          while sy<4 && !ties(index) do
            var sx=0
            while sx<4 && !ties(index) do
              val px=x+(sx+0.5)/4;val py=y+(sy+0.5)/4
              val t=if length==0 then 0.0 else math.max(0,math.min(1,((px-u.x)*dx+(py-u.y)*dy)/length))
              ties(index)=math.hypot(px-u.x-dx*t,py-u.y-dy*t)<=0.0001
              sx+=1
            sy+=1
          x+=1
        y+=1
    }
    var checked=0;var excluded=0;var worst=0
    for y <- 0 until actual.height;x <- 0 until actual.width do
      val tie=ties(y*actual.width+x)
      if tie then excluded+=1 else
        for c <- 0 until 4 do
          val i=(y*actual.width+x)*4+c;val delta=math.abs((a(i)&255)-(b(i)&255));worst=math.max(worst,delta)
          assert(delta<=2,s"$name ($x,$y) channel=$c actual=${a(i)&255} reference=${b(i)&255} delta=$delta actualRGBA=${actual.pixel(x,y)} referenceRGBA=${reference.pixel(x,y)}")
        checked+=1
    assert(checked>actual.width*actual.height/2,s"too few qualified pixels: $checked excluded=$excluded")
    println(s"comparison=$name checked=$checked excluded=$excluded worstByteError=$worst")

  test("actual batched Metal triangles render an opaque rectangle and deterministic teardown") {
    assertEquals(BatchGpu.liveOwners,0)
    val gpu=BatchGpu.open()
    try
      println(s"batch-adapter=${gpu.generation.adapter}")
      val p=gpu.compile(prepared(gpu,Drawing.record(_.fill(Path.rectangle(Rect(4,4,16,16)),red))))
      assertEquals(p.drawBatches,1)
      val r=gpu.retain(p)
      try
        val image=r.render();save("smoke",image)
        assertEquals(image.pixel(10,10),Color(1,0,0));assertEquals(image.pixel(0,0),Color(0,0,0,0))
        val before=gpu.stats;r.submit();val after=gpu.stats
        assertEquals(after.meshUploadBytes,before.meshUploadBytes);assertEquals(after.textureUploadBytes,before.textureUploadBytes);assertEquals(after.programUploadBytes,before.programUploadBytes)
      finally r.close()
      assertEquals(gpu.stats.programs,0L);assertEquals(gpu.stats.pinnedEntries,0L)
    finally gpu.close()
    assertEquals(BatchGpu.liveOwners,0)
  }

  test("winding tessellation agrees with independent source winding for holes, crossings and overlapping ribbons") {
    val gen=new DeviceGeneration("test","CPU")
    val target=RenderTarget(gen,64,48,Affine.identity)
    val a=Vector(Point(2,2),Point(44,3),Point(40,40),Point(3,38))
    val b=Vector(Point(12,10),Point(30,13),Point(30,30),Point(12,27))
    val bow=Vector(Point(1,4),Point(52,39),Point(2,39),Point(51,4))
    val clip=PreparedClip(FlatPath(Vector(Vector(Point(8,0),Point(60,20),Point(12,45)))),FillRule.NonZero)
    for path <- Vector(FlatPath(Vector(a,b)),FlatPath(Vector(a,b.reverse)),FlatPath(Vector(bow)),FlatPath(Vector(a,a)));rule <- Vector(FillRule.NonZero,FillRule.EvenOdd) do
      val mesh=WindingMesh.tessellate(path,rule,Vector(clip),target,new WindingMesh.Budget)
      for y <- 0 until 48;x <- 0 until 64 do
        val p=Point(x+0.371,y+0.619)
        def cross(a: Point,b: Point,c: Point)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
        val hits=mesh.positions.grouped(6).count { v =>
          val a=Point(v(0),v(1));val b=Point(v(2),v(3));val c=Point(v(4),v(5))
          val u=cross(a,b,p);val v1=cross(b,c,p);val w=cross(c,a,p)
          (u>0 && v1>0 && w>0)||(u<0 && v1<0 && w<0)
        }
        val expected=inside(p,path,rule)&&inside(p,clip.path,clip.rule)
        assertEquals(hits,if expected then 1 else 0,s"$p $rule")
  }

  test("four-scale batched fills, curved strokes, holes, nested clips, gradients and isolated layers match reference") {
    val hole=Path(Path.rectangle(Rect(4,4,48,32)).segments++Path.rectangle(Rect(14,12,20,12)).segments)
    val curve=Path.build { p => p.moveTo(2,30);p.cubicTo(Point(12,1),Point(42,1),Point(60,34)) }
    val d=Drawing.record { c =>
      c.fill(hole,gradient,FillRule.EvenOdd)
      c.clipped(Path.roundedRectangle(Rect(3,2,54,40),5)) { a =>
        a.layer(0.6) { b =>
          b.fill(Path.rectangle(Rect(0,0,34,46)),blue)
          b.clipped(Path.rectangle(Rect(6,2,54,36)))(_.layer(0.7)(_.stroke(curve,gradient,StrokeStyle(2,LineCap.Round,LineJoin.Round))))
          b.fill(Path.rectangle(Rect(22,3,24,18)),blue)
        }
      }
    }
    val gpu=BatchGpu.open();val ref=OffscreenGpu.open()
    try scales.foreach { s =>
      val p=prepared(gpu,d,s);val program=gpu.compile(p);val retained=gpu.retain(program)
      try
        val actual=retained.render();save("curves-layers",actual)
        val expected=BatchOracle.render(ref,p)
        compare(actual,expected,p,s"curves-layers-$s")
      finally retained.close()
    } finally {gpu.close();ref.close()}
  }

  test("four-scale compact mask and RGBA textures preserve transformed crops, filtering, paint and text") {
    val shaped=run(size=14)
    val edge=ImageSnapshot.copyRgba8(2,1,Array(255,0,0,255,0,255,0,0).map(_.toByte))
    val d=Drawing.record { c =>
      c.image(edge.full,Rect(2.25,2.25,26,10),Sampling.Linear)
      c.transformed(Affine.rotation(0.08).andThen(Affine.translation(32,1))) { a => a.image(ImageRegion(edge,Rect(0.25,0,1.5,1)),Rect(0,0,22,12),Sampling.Nearest,0.7) }
      c.clipped(Path.rectangle(Rect(5,15,50,30)))(_.layer(0.8)(_.glyphs(shaped,Point(4.25,34.5),gradient)))
    }
    val gpu=BatchGpu.open();val ref=OffscreenGpu.open()
    try scales.foreach { s =>
      val p=prepared(gpu,d,s);val r=gpu.retain(gpu.compile(p))
      try
        val actual=r.render();save("textures-text",actual)
        val expected=ref.render(PixelProgram.compile(DrawingPreparation.prepare(d,ref.target((64*s).toInt,(48*s).toInt,s)).toOption.get))
        compare(actual,expected,p,s"textures-text-$s")
      finally r.close()
    } finally {gpu.close();ref.close()}
  }

  test("distinct drawings share GPU geometry and compact assets with new paint and overlay") {
    val shaped=run();val src=picture()
    def scene(overlay: Boolean)=Drawing.record { c =>
      c.glyphs(shaped,Point(4.25,20.5),if overlay then gradient else red)
      c.image(src.full,Rect(44,5,12,12))
      if overlay then c.fill(Path.rectangle(Rect(5,32,12,6)),blue)
    }
    val gpu=BatchGpu.open()
    try scales.foreach { s =>
      val cold=gpu.retain(gpu.compile(prepared(gpu,scene(false),s)))
      try
        val before=gpu.stats;val cache=gpu.cacheStats
        val p=gpu.compile(prepared(gpu,scene(true),s));val overlay=gpu.retain(p)
        try
          assertEquals(gpu.stats.textureUploadBytes,before.textureUploadBytes)
          assertEquals(gpu.cacheStats.rasterizations,cache.rasterizations)
          assert(gpu.cacheStats.meshHits>cache.meshHits)
          val added=gpu.stats.meshUploadBytes-before.meshUploadBytes
          assert(added<=48,s"overlay geometry upload=$added")
          val a=overlay.render();save("overlay",a)
          val uploaded=gpu.stats
          for _ <- 0 until 5 do overlay.submit()
          assertEquals(gpu.stats.meshUploadBytes,uploaded.meshUploadBytes);assertEquals(gpu.stats.textureUploadBytes,uploaded.textureUploadBytes);assertEquals(gpu.stats.programUploadBytes,uploaded.programUploadBytes)
        finally overlay.close()
      finally cold.close()
    } finally gpu.close()
  }

  test("pinned texture pressure rejects with rollback; idle cells evict and old snapshots remain unchanged") {
    val gpu=BatchGpu.open(BatchLimits(imageEntries=2))
    def scene(src: ImageSnapshot)=Drawing.record(_.image(src.full,Rect(4,4,12,12)))
    val redImage=picture();val blueImage=picture(Color(0,0,1));val greenImage=picture(Color(0,1,0))
    try
      val redProgram=gpu.retain(gpu.compile(prepared(gpu,scene(redImage))))
      val blueProgram=gpu.retain(gpu.compile(prepared(gpu,scene(blueImage))))
      try
        val before=gpu.stats.pinnedEntries
        val failProgram=gpu.compile(prepared(gpu,scene(greenImage)))
        assert(intercept[IllegalStateException](gpu.retain(failProgram)).getMessage.contains("status=6"))
        assertEquals(gpu.stats.pinnedEntries,before)
        assertEquals(redProgram.render().pixel(8,8),Color(1,0,0));assertEquals(blueProgram.render().pixel(8,8),Color(0,0,1))
        blueProgram.close()
        val green=gpu.retain(failProgram)
        try {assertEquals(green.render().pixel(8,8),Color(0,1,0));assertEquals(redProgram.render().pixel(8,8),Color(1,0,0));assert(gpu.stats.evictions>0)} finally green.close()
      finally {redProgram.close();blueProgram.close()}
      assertEquals(gpu.stats.pinnedEntries,0L)
    finally gpu.close()
  }

  test("pinned mesh pressure rejects without moving live spans; idle meshes evict") {
    val gpu=BatchGpu.open(BatchLimits(meshVertices=12,meshEntries=2))
    def scene(size: Int)=Drawing.record(_.fill(Path.rectangle(Rect(2,2,size,size)),red))
    try
      val a=gpu.retain(gpu.compile(prepared(gpu,scene(8))))
      val b=gpu.retain(gpu.compile(prepared(gpu,scene(10))))
      try
        val p=gpu.compile(prepared(gpu,scene(12)))
        val before=gpu.stats.pinnedEntries
        assert(intercept[IllegalStateException](gpu.retain(p)).getMessage.contains("status=6"))
        assertEquals(gpu.stats.pinnedEntries,before);assertEquals(a.render().pixel(8,8),Color(1,0,0))
        b.close();val c=gpu.retain(p)
        try {assertEquals(c.render().pixel(12,12),Color(1,0,0));assertEquals(a.render().pixel(12,12),Color(0,0,0,0))} finally c.close()
      finally {a.close();b.close()}
    finally gpu.close()
  }

  test("resource budgets refuse whole programs, oversized cells and excessive layer depth") {
    val gpu=BatchGpu.open()
    try
      val huge=ImageSnapshot.copyRgba8(257,1,new Array[Byte](257*4))
      assert(intercept[IllegalArgumentException](gpu.compile(prepared(gpu,Drawing.record(_.image(huge.full,Rect(0,0,1,1)))))).getMessage.contains("cell budget"))
      val layers=Drawing.record(_.layer(0.5)(_.layer(0.5)(_.layer(0.5)(_.fill(Path.rectangle(Rect(0,0,1,1)),red)))))
      assert(intercept[IllegalArgumentException](gpu.compile(prepared(gpu,layers))).getMessage.contains("layer budget"))
      assert(intercept[IllegalArgumentException](gpu.compile(prepared(gpu,Drawing.record(_ => ()),w=641))).getMessage.contains("target budget"))
      val complex=FlatPath(Vector(Vector.tabulate(4098)(i => Point(i%64,(i*7)%48))))
      assert(intercept[IllegalArgumentException](WindingMesh.tessellate(complex,FillRule.EvenOdd,Vector.empty,gpu.target(64,48,1),new WindingMesh.Budget)).getMessage.contains("edge budget"))
      val horizontal=FlatPath(Vector(Vector.tabulate(4098)(i => Point(i%64,2))))
      assert(intercept[IllegalArgumentException](WindingMesh.tessellate(horizontal,FillRule.NonZero,Vector.empty,gpu.target(64,48,1),new WindingMesh.Budget)).getMessage.contains("edge budget"))
      val tooMany=Drawing.record { c => for _ <- 0 until 17 do c.image(picture().full,Rect(0,0,1,1)) }
      assert(intercept[IllegalArgumentException](gpu.compile(prepared(gpu,tooMany))).getMessage.contains("compiled texture inventory budget"))
      assertEquals(gpu.stats.programs,0L);assertEquals(gpu.stats.pinnedEntries,0L)
    finally gpu.close()
  }

  test("ownership, actual device generation replacement and 100 disposal cycles") {
    val gpu=BatchGpu.open();val d=Drawing.record(_.fill(Path.rectangle(Rect(4,4,12,12)),red));val old=prepared(gpu,d)
    val p=gpu.compile(old);val resource=gpu.retain(p)
    val caught=new AtomicReference[Throwable]()
    val thread=new Thread(() => try {resource.submit();()} catch {case e: Throwable => caught.set(e)})
    thread.start();thread.join();assert(caught.get().isInstanceOf[IllegalArgumentException])
    gpu.close();assertEquals(BatchGpu.liveOwners,0);assert(resource.program==null)
    intercept[IllegalArgumentException](resource.render());resource.close()
    val fresh=BatchGpu.open()
    try
      assert(!(fresh.generation eq old.target.device));intercept[IllegalArgumentException](fresh.compile(old));intercept[IllegalArgumentException](fresh.retain(p))
      for _ <- 0 until 100 do
        val r=fresh.retain(fresh.compile(prepared(fresh,d)))
        r.submit();r.close();assertEquals(fresh.stats.programs,0L);assertEquals(fresh.stats.pinnedEntries,0L)
      for i <- 0 until 20 do
        val s=scales(i%4);val r=fresh.retain(fresh.compile(prepared(fresh,d,s,64+i,48+i)))
        try assertEquals(r.render().pixel((8*s).toInt,(8*s).toInt),Color(1,0,0)) finally r.close()
    finally fresh.close()
    assertEquals(BatchGpu.liveOwners,0)
  }

  test("pinned R8 mask pressure, exact face reuse and paint-independent replacement") {
    val gpu=BatchGpu.open(BatchLimits(glyphEntries=2))
    val f=NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),12)
    def text(value: String)=f.shape(value,ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en"))
    val a=text("I");val b=text("M");val c=text("W");f.close()
    def scene(run: ShapedRun,paint: Paint=red)=Drawing.record(_.glyphs(run,Point(4,20),paint))
    try
      val first=gpu.retain(gpu.compile(prepared(gpu,scene(a))))
      val second=gpu.retain(gpu.compile(prepared(gpu,scene(b))))
      try
        val expected=first.render().pixelsCopy.toVector;val before=gpu.stats.pinnedEntries
        val program=gpu.compile(prepared(gpu,scene(c,gradient)))
        assert(intercept[IllegalStateException](gpu.retain(program)).getMessage.contains("status=6"))
        assertEquals(gpu.stats.pinnedEntries,before);assertEquals(first.render().pixelsCopy.toVector,expected)
        second.close();val third=gpu.retain(program)
        try {assert(third.render().pixelsCopy.exists(_!=0));assertEquals(first.render().pixelsCopy.toVector,expected)} finally third.close()
      finally {first.close();second.close()}
      assertEquals(gpu.stats.pinnedEntries,0L)
    finally gpu.close()
  }

  test("triangle work and GPU program inventory limits cannot produce partial success") {
    val gpu=BatchGpu.open()
    try
      val p=prepared(gpu,Drawing.record(_.fill(Path.rectangle(Rect(1,1,2,2)),red)))
      val work=new WindingMesh.Budget;work.pairs=16000000
      val path=FlatPath(Vector(Vector(Point(1,1),Point(3,1),Point(3,3),Point(1,3))))
      assert(intercept[IllegalArgumentException](WindingMesh.tessellate(path,FillRule.NonZero,Vector.empty,p.target,work)).getMessage.contains("work budget"))
      val triangles=new WindingMesh.Budget;triangles.triangles=262144
      assert(intercept[IllegalArgumentException](WindingMesh.tessellate(path,FillRule.NonZero,Vector.empty,p.target,triangles)).getMessage.contains("triangle budget"))
      val program=gpu.compile(p)
      val retained=Vector.fill(16)(gpu.retain(program))
      try
        assert(intercept[IllegalStateException](gpu.retain(program)).getMessage.contains("status=6"))
        assertEquals(gpu.stats.programs,16L)
      finally retained.foreach(_.close())
      assertEquals(gpu.stats.pinnedEntries,0L);assertEquals(gpu.stats.programs,0L)
    finally gpu.close()
  }

  test("tiled verification oracle agrees with the full reference without increasing its work budget") {
    val shaped=run();val src=picture(Color(1,0,0,0.5))
    val d=Drawing.record { c =>
      c.fill(Path.roundedRectangle(Rect(1,1,60,44),8),gradient)
      c.layer(0.6) { a =>
        a.glyphs(shaped,Point(2.25,24.5),gradient)
        a.clipped(Path.rectangle(Rect(5,3,45,33)))(_.image(src.full,Rect(28.25,8.5,25,24),Sampling.Linear))
      }
    }
    val ref=OffscreenGpu.open()
    try scales.foreach { s =>
      val p=DrawingPreparation.prepare(d,ref.target((64*s).toInt,(48*s).toInt,s)).toOption.get
      val full=ref.render(ref.compile(p));val tiled=BatchOracle.render(ref,p)
      val comparison=BatchOracle.compare(tiled,full,p)
      assertEquals(comparison.mismatches,0,comparison.toString)
      assert(comparison.checked>full.width*full.height/2)
    } finally ref.close()
  }

  test("global GPU payload bound refuses a fourth full-size two-layer program and releases its pins") {
    val gpu=BatchGpu.open()
    try
      val d=Drawing.record(_.layer(0.5)(_.layer(0.5)(_ => ())))
      val p=gpu.compile(prepared(gpu,d,w=640,h=360))
      val baseline=gpu.stats.payloadBytes
      val retained=Vector.fill(3)(gpu.retain(p))
      try
        assert(gpu.stats.payloadBytes<=201326592L)
        assert(intercept[IllegalStateException](gpu.retain(p)).getMessage.contains("status=6"))
        assertEquals(gpu.stats.programs,3L)
      finally retained.foreach(_.close())
      assertEquals(gpu.stats.payloadBytes,baseline);assertEquals(gpu.stats.pinnedEntries,0L)
    finally gpu.close()
  }
