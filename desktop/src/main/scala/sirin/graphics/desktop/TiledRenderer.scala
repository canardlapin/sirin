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
        val r=destination
        val extent=bounds(Vector(Point(r.x,r.y),Point(r.x+r.width,r.y),Point(r.x+r.width,r.y+r.height),Point(r.x,r.y+r.height)).map(matrix.apply))
        Option.when(extent.exists(b => b.x+b.width>area.x && area.x+area.width>b.x && b.y+b.height>area.y && area.y+area.height>b.y))(PreparedCommand.Image(source,destination,sampling,opacity,matrix.andThen(move),clip(clips,area))).toVector
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

/** Session-local LRU. Signatures include every pixel-affecting prepared value. */
final class TileCache(gpu: OffscreenGpu,rasterize: (ResolvedFont,Int,Double,Int) => GlyphRaster,edge: Int=128,maxPrograms: Int=64,maxBytes: Long=48L*1024*1024) extends AutoCloseable:
  private final case class Entry(signature: Vector[Any],var program: Option[sirin.render.RetainedProgram],var image: Option[ImageSnapshot],bytes: Long)
  private val entries=scala.collection.mutable.LinkedHashMap.empty[(Int,Int),Entry]
  private var used=0L
  private var target=Option.empty[RenderTarget]
  require(maxPrograms>0 && maxPrograms<=64 && maxBytes>0 && maxBytes<=48L*1024*1024,"cache inventory budgets")
  require(edge>=32 && edge<=192,"tile edge budget")
  var lastProfile=""
  var hits=0L;var misses=0L
  private def stripEmptyMasks(d: PreparedDrawing): PreparedDrawing =
    val commands=d.commands.flatMap {
      case PreparedCommand.Glyphs(run,origin,color,matrix,clips) =>
        val scale=PixelProgram.glyphScale(matrix)
        val glyphs=run.glyphs.filter(g => g.ink.nonEmpty || (scale>0 && rasterize(run.font,g.id,scale,2097152).byteSize>0))
        if glyphs.isEmpty then Vector.empty else
          val subset=new ShapedRun(run.source,run.font,run.options,glyphs,run.range,run.context)
          Vector(PreparedCommand.Glyphs(subset,origin,color,matrix,clips))
      case command => Vector(command)
    }
    new PreparedDrawing(d.source,d.target,d.capabilities,commands,d.resources,d.visitedCommands,d.preparedPoints)
  private def key(d: PreparedDrawing): Vector[Any] = d.commands.map {
    case PreparedCommand.Glyphs(run,origin,color,matrix,clips) => (run.font,run.glyphs,origin,color,matrix,clips)
    case other => other
  }
  private def dropProgram(position: (Int,Int)): Unit = entries.get(position).foreach { e =>
    e.program.foreach { p => used-=e.bytes;p.close() };e.program=None
  }
  private def remove(position: (Int,Int)): Unit = {dropProgram(position);entries.remove(position);()}
  def close(): Unit = { entries.keys.toVector.foreach(remove);target=None }
  def snapshots: Vector[(Rect,ImageSnapshot)] = entries.iterator.collect {
    case ((x,y),e) if e.image.nonEmpty =>
      val image=e.image.get;(Rect(x,y,image.width,image.height),image)
  }.toVector
  def render(drawing: PreparedDrawing,cancelled: () => Boolean): TileReceipt =
    if !target.contains(drawing.target) then {close();target=Some(drawing.target)}
    val width=drawing.target.width;val height=drawing.target.height
    require(width.toLong*height<=1048576,"development framebuffer budget")
    val chunk=if ((width+edge-1)/edge)*((height+edge-1)/edge)<=256 then edge else 128
    val output=new Array[Byte](width*height*4)
    var compiledNanos=0L;var gpuNanos=0L;var preparationNanos=0L;var copiedNanos=0L
    var uploaded=0L;var readback=0L;var mask=0L;var tiles=0
    val pending=scala.collection.mutable.ArrayBuffer.empty[((Int,Int),sirin.render.RetainedProgram)]
    def flush(): Unit = if pending.nonEmpty then
      val at=System.nanoTime()
      val images=gpu.replayBatch(pending.map(_._2).toVector)
      pending.zip(images).foreach { (item,image) => entries(item._1).image=Some(image) }
      pending.clear();gpuNanos+=System.nanoTime()-at
    for y <- 0 until height by chunk; x <- 0 until width by chunk do
      if cancelled() then throw new InterruptedException("obsolete tiled frame")
      val position=(x,y)
      val preparedAt=System.nanoTime()
      val prepared=stripEmptyMasks(TiledRenderer.tile(drawing,Rect(x,y,math.min(chunk,width-x),math.min(chunk,height-y))))
      val signature=key(prepared)
      preparationNanos+=System.nanoTime()-preparedAt
      entries.get(position).filter(e => e.signature==signature && e.image.nonEmpty) match
        case Some(e) => hits+=1;entries.remove(position);entries(position)=e
        case None =>
          misses+=1;remove(position)
          val compileAt=System.nanoTime()
          val program=PixelProgram.compile(prepared,rasterize=Some(rasterize))
          compiledNanos+=System.nanoTime()-compileAt
          val bytes=program.uploadBytes+prepared.target.width.toLong*prepared.target.height*4
          require(bytes<=maxBytes,"tile inventory budget")
          if entries.values.count(_.program.nonEmpty)>=maxPrograms || used+bytes>maxBytes then flush()
          while entries.values.count(_.program.nonEmpty)>=maxPrograms || used+bytes>maxBytes do
            dropProgram(entries.iterator.find(_._2.program.nonEmpty).get._1)
          val gpuAt=System.nanoTime()
          val retained=gpu.retain(program);gpuNanos+=System.nanoTime()-gpuAt
          entries(position)=Entry(signature,Some(retained),None,bytes);used+=bytes
          pending+=((position,retained))
          uploaded+=program.uploadBytes;readback+=program.readbackBytes;mask+=program.maskBytes
      tiles+=1
    flush()
    val copyAt=System.nanoTime()
    for y <- 0 until height by chunk; x <- 0 until width by chunk do
      val image=entries((x,y)).image.get;val pixels=image.pixelsCopy
      for row <- 0 until image.height do System.arraycopy(pixels,row*image.width*4,output,((y+row)*width+x)*4,image.width*4)
    copiedNanos+=System.nanoTime()-copyAt
    lastProfile=s"tilePrepareMs=${preparationNanos/1e6} compileMs=${compiledNanos/1e6} gpuMs=${gpuNanos/1e6} copyMs=${copiedNanos/1e6}"
    TileReceipt(ImageSnapshot.copyRgba8(width,height,output),uploaded,readback,mask,tiles)
