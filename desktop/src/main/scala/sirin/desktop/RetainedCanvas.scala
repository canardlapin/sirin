package sirin.desktop

import sirin.graphics.*
import sirin.graphics.desktop.GpuTileCache
import sirin.render.{BatchGpu,GpuFrame}

/** Camera operates on ordinary immutable Drawing values. No application policy. */
final case class CanvasCamera(x: Double=0,y: Double=0,zoom: Double=1):
  require(x.isFinite && y.isFinite && zoom.isFinite && zoom>=0.25 && zoom<=4,"canvas camera range")
  def matrix(scale: Double): Affine=Affine.scale(zoom*scale,zoom*scale).andThen(Affine.translation(x*scale,y*scale))
  def zoomAt(factor: Double,point: Point): CanvasCamera =
    require(factor.isFinite && factor>0,"canvas zoom factor")
    val next=math.max(0.25,math.min(4,zoom*factor));val ratio=next/zoom
    CanvasCamera(point.x-(point.x-x)*ratio,point.y-(point.y-y)*ratio,next)

final case class CanvasRequest(generation: Long,drawing: Drawing,camera: CanvasCamera,width: Int,height: Int,scale: Double):
  require(generation>0 && width>0 && height>0 && scale.isFinite && scale>0,"canvas request")
final case class CanvasFrame(request: CanvasRequest,frame: GpuFrame,prepared: Boolean,
    cache: PreparationCacheStats,work: Long,nanos: Long,compiled: Long,uploaded: Long) extends AutoCloseable:
  def close(): Unit=frame.close()

/** Pure preparation ownership may outlive a lost GPU generation. */
final class RetainedCanvas(gpu: BatchGpu,preparation: PreparationCache,edge: Int=128)
    extends OwnedRenderBackend[CanvasRequest,CanvasFrame]:
  private val tiles=new GpuTileCache(gpu,edge)
  private var prior=Option.empty[CanvasRequest]
  def render(request: CanvasRequest,cancelled: () => Boolean): CanvasFrame =
    val began=System.nanoTime()
    val control=new PreparationControl(cancelled=cancelled)
    control.checkpoint()
    val before=gpu.stats
    val unchanged=prior.exists(p => (p.drawing eq request.drawing) && p.copy(generation=request.generation)==request)
    val output=if unchanged then tiles.frame(request.width,request.height) else
      prior=None
      val target=gpu.target(request.width,request.height,request.scale).copy(logicalToDevice=request.camera.matrix(request.scale))
      val drawing=preparation.prepare(request.drawing,target,control=control).fold(e => throw new IllegalArgumentException(e.toString),identity)
      tiles.render(drawing,cancelled,control)
    try
      control.checkpoint();prior=Some(request)
      val after=gpu.stats
      CanvasFrame(request,output,!unchanged,preparation.stats,control.usedWork,System.nanoTime()-began,
        if unchanged then 0 else tiles.lastCompiled,
        after.meshUploadBytes-before.meshUploadBytes+after.textureUploadBytes-before.textureUploadBytes+after.programUploadBytes-before.programUploadBytes)
    catch case e: Throwable => {output.close();throw e}
  def stats: String=if gpu.isLost then s"deviceLost=true preparation=${preparation.stats}" else s"GPU=${gpu.stats} preparation=${preparation.stats} tiles=${tiles.stats}"
  def close(): Unit=try tiles.close() finally gpu.close()
