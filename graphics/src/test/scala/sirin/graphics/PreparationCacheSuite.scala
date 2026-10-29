package sirin.graphics

class PreparationCacheSuite extends munit.FunSuite:
  private val generation=new DeviceGeneration("test","CPU")
  private def target(scale: Double=1,x: Double=0,y: Double=0)=RenderTarget(generation,320,180,Affine.scale(scale,scale).andThen(Affine.translation(x,y)))
  private val paths=Vector.tabulate(100) { i =>
    val x=(i%20)*14.0;val y=(i/20)*30.0
    Path.build { p => p.moveTo(x,y+7);p.cubicTo(Point(x+2,y+1),Point(x+7,y+12),Point(x+10,y+2)) }
  }
  private def drawing(ps: Vector[Path])=Drawing.record(c => ps.foreach(p => c.stroke(p,Paint.Solid(Color(0.2,0.6,0.8)),StrokeStyle(0.8))))
  test("pan reuses all source geometry, edits rebuild selectively, scale rebuilds") {
    val cache=new PreparationCache
    try
      val scene=drawing(paths)
      val cold=cache.prepare(scene,target()).toOption.get;val before=cache.stats
      assertEquals(before.misses,100L)
      val pan=cache.prepare(scene,target(x=0.37,y=4)).toOption.get
      assertEquals(cache.stats.misses,before.misses);assertEquals(cache.stats.hits,100L)
      assertEquals(pan.preparedPoints,cold.preparedPoints)
      val reference=DrawingPreparation.prepare(scene,target(x=0.37,y=4)).toOption.get
      val actual=pan.commands.collect {case PreparedCommand.Fill(p,_,_,_) => p.contours.flatten}.flatten
      val expected=reference.commands.collect {case PreparedCommand.Fill(p,_,_,_) => p.contours.flatten}.flatten
      assertEquals(actual.size,expected.size)
      assert(actual.zip(expected).forall((a,b) => math.hypot(a.x-b.x,a.y-b.y)<1e-10))
      val changed=paths.updated(49,Path.rectangle(Rect(126,60,8,9)))
      cache.prepare(drawing(changed),target()).toOption.get
      assertEquals(cache.stats.misses,before.misses+1)
      cache.prepare(drawing(changed),target(2)).toOption.get
      assertEquals(cache.stats.misses,before.misses+101)
      assert(cache.stats.entries<=256 && cache.stats.payloadBytes<=4194304)
    finally cache.close()
  }
  test("cache hits retain original point budgets and cannot use a stricter subdivision contract") {
    val cache=new PreparationCache
    try
      val scene=drawing(paths.take(1))
      val prepared=cache.prepare(scene,target()).toOption.get
      val limited=cache.prepare(scene,target(),limits=PreparationLimits(maxPoints=prepared.preparedPoints-1))
      assert(limited.isLeft)
      assert(cache.prepare(scene,target(),limits=PreparationLimits(maxCurveDepth=1)).isLeft)
    finally cache.close()
  }
  test("cancellation and work refusal publish no partial result; completed entries remain reusable") {
    val cache=new PreparationCache
    try
      var checks=0;var cancelled=false
      val control=new PreparationControl(cancelled=() => {checks+=1;checks>250})
      val began=System.nanoTime()
      try cache.prepare(drawing(paths),target(),control=control)
      catch case _: InterruptedException => cancelled=true
      val elapsed=System.nanoTime()-began
      println(s"preparationCancellation checks=$checks work=${control.usedWork} elapsedNs=$elapsed completedEntries=${cache.stats.entries}")
      assert(cancelled);assertEquals(checks,251)
      assert(cache.stats.entries<100)
      assert(cache.prepare(drawing(paths.take(1)),target(),control=new PreparationControl(10)).isLeft)
      val complete=cache.prepare(drawing(paths),target()).toOption.get
      assertEquals(complete.commands.size,100)
    finally cache.close()
  }
  test("one hundred edited recordings keep source and point payload bounded") {
    val cache=new PreparationCache(maxEntries=8,maxBytes=65536)
    try
      for i <- 0 until 100 do
        val p=Path.rectangle(Rect(i*0.01,0,10,10))
        assert(cache.prepare(Drawing.record(_.fill(p,Paint.Solid(Color(1,0,0)))),target()).isRight)
        assert(cache.stats.entries<=8 && cache.stats.payloadBytes<=65536)
    finally cache.close()
    intercept[IllegalArgumentException](cache.stats)
  }
