package sirin.graphics.desktop

import sirin.graphics.*
import sirin.render.{OffscreenGpu,PixelProgram}

final case class TileReceipt(image: ImageSnapshot,programBytes: Long,readbackBytes: Long,maskBytes: Long,tiles: Int)

/** Conservative device-space partitioning around the unchanged graphics renderer.
  * All fills, layers and painter order survive. Only provably disjoint glyph ink
  * is removed; the shared program still rasterizes every surviving operation.
  */
object TiledRenderer:
  private def bounds(points: Vector[Point]): Option[Rect] =
    if points.isEmpty then None else
      val x=points.map(_.x).min;val y=points.map(_.y).min
      Some(Rect(x,y,points.map(_.x).max-x,points.map(_.y).max-y))
  private def overlaps(a: Rect,b: Rect): Boolean = a.x+a.width>=b.x && b.x+b.width>=a.x && a.y+a.height>=b.y && b.y+b.height>=a.y
  private def translated(path: FlatPath,x: Double,y: Double): FlatPath = FlatPath(path.contours.map(_.map(p => Point(p.x-x,p.y-y))))
  private def rectangle(path: FlatPath): Option[Rect] =
    if path.contours.size!=1 then None else
      val raw=path.contours.head
      val points=if raw.size>1 && raw.head==raw.last then raw.dropRight(1) else raw
      bounds(points).filter { r =>
        val corners=Set(Point(r.x,r.y),Point(r.x+r.width,r.y),Point(r.x+r.width,r.y+r.height),Point(r.x,r.y+r.height))
        points.size==4 && points.toSet==corners && points.zip(points.drop(1):+points.head).forall((a,b) => a.x==b.x || a.y==b.y)
      }
  private def clip(clips: Vector[PreparedClip],tile: Rect): Vector[PreparedClip] =
    val rectangles=clips.map(c => rectangle(c.path))
    if rectangles.forall(_.nonEmpty) then
      val intersection=rectangles.flatten.foldLeft(tile) { (a,b) =>
        val x=math.max(a.x,b.x);val y=math.max(a.y,b.y)
        Rect(x,y,math.max(0,math.min(a.x+a.width,b.x+b.width)-x),math.max(0,math.min(a.y+a.height,b.y+b.height)-y))
      }
      if intersection==tile then Vector.empty else
        val r=Rect(intersection.x-tile.x,intersection.y-tile.y,intersection.width,intersection.height)
        Vector(PreparedClip(FlatPath(Vector(Vector(Point(r.x,r.y),Point(r.x+r.width,r.y),Point(r.x+r.width,r.y+r.height),Point(r.x,r.y+r.height)))),FillRule.NonZero))
    else clips.map(c => c.copy(path=translated(c.path,tile.x,tile.y)))
  private def ink(glyph: PositionedGlyph,origin: Point,matrix: Affine): Option[Rect] = glyph.ink.flatMap { r =>
    val x=origin.x+glyph.position.x+r.x;val y=origin.y+glyph.position.y+r.y
    bounds(Vector(Point(x,y),Point(x+r.width,y),Point(x+r.width,y+r.height),Point(x,y+r.height)).map(matrix.apply))
      .map(b => Rect(b.x-2,b.y-2,b.width+4,b.height+4))
  }
  def tile(drawing: PreparedDrawing,area: Rect): PreparedDrawing =
    val move=Affine.translation(-area.x,-area.y)
    val commands=drawing.commands.flatMap {
      case PreparedCommand.Fill(path,color,rule,clips) =>
        Option.when(bounds(path.contours.flatten).exists(overlaps(_,area)))(PreparedCommand.Fill(translated(path,area.x,area.y),color,rule,clip(clips,area))).toVector
      case PreparedCommand.Glyphs(run,origin,color,matrix,clips) =>
        val glyphs=run.glyphs.filter(g => ink(g,origin,matrix).forall(overlaps(_,area)))
        if glyphs.isEmpty then Vector.empty else
          val subset=new ShapedRun(run.source,run.font,run.options,glyphs,run.range,run.context)
          Vector(PreparedCommand.Glyphs(subset,origin,color,matrix.andThen(move),clip(clips,area)))
      case PreparedCommand.Image(source,destination,sampling,opacity,matrix,clips) =>
        Vector(PreparedCommand.Image(source,destination,sampling,opacity,matrix.andThen(move),clip(clips,area)))
      case other => Vector(other)
    }
    val target=drawing.target.copy(width=area.width.toInt,height=area.height.toInt,logicalToDevice=drawing.target.logicalToDevice.andThen(move))
    new PreparedDrawing(drawing.source,target,drawing.capabilities,commands,drawing.resources,drawing.visitedCommands,drawing.preparedPoints)
  def render(gpu: OffscreenGpu,drawing: PreparedDrawing,edge: Int = 128)(cancelled: () => Boolean): TileReceipt =
    require(edge>=16 && edge<=256,"invalid render tile size")
    val width=drawing.target.width;val height=drawing.target.height
    require(width.toLong*height<=1048576,"development framebuffer budget")
    require(((width+edge-1)/edge).toLong*((height+edge-1)/edge)<=256,"render tile count budget")
    val output=new Array[Byte](width*height*4)
    var programBytes=0L;var readback=0L;var mask=0L;var count=0
    for y <- 0 until height by edge; x <- 0 until width by edge do
      if cancelled() then throw new InterruptedException("obsolete tiled frame")
      val area=Rect(x,y,math.min(edge,width-x),math.min(edge,height-y))
      val program=PixelProgram.compile(tile(drawing,area))
      val image=gpu.render(program);val bytes=image.pixelsCopy
      for row <- 0 until image.height do System.arraycopy(bytes,row*image.width*4,output,((y+row)*width+x)*4,image.width*4)
      programBytes+=program.uploadBytes;readback+=program.readbackBytes;mask+=program.maskBytes;count+=1
    TileReceipt(ImageSnapshot.copyRgba8(width,height,output),programBytes,readback,mask,count)
