package sirin.desktop

import sirin.graphics.*
import sirin.render.{BatchGpu,BatchRetained,GpuTile,GpuFrame}

/** Two exact-scale GPU variants, plus one immutable current snapshot. */
private[desktop] final class CameraCache(gpu: BatchGpu,preparation: PreparationCache) extends AutoCloseable:
  private case class Entry(request: CanvasRequest,drawing: PreparedDrawing,program: BatchRetained)
  private val entries=scala.collection.mutable.LinkedHashSet.empty[Entry]
  private var current=Option.empty[(CanvasRequest,PreparedDrawing,GpuTile)]
  var compiled=0L;var reusedPixels=0L
  def variants: Int=entries.size
  def frame(): GpuFrame=
    val (r,_,tile)=current.get
    GpuFrame(Vector((0,0,tile)),r.width,r.height)
  def render(request: CanvasRequest,control: PreparationControl): GpuFrame =
    compiled=0;reusedPixels=0
    val tx=request.camera.x*request.scale;val ty=request.camera.y*request.scale
    require(math.abs(tx)<=4096 && math.abs(ty)<=4096,"camera translation range")
    require(math.abs(tx.toFloat.toDouble-tx)<=1.0/1024 && math.abs(ty.toFloat.toDouble-ty)<=1.0/1024,"camera translation precision")
    def compatible(r: CanvasRequest)=r.width==request.width && r.height==request.height && r.scale==request.scale && r.camera.zoom==request.camera.zoom
    val entry=entries.find(e => (e.request.drawing eq request.drawing) && compatible(e.request)) match
      case Some(value) => entries.remove(value);entries.add(value);value
      case None =>
        val target=gpu.target(request.width,request.height,request.scale*request.camera.zoom)
        val drawing=preparation.prepare(request.drawing,target,control=control).fold(e => throw new IllegalArgumentException(e.toString),identity)
        while entries.size>=2 do {val old=entries.head;entries.remove(old);old.program.close()}
        val program=gpu.compileCamera(drawing,control)
        require(math.ulp((program.coordinateMagnitude+4096).toFloat)<=target.tolerance/8,"camera coordinate precision")
        val retained=gpu.retain(program,control)
        val value=Entry(request,drawing,retained);entries.add(value);compiled=1;value
    val corners=Vector(Point(-tx,-ty),Point(request.width-tx,-ty),Point(-tx,request.height-ty),Point(request.width-tx,request.height-ty))
    def paint(value: PreparedPaint,anchor: Point): Unit=value match
      case PreparedPaint.Solid(_) => ()
      case PreparedPaint.LinearGradient(x,y,offset,_,_) =>
        require(corners.forall { p =>
          val exact=(p.x-anchor.x)*x+(p.y-anchor.y)*y+offset
          val packed=(p.x+tx-tx.toFloat-anchor.x.toFloat)*x.toFloat+(p.y+ty-ty.toFloat-anchor.y.toFloat)*y.toFloat+offset.toFloat
          exact.isFinite && math.abs(exact)<=65536 && math.abs(exact-packed)<=1e-6 &&
            math.ulp(math.max(math.abs(p.x),math.abs(p.y)).toFloat)*(math.abs(x)+math.abs(y))<=1e-6
        },"camera gradient precision")
    entry.drawing.commands.foreach {
      case PreparedCommand.Fill(path,value,_,_) => paint(value,path.contours.iterator.flatMap(_.headOption).nextOption().getOrElse(Point(0,0)))
      case PreparedCommand.Image(_,_,_,_,matrix,_) => matrix.inverse.foreach { inv =>
        val approx=Affine(inv.a.toFloat,inv.b.toFloat,inv.c.toFloat,inv.d.toFloat,inv.tx.toFloat,inv.ty.toFloat)
        require(corners.forall(p => math.hypot(inv(p).x-approx(p).x,inv(p).y-approx(p).y)<=1.0/128),"camera image precision")
      }
      case PreparedCommand.Glyphs(_,_,value,matrix,_) => paint(value,matrix(Point(0,0)))
      case _ => ()
    }
    control.checkpoint()
    var mode=0;var region=Array(0f,0f,0f,0f);var dx=0;var dy=0
    current.filter(c => compatible(c._1)).foreach { (previous,prepared,_) =>
      val deltaX=tx-previous.camera.x*previous.scale;val deltaY=ty-previous.camera.y*previous.scale
      if (previous.drawing eq request.drawing) && deltaX==math.rint(deltaX) && deltaY==math.rint(deltaY) && math.abs(deltaX)<request.width && math.abs(deltaY)<request.height then
        dx=deltaX.toInt;dy=deltaY.toInt;mode=1
        region=Array(math.max(0,dx).toFloat,math.max(0,dy).toFloat,math.min(request.width,request.width+dx).toFloat,math.min(request.height,request.height+dy).toFloat)
        reusedPixels=(request.width-math.abs(dx)).toLong*(request.height-math.abs(dy))
      else if deltaX==0 && deltaY==0 then
        dirty(prepared,entry.drawing,tx,ty,request.width,request.height).foreach { area =>
          mode=2;region=area;reusedPixels=request.width.toLong*request.height-(area(2)-area(0)).toLong*(area(3)-area(1)).toLong
        }
    }
    val parameters=Array(tx.toFloat,ty.toFloat,mode.toFloat,0f)++region++Array(dx.toFloat,dy.toFloat,0f,0f)
    val output=entry.program.camera(current.map(_._3).filter(_ => mode!=0),parameters)
    try
      control.checkpoint()
      val packet=GpuFrame(Vector((0,0,output)),request.width,request.height)
      current.foreach(_._3.close());current=Some((request,entry.drawing,output));packet
    catch case e: Throwable => {output.close();throw e}
  private def dirty(before: PreparedDrawing,after: PreparedDrawing,tx: Double,ty: Double,w: Int,h: Int): Option[Array[Float]] =
    if before.commands.size!=after.commands.size then None else
      val changed=before.commands.zip(after.commands).filter((a,b) => a!=b)
      if !changed.forall {case (PreparedCommand.Fill(_,_,_,_),PreparedCommand.Fill(_,_,_,_)) => true;case _ => false} then None else
        val points=changed.flatMap((a,b) => Vector(a,b)).flatMap {case PreparedCommand.Fill(path,_,_,_) => path.contours.flatten;case _ => Vector.empty}
        if points.isEmpty then Some(Array(0f,0f,0f,0f)) else
          def clamp(x: Double,max: Int)=math.max(0,math.min(max,x)).toFloat
          Some(Array(clamp(math.floor(points.map(_.x).min+tx)-2,w),clamp(math.floor(points.map(_.y).min+ty)-2,h),
            clamp(math.ceil(points.map(_.x).max+tx)+2,w),clamp(math.ceil(points.map(_.y).max+ty)+2,h)))
  def close(): Unit=
    entries.foreach(_.program.close());entries.clear();current.foreach(_._3.close());current=None
