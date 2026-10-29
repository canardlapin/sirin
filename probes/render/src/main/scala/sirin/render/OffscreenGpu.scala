package sirin.render

import sirin.graphics.*
import scala.scalanative.unsafe.*
import java.nio.file.{Files,Path as FilePath}

final class OffscreenGpu private(private var handle: Ptr[Byte],val generation: DeviceGeneration,limits: SharedResourceLimits) extends AutoCloseable:
  private val owner=Thread.currentThread()
  private val cache=new PixelResourceCache(limits.cpuBytes,limits.cpuEntries)
  private val retained=scala.collection.mutable.HashSet.empty[RetainedProgram]
  private def check(): Unit =
    require(Thread.currentThread() eq owner,"GPU owner thread mismatch")
    require(handle!=null,"GPU owner is closed")
  def target(width: Int,height: Int,scale: Double): RenderTarget =
    check();require(scale.isFinite && scale>0,"invalid device scale")
    RenderTarget(generation,width,height,Affine.scale(scale,scale))
  def compile(drawing: PreparedDrawing): PixelProgram =
    check();require(drawing.target.device eq generation,"prepared drawing device generation mismatch")
    PixelProgram.cached(drawing,cache)
  def pixelCacheStats: PixelCacheStats = {check();cache.stats}
  def assetStats: GpuAssetStats =
    check()
    GpuAssetStats(GpuShim.sgpu_asset_stat(handle,0).toInt,GpuShim.sgpu_asset_stat(handle,1).toInt,
      GpuShim.sgpu_asset_stat(handle,2),GpuShim.sgpu_asset_stat(handle,3),GpuShim.sgpu_asset_stat(handle,4),GpuShim.sgpu_asset_stat(handle,5))
  def render(program: PixelProgram,thorvg: Boolean = false): ImageSnapshot =
    check();require(program.drawing.target.device eq generation,"prepared drawing device generation mismatch")
    val t=program.drawing.target
    require(t.width.toLong*t.height<=1048576,"pixel budget")
    if program.assets.nonEmpty then
      require(!thorvg,"shared assets are unsupported by the ThorVG comparison")
      val resource=retain(program)
      try return resource.render() finally resource.close()
    Zone.acquire { implicit zone =>
      val data=alloc[CFloat](program.words.length);program.words.indices.foreach(i => data(i)=program.words(i))
      val out=alloc[Byte](t.width*t.height*4)
      val status=GpuShim.sgpu_render(handle,data,program.words.length,t.width,t.height,if thorvg then 1 else 0,out)
      if status!=0 then throw new IllegalStateException(s"render status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
      ImageSnapshot.copyRgba8(t.width,t.height,Array.tabulate(t.width*t.height*4)(i => out(i)))
    }
  def resourceStats: GpuResourceStats =
    check()
    GpuResourceStats(GpuShim.sgpu_programs(handle),GpuShim.sgpu_retained_bytes(handle),GpuShim.sgpu_uploaded_bytes(handle))
  def retain(program: PixelProgram): RetainedProgram =
    check();require(program.drawing.target.device eq generation,"prepared drawing device generation mismatch")
    require(program.assets.size<=limits.gpuEntries,"shared asset entry budget")
    val t=program.drawing.target
    Zone.acquire { implicit zone =>
      val acquired=scala.collection.mutable.ArrayBuffer.empty[Int]
      var transferred=false
      try
        val offsets=program.assets.map { asset =>
          val offset=alloc[CInt]()
          var status=GpuShim.sgpu_asset_acquire(handle,asset.token,null,asset.words.length,offset)
          if status==7 then
            val pixels=alloc[CFloat](asset.words.length);asset.words.indices.foreach(i => pixels(i)=asset.words(i))
            status=GpuShim.sgpu_asset_acquire(handle,asset.token,pixels,asset.words.length,offset)
          if status!=0 then throw new IllegalStateException(s"asset admission status=$status")
          acquired+=asset.token
          !offset
        }
        val words=if program.assets.isEmpty then program.words else program.words.clone()
        program.assetPatches.foreach((word,asset) => words(word)=offsets(asset).toFloat)
        val data=alloc[CFloat](words.length);words.indices.foreach(i => data(i)=words(i))
        val ids=alloc[CInt](math.max(1,acquired.size));acquired.indices.foreach(i => ids(i)=acquired(i))
        val out=alloc[Ptr[Byte]]()
        val status=GpuShim.sgpu_retain_assets(handle,data,words.length,t.width,t.height,ids,acquired.size,out)
        if status!=0 then throw new IllegalStateException(s"retain status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
        val native= !out
        transferred=true
        try
          val resource=new RetainedProgram(this,native,program)
          retained+=resource
          resource
        catch
          case error: Throwable => GpuShim.sgpu_program_close(native);throw error
      finally if !transferred then acquired.foreach(id => GpuShim.sgpu_asset_release(handle,id))
    }
  private[render] def replay(resource: RetainedProgram): ImageSnapshot =
    check();require(retained(resource),"retained program is closed or belongs to another owner")
    val t=resource.program.drawing.target
    Zone.acquire { implicit zone =>
      val out=alloc[Byte](t.width*t.height*4)
      val status=GpuShim.sgpu_replay(resource.handle,out)
      if status!=0 then throw new IllegalStateException(s"replay status=$status: ${fromCString(GpuShim.sgpu_error(handle))}")
      ImageSnapshot.copyRgba8(t.width,t.height,Array.tabulate(t.width*t.height*4)(i => out(i)))
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
      cache.close()
      GpuShim.sgpu_close(handle);handle=null

/** Requested storage-buffer + output-texture payload, excluding driver overhead,
  * staging, pipeline and bind-group allocations. Uploads count direct storage.
  */
final case class GpuResourceStats(programs: Int, retainedBytes: Long, uploadedBytes: Long)

final class RetainedProgram private[render](private val owner: OffscreenGpu,
    private[render] var handle: Ptr[Byte], private[render] var program: PixelProgram) extends AutoCloseable:
  def render(): ImageSnapshot = owner.replay(this)
  def close(): Unit = owner.retire(this)
  private[render] def clear(): Unit =
    handle=null
    program=null

final case class SharedResourceLimits(gpuVec4: Int = 262144,gpuEntries: Int = 128,
    cpuBytes: Long = 4194304,cpuEntries: Int = 128):
  require(gpuVec4>0 && gpuVec4<=1048576 && gpuEntries>0 && gpuEntries<=128 &&
    cpuBytes>0 && cpuBytes<=67108864 && cpuEntries>0 && cpuEntries<=1024,"invalid shared resource limits")
final case class GpuAssetStats(entries: Int,pinned: Int,capacityBytes: Long,usedBytes: Long,uploadedBytes: Long,evictions: Long)

object OffscreenGpu:
  def liveOwners: Int = GpuShim.sgpu_live()
  def open(limits: SharedResourceLimits = SharedResourceLimits()): OffscreenGpu = Zone.acquire { implicit zone =>
    val paths=Vector(FilePath.of("probes/render/src/main/resources/raster.wgsl"),FilePath.of("src/main/resources/raster.wgsl"))
    val shader=Files.readString(paths.find(Files.exists(_)).getOrElse(throw new IllegalStateException("missing shader")))
    val out=alloc[Ptr[Byte]]();val error=alloc[Byte](512)
    val status=GpuShim.sgpu_open(toCString(shader),out,error)
    if status!=0 then throw new IllegalStateException(s"GPU open: ${fromCString(error)}")
    val handle= !out
    try
      require(GpuShim.sgpu_asset_configure(handle,limits.gpuVec4,limits.gpuEntries)==0,"shared arena configuration rejected")
      new OffscreenGpu(handle,new DeviceGeneration("wgpu-native-29.0.1.1/reference-v2",fromCString(GpuShim.sgpu_adapter(handle))),limits)
    catch
      case error: Throwable => GpuShim.sgpu_close(handle);throw error
  }
@extern private[render] object GpuShim:
  @blocking def sgpu_open(shader: CString,out: Ptr[Ptr[Byte]],error: Ptr[Byte]): CInt = extern
  @blocking def sgpu_close(handle: Ptr[Byte]): Unit = extern
  @blocking def sgpu_render(handle: Ptr[Byte],data: Ptr[CFloat],count: CInt,width: CInt,height: CInt,thorvg: CInt,out: Ptr[Byte]): CInt = extern
  @blocking def sgpu_retain(handle: Ptr[Byte],data: Ptr[CFloat],count: CInt,width: CInt,height: CInt,out: Ptr[Ptr[Byte]]): CInt = extern
  @blocking def sgpu_replay(program: Ptr[Byte],out: Ptr[Byte]): CInt = extern
  @blocking def sgpu_program_close(program: Ptr[Byte]): Unit = extern
  def sgpu_asset_configure(handle: Ptr[Byte],capacity: CInt,entries: CInt): CInt = extern
  @blocking def sgpu_asset_acquire(handle: Ptr[Byte],id: CInt,data: Ptr[CFloat],count: CInt,offset: Ptr[CInt]): CInt = extern
  def sgpu_asset_release(handle: Ptr[Byte],id: CInt): Unit = extern
  def sgpu_asset_stat(handle: Ptr[Byte],field: CInt): CLongLong = extern
  @blocking def sgpu_retain_assets(handle: Ptr[Byte],data: Ptr[CFloat],count: CInt,width: CInt,height: CInt,ids: Ptr[CInt],assets: CInt,out: Ptr[Ptr[Byte]]): CInt = extern
  def sgpu_programs(handle: Ptr[Byte]): CInt = extern
  def sgpu_retained_bytes(handle: Ptr[Byte]): CLongLong = extern
  def sgpu_uploaded_bytes(handle: Ptr[Byte]): CLongLong = extern
  def sgpu_adapter(handle: Ptr[Byte]): CString = extern
  def sgpu_error(handle: Ptr[Byte]): CString = extern
  def sgpu_live(): CInt = extern
