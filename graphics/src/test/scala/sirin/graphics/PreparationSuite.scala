package sirin.graphics

class PreparationSuite extends munit.FunSuite:
  private val device = new DeviceGeneration("test-backend", "test-adapter")
  private val target = RenderTarget(device, 100, 100, Affine.identity)
  private val red = Paint.Solid(Color(1, 0, 0))
  private val blue = Paint.Solid(Color(0, 0, 1))
  private val box = Path.rectangle(Rect(0, 0, 10, 10))
  private def ready(drawing: Drawing, t: RenderTarget = target): PreparedDrawing =
    DrawingPreparation.prepare(drawing, t).fold(e => fail(e.toString), identity)

  test("expanded children, ordered instances and isolated groups retain painter order") {
    val child = Drawing.record(_.fill(box, red))
    val drawing = Drawing.record { c =>
      c.draw(child)
      c.layer(0.5)(_.instances(box, InstanceBatch(Vector(Affine.translation(2, 0), Affine.translation(4, 0)),
        InstanceValues.PerInstance(Vector(blue, red)))))
      c.draw(child)
    }
    val p = ready(drawing)
    assertEquals(p.resources.uniqueDrawings, 2)
    assertEquals(p.commands.size, 6)
    assertEquals(p.commands(1), PreparedCommand.BeginLayer(0.5))
    assertEquals(p.commands(4), PreparedCommand.EndLayer)
    val fills = p.commands.collect { case f: PreparedCommand.Fill => f }
    assertEquals(fills.map(_.color), Vector(PreparedPaint.Solid(Color(1,0,0)), PreparedPaint.Solid(Color(0,0,1)), PreparedPaint.Solid(Color(1,0,0)), PreparedPaint.Solid(Color(1,0,0))))
    assertEquals(fills.map(_.path.contours.head.head.x), Vector(0.0, 2.0, 4.0, 0.0))
  }

  test("clips freeze at establishment and sibling state is restored") {
    val drawing = Drawing.record { c =>
      c.transformed(Affine.translation(10, 0)) { moved =>
        moved.clipped(box) { clipped =>
          clipped.transformed(Affine.scale(2, 2))(_.fill(box, red))
        }
      }
      c.fill(box, blue)
    }
    val fills = ready(drawing).commands.collect { case f: PreparedCommand.Fill => f }
    assertEquals(fills.head.path.contours.head.take(2), Vector(Point(10,0), Point(30,0)))
    assertEquals(fills.head.clips.head.path.contours.head.take(2), Vector(Point(10,0), Point(20,0)))
    assertEquals(fills.last.clips, Vector.empty)
    assertEquals(fills.last.path.contours.head.head, Point(0,0))
  }

  test("cubic subdivision bounds geometric deviation after scale and shear") {
    val a=Point(2,3);val b=Point(20,90);val c=Point(80,-60);val d=Point(95,40)
    val path=Path.build { p => p.moveTo(a.x,a.y);p.cubicTo(b,c,d) }
    def distance(p: Point, a: Point, b: Point): Double =
      val dx=b.x-a.x;val dy=b.y-a.y
      val t=math.max(0,math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)))
      math.hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)
    for scale <- Vector(1.0,1.25,1.5,2.0) do
      val m=Affine(1,0.3,0.4,1,0,0).andThen(Affine.scale(scale,scale))
      val PreparedCommand.Fill(flat,_,_,_)=ready(Drawing.record(_.fill(path,red)),target.copy(logicalToDevice=m)).commands.head: @unchecked
      val line=flat.contours.head
      for i <- 0 to 1000 do
        val t=i/1000.0;val s=1-t
        val p=m(Point(s*s*s*a.x+3*s*s*t*b.x+3*s*t*t*c.x+t*t*t*d.x,
          s*s*s*a.y+3*s*s*t*b.y+3*s*t*t*c.y+t*t*t*d.y))
        assert(line.sliding(2).map(v => distance(p,v(0),v(1))).min<=target.tolerance)
  }

  test("quadratic, close continuation and large local origins preserve geometry") {
    val big=1e13
    val p=Path.build { p => p.moveTo(big,big);p.quadraticTo(Point(big+5,big+10),Point(big+10,big));p.close();p.lineTo(big,big+5) }
    val PreparedCommand.Fill(flat,_,_,_)=ready(Drawing.record(_.fill(p,red)),target.copy(logicalToDevice=Affine.translation(-big,-big))).commands.head: @unchecked
    assertEquals(flat.contours.head.head,Point(0,0))
    assertEquals(flat.contours.last,Vector(Point(0,0),Point(0,5)))
    assert(flat.contours.head.size>3)
  }

  test("whole attempts reject disabled strokes and gradients even when invisible") {
    val gradient=Paint.LinearGradient(Point(0,0),Point(10,0),Vector(ColorStop(0,Color(0,0,0)),ColorStop(1,Color(1,1,1))))
    for operation <- Vector[Canvas2D => Unit](_.stroke(box,red), _.fill(box,gradient)) do
      val drawing=Drawing.record { c => c.fill(box,red);c.layer(0)(operation) }
      assert(DrawingPreparation.prepare(drawing,target,RenderCapabilities(strokes=false,linearGradients=false)).left.toOption.get.isInstanceOf[PreparationFailure.Unsupported])
  }

  test("capability rejection does not silently omit images, clips or groups") {
    val image=ImageSnapshot.copyRgba8(1,1,Array[Byte](0,0,0,0))
    val cases=Vector(
      (Drawing.record(_.image(image.full,Rect(0,0,0,0))),RenderCapabilities(images=false)),
      (Drawing.record(_.clipped(box)(_.fill(box,red))),RenderCapabilities(pathClips=false)),
      (Drawing.record(_.layer(0)(_.fill(box,red))),RenderCapabilities(isolatedOpacity=false)))
    cases.foreach { (d,c) => assert(DrawingPreparation.prepare(d,target,c).isLeft) }
  }

  test("expanded command, point, recursion, scope and curve budgets fail explicitly") {
    val leaf=Drawing.record(_.fill(box,red))
    val repeated=Drawing.record(c => (0 until 4).foreach(_ => c.draw(leaf)))
    assert(DrawingPreparation.prepare(repeated,target,limits=PreparationLimits(maxCommands=5)).isLeft)
    assert(DrawingPreparation.prepare(leaf,target,limits=PreparationLimits(maxPoints=2)).isLeft)
    val nested=Drawing.record(_.draw(Drawing.record(_.draw(leaf))))
    assert(DrawingPreparation.prepare(nested,target,limits=PreparationLimits(maxDepth=1)).isLeft)
    val groups=Drawing.record(_.layer(1)(_.layer(1)(_.fill(box,red))))
    assert(DrawingPreparation.prepare(groups,target,limits=PreparationLimits(maxLayers=1)).isLeft)
    val curve=Path.build { p => p.moveTo(0,0);p.cubicTo(Point(0,100),Point(100,100),Point(100,0)) }
    assert(DrawingPreparation.prepare(Drawing.record(_.fill(curve,red)),target,limits=PreparationLimits(maxCurveDepth=1)).isLeft)
  }

  test("prepared target compatibility includes device generation, dimensions, transform and tolerance") {
    val p=ready(Drawing.empty)
    assert(p.compatibleWith(target))
    assert(!p.compatibleWith(target.copy(device=new DeviceGeneration(device.backendBuild,device.adapter))))
    assert(!p.compatibleWith(target.copy(width=101)))
    assert(!p.compatibleWith(target.copy(logicalToDevice=Affine.scale(1.25,1.25))))
    assert(!p.compatibleWith(target.copy(tolerance=0.25)))
  }
