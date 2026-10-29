package sirin.graphics

class StrokePreparationSuite extends munit.FunSuite:
  private val red=Paint.Solid(Color(1,0,0,0.5))
  private val device=new DeviceGeneration("test","test")
  private def target(m: Affine = Affine.identity)=RenderTarget(device,160,120,m)
  private def line=Path.build { p => p.moveTo(20,20);p.lineTo(60,20) }
  private def ready(path: Path,style: StrokeStyle,m: Affine = Affine.identity): PreparedCommand.Fill =
    val d=Drawing.record(_.stroke(path,red,style))
    DrawingPreparation.prepare(d,target(m)).fold(e => fail(e.toString),identity).commands.head.asInstanceOf[PreparedCommand.Fill]
  private def contains(path: FlatPath,p: Point): Boolean =
    var winding=0
    path.contours.foreach { contour =>
      contour.zip(contour.drop(1):+contour.head).foreach { (a,b) =>
        if (a.y<=p.y && b.y>p.y) || (b.y<=p.y && a.y>p.y) then
          val x=a.x+(p.y-a.y)*(b.x-a.x)/(b.y-a.y)
          if x>p.x then winding+= (if b.y>a.y then 1 else -1)
      }
    }
    winding!=0

  test("stroke caps have analytic extents and round arcs obey device error after shear") {
    val transforms=Vector(Affine.identity,Affine(1,0.4,0.7,1.5,4,3),Affine.scale(-2,1.25))
    for m <- transforms;cap <- LineCap.values do
      val fill=ready(line,StrokeStyle(width=8,cap=cap),m)
      assert(contains(fill.path,m(Point(40,20))))
      assert(!contains(fill.path,m(Point(40,25))))
      assertEquals(contains(fill.path,m(Point(18,20))),cap!=LineCap.Butt)
      assertEquals(contains(fill.path,m(Point(17,17))),cap==LineCap.Square)
      if cap==LineCap.Round then
        val disk=fill.path.contours.find(_.size>5).get
        // Every sampled analytic circle lies within the allowed chord error.
        def distance(p: Point,a: Point,b: Point): Double =
          val dx=b.x-a.x;val dy=b.y-a.y
          val t=math.max(0,math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)))
          math.hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)
        for i <- 0 until 720 do
          val p=m(Point(20+4*math.cos(i*math.Pi/360),20+4*math.sin(i*math.Pi/360)))
          assert(disk.sliding(2).map(v => distance(p,v(0),v(1))).min<=0.125/2)
  }

  test("miter extension, limit fallback and round/bevel joins are distinct") {
    val corner=Path.build { p => p.moveTo(10,30);p.lineTo(30,30);p.lineTo(30,50) }
    def at(join: LineJoin,limit: Double,p: Point)=contains(ready(corner,StrokeStyle(10,join=join,miterLimit=limit)).path,p)
    assert(at(LineJoin.Miter,4,Point(34.5,25.5)))
    assert(!at(LineJoin.Miter,1,Point(34.5,25.5)))
    assert(!at(LineJoin.Bevel,4,Point(34,26)))
    assert(at(LineJoin.Round,4,Point(33,27)))
    assert(!at(LineJoin.Round,4,Point(34.5,25.5)))
  }

  test("dashes honor odd repetition, phase, zero dots and restart per subpath") {
    val path=Path.build { p => p.moveTo(0,20);p.lineTo(80,20);p.moveTo(0,40);p.lineTo(80,40) }
    val odd=StrokeStyle(4,dashes=Vector(5,3,2),dashOffset=2)
    val fill=ready(path,odd)
    val pattern=odd.normalizedDashes
    for y <- Vector(20.0,40.0);x <- 0 until 79 do
      val phase=(x+0.37+2)%pattern.sum
      val spans=pattern.scanLeft(0.0)(_+_)
      val on=spans.sliding(2).zipWithIndex.exists((v,i) => i%2==0 && phase>=v(0) && phase<v(1))
      assertEquals(contains(fill.path,Point(x+0.37,y)),on)
    val dots=ready(line,StrokeStyle(4,LineCap.Round,dashes=Vector(0,8)))
    assert(contains(dots.path,Point(20,20)))
    assert(!contains(dots.path,Point(24,20)))
    val diagonal=Path.build { p => p.moveTo(20,20);p.lineTo(60,60) }
    val squares=ready(diagonal,StrokeStyle(4,LineCap.Square,dashes=Vector(0,12)))
    assert(contains(squares.path,Point(22.5,20)))
    assert(!contains(squares.path,Point(21.8,21.8)))
    val equivalent=ready(path,odd.copy(dashOffset=odd.dashOffset-pattern.sum))
    assertEquals(equivalent.path,fill.path)
  }

  test("closed contours join at the dash seam; overlap is one translucent fill") {
    val box=Path.rectangle(Rect(20,20,20,20))
    val solid=ready(box,StrokeStyle(4,join=LineJoin.Miter))
    assert(contains(solid.path,Point(18.5,18.5)))
    assert(!contains(solid.path,Point(30,30)))
    val seam=ready(box,StrokeStyle(4,join=LineJoin.Miter,dashes=Vector(14,6),dashOffset=4))
    assert(contains(seam.path,Point(18.5,18.5)))
    assertEquals(solid.color,PreparedPaint.Solid(Color(1,0,0,0.5)))
  }

  test("zero-length segments and move-only paths have explicit cap semantics") {
    val point=Path.build { p => p.moveTo(20,20);p.lineTo(20,20) }
    for cap <- LineCap.values do
      assertEquals(contains(ready(point,StrokeStyle(4,cap)).path,Point(20.5,20.5)),cap!=LineCap.Butt)
    assertEquals(ready(Path.build(_.moveTo(20,20)),StrokeStyle(4,LineCap.Round)).path.contours,Vector.empty)
  }

  test("cusps and exhausted stroke/gradient budgets fail the whole attempt") {
    val curve=Path.build { p => p.moveTo(0,0);p.quadraticTo(Point(20,20),Point(0,0)) }
    val d=Drawing.record(_.stroke(curve,red))
    assertEquals(DrawingPreparation.prepare(d,target()).left.toOption,Some(PreparationFailure.Unsupported("stroke cusp")))
    val many=Drawing.record(_.stroke(line,red,StrokeStyle(4,dashes=Vector(0.001,0.001))))
    assert(DrawingPreparation.prepare(many,target(),limits=PreparationLimits(maxPoints=100)).left.toOption.get.isInstanceOf[PreparationFailure.Budget])
    val gradient=Paint.LinearGradient(Point(0,0),Point(1,0),Vector.fill(4097)(ColorStop(0,Color(1,0,0))))
    assert(DrawingPreparation.prepare(Drawing.record(_.fill(line,gradient)),target()).left.toOption.get.isInstanceOf[PreparationFailure.Budget])
    val round=Drawing.record(_.stroke(line,red,StrokeStyle(100,LineCap.Round)))
    assert(DrawingPreparation.prepare(round,target(),limits=PreparationLimits(maxPoints=10)).isLeft)
  }

  test("gradient projection follows paint, instance, shear and path-relative tile origins") {
    val gradient=Paint.LinearGradient(Point(0,0),Point(10,4),Vector(ColorStop(0,Color(1,0,0)),ColorStop(1,Color(0,0,1))),Affine.rotation(0.3),Extend.Reflect)
    val m=Affine(1,0.2,0.4,1,30,12)
    val d=Drawing.record(_.transformed(m)(_.fill(Path.rectangle(Rect(3,4,20,20)),gradient)))
    val fill=DrawingPreparation.prepare(d,target()).toOption.get.commands.head.asInstanceOf[PreparedCommand.Fill]
    val PreparedPaint.LinearGradient(x,y,offset,stops,extend)=fill.color: @unchecked
    assertEquals(stops,gradient.stops);assertEquals(extend,Extend.Reflect)
    val anchor=fill.path.contours.head.head
    val inverse=gradient.paintToLocal.andThen(m).inverse.get
    for p <- Vector(Point(3,6),Point(40,20),Point(-4,2)) do
      val q=inverse(p)
      val expected=(q.x*10+q.y*4)/116
      assertEqualsDouble((p.x-anchor.x)*x+(p.y-anchor.y)*y+offset,expected,1e-12)
      val moved=Point(p.x-16,p.y-8);val origin=Point(anchor.x-16,anchor.y-8)
      assertEqualsDouble((moved.x-origin.x)*x+(moved.y-origin.y)*y+offset,expected,1e-12)
    val singular=Drawing.record(_.transformed(Affine.scale(0,1))(_.fill(line,gradient)))
    assert(DrawingPreparation.prepare(singular,target()).isLeft)
  }
