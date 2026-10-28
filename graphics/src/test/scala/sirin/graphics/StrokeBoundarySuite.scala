package sirin.graphics

class StrokeBoundarySuite extends munit.FunSuite:
  private val device=new DeviceGeneration("test","CPU")
  private val red=Paint.Solid(Color(1,0,0))
  test("100 curved strokes fit the unchanged preparation point budget at every frozen scale") {
    val drawing=Drawing.record { c =>
      for i <- 0 until 100 do
        val x=3+(i%25)*12;val y=75+(i/25)*11
        val p=Path.build { b => b.moveTo(x,y+7);b.cubicTo(Point(x+2,y),Point(x+7,y+10),Point(x+10,y+2)) }
        c.stroke(p,red,StrokeStyle(0.8,LineCap.Round,LineJoin.Round))
    }
    for scale <- Vector(1.0,1.25,1.5,2.0) do
      val target=RenderTarget(device,(320*scale).toInt,(180*scale).toInt,Affine.scale(scale,scale))
      val result=DrawingPreparation.prepare(drawing,target).fold(e => fail(e.toString),identity)
      assertEquals(result.commands.size,100)
      assert(result.preparedPoints<=262144)
      assert(DrawingPreparation.prepare(drawing,target,limits=PreparationLimits(maxPoints=32)).left.toOption.get.isInstanceOf[PreparationFailure.Budget])
  }
  test("compact self-overlapping round stroke agrees with independent segment-distance union") {
    val points=Vector(Point(3,3),Point(27,25),Point(3,25),Point(27,3),Point(3,3))
    val path=Path.build { p => p.moveTo(points.head.x,points.head.y);points.tail.foreach(q => p.lineTo(q.x,q.y));p.close() }
    val drawing=Drawing.record(_.stroke(path,red,StrokeStyle(3,LineCap.Round,LineJoin.Round)))
    val fill=DrawingPreparation.prepare(drawing,RenderTarget(device,32,32,Affine.identity)).toOption.get.commands.head.asInstanceOf[PreparedCommand.Fill].path
    def distance(p: Point,a: Point,b: Point): Double =
      val dx=b.x-a.x;val dy=b.y-a.y
      val t=math.max(0,math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)))
      math.hypot(p.x-a.x-dx*t,p.y-a.y-dy*t)
    def contains(p: Point): Boolean =
      val winding=fill.contours.foldLeft(0) { (total,ps) => total+ps.zip(ps.drop(1):+ps.head).foldLeft(0) { case (n,(a,b)) =>
        val cross=(b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x)
        if a.y<=p.y && b.y>p.y && cross>0 then n+1 else if b.y<=p.y && a.y>p.y && cross<0 then n-1 else n
      }}
      winding!=0
    for y <- 0 until 64;x <- 0 until 64 do
      val p=Point(x/2.0+0.173,y/2.0+0.231)
      val closest=points.zip(points.tail).map((a,b) => distance(p,a,b)).min
      if math.abs(closest-1.5)>0.2 then assertEquals(contains(p),closest<1.5,s"$p distance=$closest")
  }
