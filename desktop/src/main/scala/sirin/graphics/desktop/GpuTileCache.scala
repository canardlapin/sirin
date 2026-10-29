package sirin.graphics.desktop

import sirin.graphics.*
import sirin.render.{BatchGpu,BatchRetained,GpuFrame,GpuTile}

/** Completed immutable tile variants. Temporary programs never escape the worker. */
final class GpuTileCache(gpu: BatchGpu,edge: Int=128) extends AutoCloseable:
  private class Entry(val position: (Int,Int),val signature: Vector[Any],val width: Int,val height: Int,
      var tile: Option[GpuTile],var program: Option[BatchRetained])
  private val owner=Thread.currentThread()
  private val entries=scala.collection.mutable.LinkedHashSet.empty[Entry]
  private var current=Vector.empty[(Int,Int,GpuTile)]
  private var target=Option.empty[RenderTarget]
  private var pixels=0L
  var hits=0L;var misses=0L
  var lastCompiled=0L
  var lastPrepared=0L
  require(edge>=32 && edge<=192,"tile edge budget")
  private def check(): Unit=require(Thread.currentThread() eq owner,"GPU tile cache owner mismatch")
  private def remove(e: Entry): Unit=
    entries.remove(e);pixels-=e.width.toLong*e.height
    e.program.foreach(_.close());e.program=None;e.tile.foreach(_.close());e.tile=None
  private def key(d: PreparedDrawing): Vector[Any]=d.commands.map {
    case PreparedCommand.Glyphs(run,origin,color,matrix,clips) => (run.font,run.glyphs,origin,color,matrix,clips)
    case other => other
  }
  def stats: String=s"entries=${entries.size} pixels=$pixels hits=$hits misses=$misses"
  def close(): Unit={check();entries.toVector.foreach(remove);target=None;current=Vector.empty}
  def frame(width: Int,height: Int): GpuFrame={check();GpuFrame(current,width,height)}
  def render(drawing: PreparedDrawing,cancelled: () => Boolean,control: PreparationControl=new PreparationControl): GpuFrame =
    check();current=Vector.empty;lastCompiled=0;lastPrepared=0
    require(drawing.target.width.toLong*drawing.target.height<=1048576,"GPU frame pixel budget")
    if !target.contains(drawing.target) then {close();target=Some(drawing.target)}
    entries.filter(_.tile.isEmpty).toVector.foreach(remove)
    val active=scala.collection.mutable.HashSet.empty[Entry]
    val output=Vector.newBuilder[Entry]
    val pending=scala.collection.mutable.ArrayBuffer.empty[Entry]
    def flush(): Unit=if pending.nonEmpty then
      control.checkpoint()
      gpu.submitBatch(pending.map(_.program.get).toVector)
      pending.foreach { e =>
        e.tile=Some(e.program.get.exportTile());e.program.get.close();e.program=None
      }
      pending.clear()
    val w=drawing.target.width;val h=drawing.target.height
    require(((w+edge-1)/edge)*((h+edge-1)/edge)<=256,"GPU frame tile count")
    for y <- 0 until h by edge;x <- 0 until w by edge do
      if cancelled() then throw new InterruptedException("obsolete GPU frame")
      val prepared=TiledRenderer.withoutEmptyMasks(
        TiledRenderer.tile(drawing,Rect(x,y,math.min(edge,w-x),math.min(edge,h-y))),gpu.hasGlyphInk)
      lastPrepared+=1
      val signature=key(prepared)
      val position=(x,y)
      val entry=entries.find(e => e.position==position && e.signature==signature && e.tile.nonEmpty) match
        case Some(e) => hits+=1;entries.remove(e);entries.add(e);e
        case None =>
          misses+=1
          val size=prepared.target.width.toLong*prepared.target.height
          if pending.size>=4 then flush()
          while entries.size>=256 || pixels+size>1048576 do
            remove(entries.find(e => !active(e)).getOrElse(throw new IllegalStateException("active GPU frame budget")))
          val program=gpu.compile(prepared,control);lastCompiled+=1
          val retained=gpu.retain(program,control)
          val e=new Entry(position,signature,prepared.target.width,prepared.target.height,None,Some(retained))
          entries.add(e);pixels+=size;pending+=e;e
      active+=entry;output+=entry
    if cancelled() then throw new InterruptedException("obsolete GPU frame")
    flush()
    current=output.result().map(e => (e.position._1,e.position._2,e.tile.get))
    GpuFrame(current,w,h)

object GpuTileCache:
  /** Both inputs have already passed shared preparation and use the same target. */
  def compose(base: PreparedDrawing,overlay: PreparedDrawing): PreparedDrawing =
    require(base.target==overlay.target,"prepared composition target mismatch")
    val source=Drawing.record { c => c.draw(base.source);c.draw(overlay.source) }
    val resources=DrawingResources.inspect(source).fold(e => throw new IllegalArgumentException(e.toString),identity)
    require(base.visitedCommands+overlay.visitedCommands<=65536 && base.preparedPoints+overlay.preparedPoints<=262144,"composed preparation budget")
    new PreparedDrawing(source,base.target,base.capabilities,base.commands++overlay.commands,
      resources,base.visitedCommands+overlay.visitedCommands,base.preparedPoints+overlay.preparedPoints)
