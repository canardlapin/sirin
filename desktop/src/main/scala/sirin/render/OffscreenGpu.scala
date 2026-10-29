// JVM FFM adaptation of the graphics track transport.
package sirin.render

import sirin.graphics.*
import sirin.desktop.ffm.Memory.*
import sirin.desktop.ffm.Calls

final class OffscreenGpu private(private var handle: Ptr[Byte],val generation: DeviceGeneration) extends AutoCloseable:
  private val owner=Thread.currentThread()
  private def check(): Unit =
    require(Thread.currentThread() eq owner,"GPU owner thread mismatch")
    require(handle!=null,"GPU owner is closed")
  def target(width: Int,height: Int,scale: Double): RenderTarget =
    check();require(scale.isFinite && scale>0,"invalid device scale")
    RenderTarget(generation,width,height,Affine.scale(scale,scale))
  def render(program: PixelProgram,thorvg: Boolean = false): ImageSnapshot =
    check();require(program.drawing.target.device eq generation,"prepared drawing device generation mismatch")
    val t=program.drawing.target
    require(t.width.toLong*t.height<=1048576,"pixel budget")
    Zone.acquire { implicit zone =>
      val data=alloc[CFloat](program.words.length);program.words.indices.foreach(i => data(i)=program.words(i))
      val out=alloc[Byte](t.width*t.height*4)
      val status=GpuShim.sgpu_render(handle,data,program.words.length,t.width,t.height,if thorvg then 1 else 0,out)
      if status!=0 then throw new IllegalStateException(s"render status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
      ImageSnapshot.copyRgba8(t.width,t.height,Array.tabulate(t.width*t.height*4)(i => out(i)))
    }
  def close(): Unit =
    require(Thread.currentThread() eq owner,"GPU owner thread mismatch")
    if handle!=null then {GpuShim.sgpu_close(handle);handle=null}

object OffscreenGpu:
  def liveOwners: Int = GpuShim.sgpu_live()
  def open(): OffscreenGpu = Zone.acquire { implicit zone =>
    val stream=Option(getClass.getResourceAsStream("/raster.wgsl")).getOrElse(throw new IllegalStateException("missing renderer shader"))
    val shader=try new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8) finally stream.close()
    val out=alloc[Ptr[Byte]]();val error=alloc[Byte](512)
    val status=GpuShim.sgpu_open(toCString(shader),out,error)
    if status!=0 then throw new IllegalStateException(s"GPU open: ${fromCString(error)}")
    val handle= !out
    try new OffscreenGpu(handle,new DeviceGeneration("wgpu-native-29.0.1.1/reference-v1",fromCString(GpuShim.sgpu_adapter(handle))))
    catch
      case error: Throwable => GpuShim.sgpu_close(handle);throw error
  }
private[render] object GpuShim:
  def sgpu_open(shader: CString,out: Ptr[Ptr[Byte]],error: Ptr[Byte]): CInt = Calls.int("sgpu_open", shader, out, error)
  def sgpu_close(handle: Ptr[Byte]): Unit = Calls.unit("sgpu_close", handle)
  def sgpu_render(handle: Ptr[Byte],data: Ptr[CFloat],count: CInt,width: CInt,height: CInt,thorvg: CInt,out: Ptr[Byte]): CInt = Calls.int("sgpu_render", handle, data, count, width, height, thorvg, out)
  def sgpu_adapter(handle: Ptr[Byte]): CString = Calls.ptr[Byte]("sgpu_adapter", handle)
  def sgpu_error(handle: Ptr[Byte]): CString = Calls.ptr[Byte]("sgpu_error", handle)
  def sgpu_live(): CInt = Calls.int("sgpu_live")
