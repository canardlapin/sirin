package sirin.graphics.desktop

import sirin.graphics.*
import sirin.render.{BatchGpu,BatchRetained,GpuFrame,GpuTile}

/** Completed immutable tile variants. Temporary programs never escape the worker. */
final class GpuTileCache(gpu: BatchGpu,edge: Int=128) extends AutoCloseable:
  private class Entry(val position: (Int,Int),val signature: Vector[Any],val width: Int,val height: Int,
      var tile: Option[GpuTile],var program: Option[BatchRetained])
  private case class Split(signature: Vector[Any], vertical: Boolean)
  private val splits = scala.collection.mutable.LinkedHashMap.empty[(Int, Int, Int, Int), Split]
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
  def tileCount: Int =
    check()
    current.size
  def stats: String=s"entries=${entries.size} pixels=$pixels hits=$hits misses=$misses"
  def close(): Unit =
    check()
    entries.toVector.foreach(remove)
    splits.clear()
    target = None
    current = Vector.empty
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
    var leaves = 0
    var visits = 0
    def renderTile(rect: Rect): Unit =
      if cancelled() then throw new InterruptedException("obsolete GPU frame")
      control.checkpoint()
      visits += 1
      require(visits <= 512, "GPU adaptive tile work budget")
      val prepared = TiledRenderer.withoutEmptyMasks(TiledRenderer.tile(drawing, rect), gpu.hasGlyphInk)
      lastPrepared += 1
      val signature = key(prepared)
      val position = (rect.x.toInt, rect.y.toInt)
      val existing = entries.find(e => e.position == position && e.signature == signature && e.tile.nonEmpty &&
        e.width == prepared.target.width && e.height == prepared.target.height)
      val region = (position._1, position._2, rect.width.toInt, rect.height.toInt)
      def divide(vertical: Boolean): Unit =
        if vertical then
          val half = math.floor(rect.width / 2)
          renderTile(rect.copy(width = half))
          renderTile(Rect(rect.x + half, rect.y, rect.width - half, rect.height))
        else
          val half = math.floor(rect.height / 2)
          renderTile(rect.copy(height = half))
          renderTile(Rect(rect.x, rect.y + half, rect.width, rect.height - half))
      existing match
        case Some(e) =>
          hits += 1
          entries.remove(e)
          entries.add(e)
          leaves += 1
          require(leaves <= 256, "GPU frame tile count")
          active += e
          output += e
        case None => splits.get(region).filter(_.signature == signature) match
          case Some(split) => divide(split.vertical)
          case None =>
            val program = gpu.compile(prepared, control)
            lastCompiled += 1
            if !gpu.fits(program) && math.max(rect.width, rect.height) > 32 then
              // Retain only bounded immutable partition metadata, never a native resource.
              while splits.size >= 256 do splits.remove(splits.head._1)
              val split = Split(signature, rect.width >= rect.height && rect.width > 32)
              splits(region) = split
              divide(split.vertical)
            else
              splits.remove(region)
              require(gpu.fits(program), "GPU tile resource budget at minimum tile size")
              leaves += 1
              require(leaves <= 256, "GPU frame tile count")
              misses += 1
              val size = prepared.target.width.toLong * prepared.target.height
              if pending.size >= 4 || (pending.nonEmpty && !gpu.canRetain(program)) then flush()
              while entries.size >= 256 || pixels + size > 1048576 do
                val victim = entries.find(e => !active(e))
                  .getOrElse(throw new IllegalStateException("active GPU frame budget"))
                remove(victim)
              val retained = gpu.retain(program, control)
              val e = new Entry(position, signature, prepared.target.width, prepared.target.height,
                None, Some(retained))
              entries.add(e)
              pixels += size
              pending += e
              active += e
              output += e
    try
      for y <- 0 until h by edge; x <- 0 until w by edge do
        renderTile(Rect(x, y, math.min(edge, w - x), math.min(edge, h - y)))
      if cancelled() then throw new InterruptedException("obsolete GPU frame")
      flush()
      current = output.result().map(e => (e.position._1, e.position._2, e.tile.get))
      GpuFrame(current, w, h)
    catch
      case error: Throwable =>
        // Completed immutable tiles may stay cached; incomplete programs must release their pins now.
        pending.toVector.foreach(remove)
        pending.clear()
        throw error

object GpuTileCache:
  /** Both inputs have already passed shared preparation and use the same target. */
  def compose(base: PreparedDrawing,overlay: PreparedDrawing): PreparedDrawing =
    require(base.target==overlay.target,"prepared composition target mismatch")
    val source=Drawing.record { c => c.draw(base.source);c.draw(overlay.source) }
    val resources=DrawingResources.inspect(source).fold(e => throw new IllegalArgumentException(e.toString),identity)
    require(base.visitedCommands+overlay.visitedCommands<=65536 && base.preparedPoints+overlay.preparedPoints<=262144,"composed preparation budget")
    new PreparedDrawing(source,base.target,base.capabilities,base.commands++overlay.commands,
      resources,base.visitedCommands+overlay.visitedCommands,base.preparedPoints+overlay.preparedPoints)
