package sirin.graphics

import sirin.render.{OffscreenGpu,PixelProgram}

/** Independent device-space tile translation, without editing the desktop host. */
class RebasedGradientSuite extends munit.FunSuite:
  test("four-scale translated prepared paths preserve clipped gradient pixels") {
    val gradient=Paint.LinearGradient(Point(0,0),Point(25,8),Vector(
      ColorStop(0,Color(1,0,0)),ColorStop(0.5,Color(0,1,0,0.6)),ColorStop(1,Color(0,0,1))),
      Affine.rotation(0.17),Extend.Reflect)
    val drawing=Drawing.record { c =>
      c.layer(0.7)(_.clipped(Path.rectangle(Rect(20,10,100,90))) { clipped =>
        clipped.transformed(Affine(1,0.2,0.3,1,5,3))(_.fill(Path.rectangle(Rect(0,0,120,90)),gradient))
      })
    }
    val gpu=OffscreenGpu.open()
    try Vector(1.0,1.25,1.5,2.0).foreach { scale =>
      val prepared=DrawingPreparation.prepare(drawing,gpu.target((160*scale).toInt,(120*scale).toInt,scale)).toOption.get
      val full=gpu.render(PixelProgram.compile(prepared))
      val x=(32*scale).toInt;val y=(16*scale).toInt;val size=(64*scale).toInt
      def translated(p: FlatPath)=FlatPath(p.contours.map(_.map(v => Point(v.x-x,v.y-y))))
      val commands=prepared.commands.map {
        case PreparedCommand.Fill(path,paint,rule,clips) =>
          PreparedCommand.Fill(translated(path),paint,rule,clips.map(c => c.copy(path=translated(c.path))))
        case other => other
      }
      val target=prepared.target.copy(width=size,height=size,
        logicalToDevice=prepared.target.logicalToDevice.andThen(Affine.translation(-x,-y)))
      val tile=new PreparedDrawing(drawing,target,prepared.capabilities,commands,prepared.resources,prepared.visitedCommands,prepared.preparedPoints)
      val actual=gpu.render(PixelProgram.compile(tile))
      for py <- 0 until size;px <- 0 until size do
        val a=actual.pixel(px,py);val b=full.pixel(px+x,py+y)
        Vector(a.red-b.red,a.green-b.green,a.blue-b.blue,a.alpha-b.alpha).foreach(v =>
          assert(math.abs(v)<=1.0/255,s"tile mismatch $px,$py scale=$scale: $a vs $b"))
    }
    finally gpu.close()
    assertEquals(OffscreenGpu.liveOwners,0)
  }
