package sirin.render

import sirin.graphics.*
import sirin.desktop.ffm.Memory.*
import sirin.desktop.ffm.Calls

final class OffscreenGpu private(private var handle: Ptr[Byte],val generation: DeviceGeneration) extends AutoCloseable:
  private val owner=Thread.currentThread()
  private val retained=scala.collection.mutable.HashSet.empty[RetainedProgram]
  private def check(): Unit =
    require(Thread.currentThread() eq owner,"GPU owner thread mismatch")
    require(handle!=null,"GPU owner is closed")
  def target(width: Int,height: Int,scale: Double): RenderTarget =
    check();require(scale.isFinite && scale>0,"invalid device scale")
    RenderTarget(generation,width,height,Affine.scale(scale,scale))
  def compile(drawing: PreparedDrawing): PixelProgram={check();require(drawing.target.device eq generation,"reference device mismatch");PixelProgram.compile(drawing)}
  def render(program: PixelProgram,thorvg: Boolean = false): ImageSnapshot =
    check();require(program.drawing.target.device eq generation,"prepared drawing device generation mismatch")
    require(program.assets.isEmpty,"desktop bridge admits inline programs only")
    val t=program.drawing.target
    require(t.width.toLong*t.height<=1048576,"pixel budget")
    Zone.acquire { implicit zone =>
      val data=alloc[CFloat](program.words.length);data.raw.copyFrom(java.lang.foreign.MemorySegment.ofArray(program.words))
      val out=alloc[Byte](t.width*t.height*4)
      val status=GpuShim.sgpu_render(handle,data,program.words.length,t.width,t.height,if thorvg then 1 else 0,out)
      if status!=0 then throw new IllegalStateException(s"render status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
      ImageSnapshot.copyRgba8(t.width,t.height,out.raw.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
    }
  def resourceStats: GpuResourceStats =
    check()
    GpuResourceStats(GpuShim.sgpu_programs(handle),GpuShim.sgpu_retained_bytes(handle),GpuShim.sgpu_uploaded_bytes(handle))
  def retain(program: PixelProgram): RetainedProgram =
    check();require(program.drawing.target.device eq generation,"prepared drawing device generation mismatch")
    require(program.assets.isEmpty,"desktop bridge admits inline programs only")
    val t=program.drawing.target
    Zone.acquire { implicit zone =>
      val data=alloc[CFloat](program.words.length);data.raw.copyFrom(java.lang.foreign.MemorySegment.ofArray(program.words))
      val out=alloc[Ptr[Byte]]()
      val status=GpuShim.sgpu_retain_deferred(handle,data,program.words.length,t.width,t.height,out)
      if status!=0 then throw new IllegalStateException(s"retain status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
      val native= !out
      try
        val resource=new RetainedProgram(this,native,t)
        retained+=resource
        resource
      catch
        case error: Throwable => GpuShim.sgpu_program_close(native);throw error
    }
  def replayBatch(resources: Vector[RetainedProgram]): Vector[ImageSnapshot] =
    check();require(resources.nonEmpty && resources.size<=64,"GPU batch count")
    require(resources.forall(retained),"batch contains a closed or foreign program")
    val sizes=resources.map(r => r.target.width*r.target.height*4)
    val size=sizes.map(_.toLong).sum;require(size<=4194304,"GPU batch pixel budget")
    Zone.acquire { implicit zone =>
      val pointers=alloc[Ptr[Byte]](resources.size);resources.zipWithIndex.foreach((r,i) => pointers(i)=r.handle)
      val output=alloc[Byte](size.toInt)
      val status=GpuShim.sirin_desktop_gpu_batch(pointers,resources.size,output)
      if status!=0 then throw new IllegalStateException(s"batch status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
      val bytes=output.raw.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)
      var offset=0
      resources.zip(sizes).map { (resource,length) =>
        val t=resource.target
        val image=ImageSnapshot.copyRgba8(t.width,t.height,java.util.Arrays.copyOfRange(bytes,offset,offset+length));offset+=length;image
      }
    }
  private[render] def replay(resource: RetainedProgram): ImageSnapshot =
    check();require(retained(resource),"retained program is closed or belongs to another owner")
    val t=resource.target
    Zone.acquire { implicit zone =>
      val out=alloc[Byte](t.width*t.height*4)
      val status=GpuShim.sgpu_replay(resource.handle,out)
      if status!=0 then throw new IllegalStateException(s"replay status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
      ImageSnapshot.copyRgba8(t.width,t.height,out.raw.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
    }
  private[render] def retire(resource: RetainedProgram): Unit =
    require(Thread.currentThread() eq owner,"GPU owner thread mismatch")
    if retained.remove(resource) then
      GpuShim.sgpu_program_close(resource.handle)
      resource.clear()
  def close(): Unit =
    require(Thread.currentThread() eq owner,"GPU owner thread mismatch")
    if handle!=null then
      retained.toVector.foreach(retire)
      GpuShim.sgpu_close(handle);handle=null

/** Requested storage-buffer + output-texture payload, excluding driver overhead,
  * staging, pipeline and bind-group allocations. Uploads count direct storage.
  */
final case class GpuResourceStats(programs: Int, retainedBytes: Long, uploadedBytes: Long)

final class RetainedProgram private[render](private val owner: OffscreenGpu,
    private[render] var handle: Ptr[Byte], private[render] val target: RenderTarget) extends AutoCloseable:
  def render(): ImageSnapshot = owner.replay(this)
  def close(): Unit = owner.retire(this)
  private[render] def clear(): Unit =
    handle=null

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
  def sgpu_retain_deferred(handle: Ptr[Byte],data: Ptr[CFloat],count: CInt,width: CInt,height: CInt,out: Ptr[Ptr[Byte]]): CInt = Calls.int("sgpu_retain_deferred", handle, data, count, width, height, out)
  def sirin_desktop_gpu_batch(programs: Ptr[Ptr[Byte]],count: CInt,out: Ptr[Byte]): CInt = Calls.int("sirin_desktop_gpu_batch",programs,count,out)
  def sgpu_replay(program: Ptr[Byte],out: Ptr[Byte]): CInt = Calls.int("sgpu_replay", program, out)
  def sgpu_program_close(program: Ptr[Byte]): Unit = Calls.unit("sgpu_program_close", program)
  def sgpu_programs(handle: Ptr[Byte]): CInt = Calls.int("sgpu_programs", handle)
  def sgpu_retained_bytes(handle: Ptr[Byte]): CLongLong = Calls.long("sgpu_retained_bytes", handle)
  def sgpu_uploaded_bytes(handle: Ptr[Byte]): CLongLong = Calls.long("sgpu_uploaded_bytes", handle)
  def sgpu_adapter(handle: Ptr[Byte]): CString = Calls.ptr[Byte]("sgpu_adapter", handle)
  def sgpu_error(handle: Ptr[Byte]): CString = Calls.ptr[Byte]("sgpu_error", handle)
  def sgpu_live(): CInt = Calls.int("sgpu_live")
