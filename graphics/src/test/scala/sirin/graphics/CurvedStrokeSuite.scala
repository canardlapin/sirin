package sirin.graphics

class CurvedStrokeSuite extends munit.FunSuite:
  private val device=new DeviceGeneration("test","test")
  private val red=Paint.Solid(Color(1,0,0))
  private val tolerance=0.125
  private val a=Point(10,70);private val b=Point(60,-10);private val d=Point(110,70)
  private def quadratic=Path.build { p => p.moveTo(a.x,a.y);p.quadraticTo(b,d) }
  private def position(t: Double)=Point((1-t)*(1-t)*a.x+2*(1-t)*t*b.x+t*t*d.x,(1-t)*(1-t)*a.y+2*(1-t)*t*b.y+t*t*d.y)
  private def tangent(t: Double)=Point(2*((1-t)*(b.x-a.x)+t*(d.x-b.x)),2*((1-t)*(b.y-a.y)+t*(d.y-b.y)))
  private def contains(path: FlatPath,p: Point): Boolean =
    var winding=0
    var i=0
    while i<path.contours.size do
      val contour=path.contours(i)
      var j=0
      while j<contour.size do
        val a=contour(j);val b=contour(if j+1<contour.size then j+1 else 0)
        if (a.y<=p.y && b.y>p.y) || (b.y<=p.y && a.y>p.y) then
          val orientation=(b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x)
          if (if b.y>a.y then orientation>0 else orientation<0) then winding+=(if b.y>a.y then 1 else -1)
        j+=1
      i+=1
    winding!=0
  private def prepare(path: Path,style: StrokeStyle,m: Affine): PreparedCommand.Fill =
    DrawingPreparation.prepare(Drawing.record(_.stroke(path,red,style)),RenderTarget(device,320,240,m))
      .fold(e => fail(e.toString),identity).commands.head.asInstanceOf[PreparedCommand.Fill]

  test("quadratic normal ribbons preserve narrow/wide boundaries at four scales under shear and reflection") {
    for scale <- Vector(1.0,1.25,1.5,2.0);width <- Vector(2.0,40.0);
        affine <- Vector(Affine.identity,Affine(1,0.3,0.5,1,0,0),Affine.scale(-1,1)) do
      val m=affine.andThen(Affine.scale(scale,scale))
      val outline=prepare(quadratic,StrokeStyle(width),m).path
      val inv=m.inverse.get
      val delta=1.5*tolerance*math.hypot(math.hypot(inv.a,inv.b),math.hypot(inv.c,inv.d))
      for i <- 1 until 40;sign <- Vector(-1,1) do
        val t=i/40.0;val p=position(t);val v=tangent(t);val length=math.hypot(v.x,v.y)
        def point(r: Double)=m(Point(p.x-sign*r*v.y/length,p.y+sign*r*v.x/length))
        assert(contains(outline,point(width/2-delta)),s"missing inner boundary t=$t width=$width scale=$scale")
        assert(!contains(outline,point(width/2+delta)),s"extra outer boundary t=$t width=$width scale=$scale")
  }

  test("cubic inflections use their original endpoint tangents for caps and joins") {
    val cubic=Path.build { p => p.moveTo(10,50);p.cubicTo(Point(30,10),Point(80,90),Point(110,50)) }
    val outline=prepare(cubic,StrokeStyle(4),Affine.identity).path
    val u=StrokeCurves.unit(Point(20,-40),Point(1,0))
    assert(!contains(outline,Point(10-u.x*0.3,50-u.y*0.3)))
    assert(contains(outline,Point(10+u.x*0.3,50+u.y*0.3)))
    val joined=Path.build { p => p.moveTo(10,40);p.quadraticTo(Point(20,20),Point(60,20));p.lineTo(60,60) }
    assert(contains(prepare(joined,StrokeStyle(10),Affine.identity).path,Point(64.5,15.5)))
    assert(!contains(prepare(joined,StrokeStyle(10,miterLimit=1),Affine.identity).path,Point(64.5,15.5)))
  }

  test("aggregate curve chord length stays within dash phase budget against independent Simpson integration") {
    def speed(t: Double)={val v=tangent(t);math.hypot(v.x,v.y)}
    val n=10000
    val reference=(speed(0)+speed(1)+(1 until n).map(i => speed(i.toDouble/n)*(if i%2==0 then 2 else 4)).sum)/(3*n)
    for density <- Vector(1.0,1.25,1.5,2.5) do
      val contour=StrokeCurves.flatten(quadratic,StrokeStyle(8,dashes=Vector(7,3)),density,tolerance,262144,24).head
      val length=contour.pieces.map(_.length).sum
      assert(reference>=length-1e-9)
      assert(reference-length<=tolerance/(16*density),s"reference=$reference chord=$length density=$density")
  }

  test("closed curved dash seams retain paint and explicit cusp/depth failures reject whole attempts") {
    val closed=Path.build { p => p.moveTo(20,40);p.quadraticTo(Point(40,0),Point(60,40));p.lineTo(20,40);p.close() }
    val p=prepare(closed,StrokeStyle(4,LineCap.Round,dashes=Vector(15,3)),Affine.identity)
    assertEquals(p.color,PreparedPaint.Solid(Color(1,0,0)))
    val target=RenderTarget(device,160,120,Affine.identity)
    val cusp=Path.build { p => p.moveTo(10,10);p.cubicTo(Point(40,10),Point(-20,10),Point(10,10)) }
    assertEquals(DrawingPreparation.prepare(Drawing.record(_.stroke(cusp,red)),target).left.toOption,Some(PreparationFailure.Unsupported("stroke cusp")))
    val reversal=Path.build { p => p.moveTo(0,0);p.lineTo(10,0);p.lineTo(0,1e-7) }
    val illConditioned=Drawing.record(_.stroke(reversal,red,StrokeStyle(0.001,miterLimit=1e10)))
    assert(DrawingPreparation.prepare(illConditioned,target).left.toOption.get.isInstanceOf[PreparationFailure.Invalid])
    val big=1e16
    val imprecise=Path.build { p => p.moveTo(big,big);p.cubicTo(Point(big+16,big+32),Point(big+32,big-16),Point(big+48,big)) }
    val rebased=target.copy(logicalToDevice=Affine.translation(-big,-big))
    assert(DrawingPreparation.prepare(Drawing.record(_.stroke(imprecise,red)),rebased).left.toOption.get.isInstanceOf[PreparationFailure.Invalid])
    val drawing=Drawing.record(_.stroke(quadratic,red,StrokeStyle(40)))
    assert(DrawingPreparation.prepare(drawing,target,limits=PreparationLimits(maxCurveDepth=1)).left.toOption.get.isInstanceOf[PreparationFailure.Budget])
  }
