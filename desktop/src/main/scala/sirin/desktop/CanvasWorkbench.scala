package sirin.desktop

import java.nio.file.{Files,Path as FilePath}
import sirin.graphics.*
import sirin.graphics.text.NativeFont
import sirin.render.BatchGpu

private[desktop] object CanvasFixture:
  val paths: Vector[Path]=Vector.tabulate(100) { i =>
    val x=4+(i%20)*15.0;val y=8+(i/20)*26.0
    Path.build { b => b.moveTo(x,y+9);b.cubicTo(Point(x+2,y+0.7),Point(x+7,y+13.3),Point(x+11,y+2.1)) }
  }
  val image: ImageSnapshot=ImageSnapshot.copyRgba8(4,4,Array.tabulate[Byte](64)(i =>
    (i%4 match
      case 0 => (i/4)%4*70
      case 1 => (i/16)*70
      case 2 => 180
      case _ => 220).toByte))
  def drawing(paths: Vector[Path]=paths,label: Option[ShapedRun]=None): Drawing=Drawing.record { c =>
    c.fill(Path.rectangle(Rect(-1024,-1024,2048,2048)),Paint.Solid(Color(0.06,0.08,0.12)))
    val brush=Paint.LinearGradient(Point(0,0),Point(320,180),Vector(ColorStop(0,Color(0.1,0.7,1)),ColorStop(1,Color(1,0.2,0.5))))
    paths.foreach(p => c.stroke(p,brush,StrokeStyle(0.8,LineCap.Round,LineJoin.Round)))
    c.layer(0.8)(_.image(image.full,Rect(280.3,138.7,24.2,27.6),Sampling.Linear))
    label.foreach(run => c.glyphs(run,Point(8.3,166.7),Paint.Solid(Color(0.9,0.9,1))))
  }

/** Interactive graphics driver: arrows pan, +/- zoom, E edits, R destroys the
  * actual device, Escape/native close retires it. All recordings use Drawing. */
object CanvasWorkbench:
  def main(args: Array[String]): Unit =
    if args.headOption.contains("benchmark") then {CanvasInputBenchmark.main(args.drop(1));return}
    val root=FilePath.of(System.getProperty("sirin.repo",".")).toAbsolutePath.normalize()
    val face=NativeFont.open(FontData.copy(Files.readAllBytes(root.resolve("graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),12)
    val label=try face.shape("Pan / zoom / edit / recover",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en")) finally face.close()
    val window=new NativeWindow("Sirin retained canvas",320,180)
    try run(window,label,args) finally window.close()
  private def run(window: NativeWindow,label: ShapedRun,args: Array[String]): Unit =
    val handle=new java.util.concurrent.atomic.AtomicReference(window.prepareRenderer(BatchGpu.shaderSource))
    var preparation=Option.empty[PreparationCache] // only the persistent worker touches this
    val service=new OwnedRenderService[CanvasRequest,CanvasFrame](() => {window.wake();()},_.generation,() => {
      val cache=preparation.getOrElse {val value=new PreparationCache;preparation=Some(value);value}
      new RetainedCanvas(BatchGpu.shared(handle.get()),cache)
    },(frame,_,_) => frame,() => preparation.foreach(_.close()))
    var current=Option.empty[CanvasFrame]
    var serial=0L;var camera=CanvasCamera();var paths=CanvasFixture.paths
    var scene=CanvasFixture.drawing(paths,Some(label))
    var facts=window.waitEvent(0);var running=true;var pending=true;var stage=0;var recovered=0;var losses=0
    val scenario=args.contains("scenario")
    val deadline=System.nanoTime()+60000000000L
    def submit(): Unit =
      serial=Math.addExact(serial,1)
      service.submit(CanvasRequest(serial,scene,camera,facts.width(),facts.height(),facts.width().toDouble/facts.logicalWidth()))
      pending=false
    def recover(): Unit =
      losses+=1;require(losses<=3,"repeated canvas device loss")
      current.foreach(_.close());current=None
      service.quiesce();handle.set(window.prepareRenderer(BatchGpu.shaderSource));pending=true;recovered+=1
    try
      while running do
        if window.rendererLost() then recover()
        if facts.quit() then running=false
        if facts.kind()==1 then facts.key() match
          case 4 => running=false
          case 5 => camera=camera.copy(y=camera.y+8);pending=true
          case 6 => camera=camera.copy(y=camera.y-8);pending=true
          case key if key==1000+'+'.toInt || key==1000+'='.toInt => camera=camera.zoomAt(1.25,Point(160,90));pending=true
          case key if key==1000+'-'.toInt => camera=camera.zoomAt(0.8,Point(160,90));pending=true
          case key if key==1000+'e'.toInt => paths=paths.updated(49,Path.rectangle(Rect(140,50,10,12)));scene=CanvasFixture.drawing(paths,Some(label));pending=true
          case key if key==1000+'r'.toInt => window.destroyRenderer()
          case _ => ()
        if facts.kind()==6 then
          camera=camera.zoomAt(if facts.deltaY()>0 then 1.25 else 0.8,Point(facts.x(),facts.y()));pending=true
        if facts.redraw() then pending=true
        if running && pending && facts.width()>0 && facts.height()>0 then submit()
        service.poll().foreach {
          case (id,Right(frame)) if id==serial =>
            current.foreach(_.close());current=Some(frame)
            if frame.frame.present(window) then
              losses=0
              println(s"canvasFrame=$serial camera=$camera prepared=${frame.prepared} compiled=${frame.compiled} upload=${frame.uploaded} work=${frame.work} ms=${frame.nanos/1e6} cache=${frame.cache}")
              if scenario then
                stage match
                  case 0 => camera=camera.copy(x=8,y=0.37);pending=true;stage=1
                  case 1 => camera=camera.zoomAt(1.25,Point(160,90));pending=true;stage=2
                  case 2 => paths=paths.updated(49,Path.rectangle(Rect(140,50,10,12)));scene=CanvasFixture.drawing(paths,Some(label));pending=true;stage=3
                  case 3 => window.destroyRenderer();stage=4
                  case 4 => require(recovered==1,"canvas recovery missing");stage=5;window.inject(8,0,0,0,0,0)
                  case _ => ()
          case (_,Right(frame)) => frame.close()
          case (_,Left(_: GraphicsDeviceLost)) => recover()
          case (_,Left(error)) => throw error
        }
        if running && window.rendererLost() then recover()
        if scenario then require(System.nanoTime()<deadline,"canvas scenario deadline")
        if running then facts=window.waitEvent(if pending then 0 else -1)
      require(!scenario || stage==5,"canvas scenario incomplete")
      println(s"canvasScenario=$stage recovered=$recovered")
    finally
      current.foreach(_.close())
      try service.close() finally window.close()
    require(BatchGpu.liveOwners==0 && BatchGpu.liveHealthRecords==0 && sirin.render.GpuFrame.liveFrames==0 && sirin.render.GpuFrame.liveTiles==0,"canvas native owners leaked")
