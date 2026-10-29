package sirin.render

import sirin.graphics.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import java.nio.file.{Files,Path as FilePath}

final case class BatchLimits(meshVertices: Int=1048576,meshEntries: Int=256,glyphEntries: Int=256,imageEntries: Int=16):
  require(meshVertices>=3 && meshVertices<=1048576 && meshEntries>0 && meshEntries<=1024 &&
    glyphEntries>0 && glyphEntries<=256 && imageEntries>0 && imageEntries<=16,"invalid batch resource limits")
final case class BatchGpuStats(programs: Long,payloadBytes: Long,meshUploadBytes: Long,textureUploadBytes: Long,
    programUploadBytes: Long,evictions: Long,pinnedEntries: Long,entries: Long,submissions: Long)

/** Explicit isolated direct-wgpu device owner. The host/reference renderer is
  * unchanged. Slow work belongs on the caller's Scala-created worker. */
final class BatchGpu private(private var handle: Ptr[Byte],val generation: DeviceGeneration,limits: BatchLimits) extends AutoCloseable:
  private val owner=Thread.currentThread()
  private val compiler=new BatchCompiler
  private val children=scala.collection.mutable.HashSet.empty[BatchRetained]
  private def check(): Unit =
    require(Thread.currentThread() eq owner,"batch GPU owner thread mismatch")
    require(handle!=null,"batch GPU owner closed")
    if BatchShim.bgpu_lost(handle)!=0 then throw new GraphicsDeviceLost
  def isLost: Boolean={require(handle!=null,"batch GPU owner closed");BatchShim.bgpu_lost(handle)!=0}
  private[sirin] def destroyDevice(): Unit={check();BatchShim.bgpu_destroy_device(handle)}
  private[sirin] def lossReason: Int=BatchShim.bgpu_lost_reason(handle)
  def target(width: Int,height: Int,scale: Double): RenderTarget =
    check();require(scale.isFinite && scale>0,"invalid scale")
    RenderTarget(generation,width,height,Affine.scale(scale,scale))
  def compile(drawing: PreparedDrawing,control: PreparationControl=new PreparationControl): BatchProgram =
    check();require(drawing.target.device eq generation,"batch device generation mismatch");compiler.compile(drawing,control)
  def cacheStats: BatchCacheStats = {check();compiler.stats}
  def stats: BatchGpuStats =
    check()
    def field(i: Int)=BatchShim.bgpu_stat(handle,i)
    BatchGpuStats(field(0),field(1),field(2),field(3),field(4),field(5),field(6),field(7),field(8))
  def retain(program: BatchProgram,control: PreparationControl=new PreparationControl): BatchRetained =
    check();require(program.drawing.target.device eq generation,"batch device generation mismatch")
    require(program.meshes.size<=limits.meshEntries,"GPU mesh entry budget")
    require(program.assets.count(_.kind==1)<=limits.imageEntries && program.assets.count(_.kind==2)<=limits.glyphEntries,"GPU texture entry budget")
    Zone.acquire { implicit zone =>
      val acquiredMeshes=scala.collection.mutable.ArrayBuffer.empty[Int]
      val acquiredAssets=scala.collection.mutable.ArrayBuffer.empty[(Int,Int)]
      var transferred=false
      def status(code: Int,operation: String): Unit =
        if code!=0 then
          if isLost then throw new GraphicsDeviceLost
          throw new IllegalStateException(s"$operation status=$code: ${fromCString(BatchShim.bgpu_error(handle))}")
      try
        val offsets=program.meshes.map { mesh =>
          control.checkpoint()
          val out=alloc[CInt]()
          var code=BatchShim.bgpu_mesh_acquire(handle,mesh.token,null,mesh.count,out)
          if code==7 then
            val data=alloc[CFloat](mesh.positions.length);mesh.positions.indices.foreach(i => data(i)=mesh.positions(i))
            code=BatchShim.bgpu_mesh_acquire(handle,mesh.token,data,mesh.count,out)
          status(code,"batch mesh admission");acquiredMeshes+=mesh.token;!out
        }
        val slots=program.assets.map { asset =>
          control.checkpoint()
          val out=alloc[CInt]()
          var code=BatchShim.bgpu_asset_acquire(handle,asset.kind,asset.token,null,asset.width,asset.height,out)
          if code==7 then
            val data: Ptr[Byte]=if asset.kind==1 then
              val f=alloc[CFloat](asset.rgba.length);asset.rgba.indices.foreach(i => f(i)=asset.rgba(i));f.asInstanceOf[Ptr[Byte]]
            else
              val b=alloc[Byte](asset.gray.length);asset.gray.indices.foreach(i => b(i)=asset.gray(i));b
            code=BatchShim.bgpu_asset_acquire(handle,asset.kind,asset.token,data,asset.width,asset.height,out)
          status(code,"batch texture admission");acquiredAssets+=((asset.kind,asset.token));!out
        }
        val words=program.words.clone();program.patches.foreach((word,asset) => words(word)=slots(asset).toFloat)
        val data=alloc[CFloat](words.length);words.indices.foreach(i => data(i)=words(i))
        val indices=alloc[CUnsignedInt](math.max(2,program.triangleCount*2));var at=0
        program.uses.foreach { use =>
          for i <- 0 until program.meshes(use.mesh).count by 3 do
            indices(at)=(offsets(use.mesh)+i).toUInt;indices(at+1)=use.record.toUInt;at+=2
        }
        val steps=alloc[CStruct4[CInt,CInt,CInt,CFloat]](math.max(1,program.steps.size))
        program.steps.zipWithIndex.foreach { (step,i) =>
          steps(i)._1=step.kind;steps(i)._2=step.first;steps(i)._3=step.count;steps(i)._4=step.alpha
        }
        val meshIds=alloc[CInt](math.max(1,acquiredMeshes.size));acquiredMeshes.indices.foreach(i => meshIds(i)=acquiredMeshes(i))
        val assetIds=alloc[CInt](math.max(1,acquiredAssets.size));val kinds=alloc[CInt](math.max(1,acquiredAssets.size))
        acquiredAssets.indices.foreach { i => kinds(i)=acquiredAssets(i)._1;assetIds(i)=acquiredAssets(i)._2 }
        val out=alloc[Ptr[Byte]]();val t=program.drawing.target
        control.checkpoint()
        status(BatchShim.bgpu_retain(handle,data,words.length,indices,program.triangleCount,steps.asInstanceOf[Ptr[Byte]],program.steps.size,
          t.width,t.height,program.maxLayers,meshIds,acquiredMeshes.size,assetIds,kinds,acquiredAssets.size,out),"batch retain")
        val native= !out;transferred=true
        try
          val resource=new BatchRetained(this,native,program);children+=resource;resource
        catch
          case e: Throwable => BatchShim.bgpu_program_close(native);throw e
      finally if !transferred then
        acquiredMeshes.foreach(id => BatchShim.bgpu_mesh_release(handle,id))
        acquiredAssets.foreach((kind,id) => BatchShim.bgpu_asset_release(handle,kind,id))
    }
  private[render] def replay(resource: BatchRetained,readback: Boolean): Option[ImageSnapshot] =
    check();require(children(resource),"batch program closed or wrong owner")
    Zone.acquire { implicit zone =>
      val t=resource.program.drawing.target
      val out=if readback then alloc[Byte](t.width*t.height*4) else null
      val code=BatchShim.bgpu_replay(resource.handle,out)
      if code!=0 then
        if isLost then throw new GraphicsDeviceLost
        throw new IllegalStateException(s"batch replay status=$code: ${fromCString(BatchShim.bgpu_error(handle))}")
      if readback then Some(ImageSnapshot.copyRgba8(t.width,t.height,Array.tabulate(t.width*t.height*4)(i => out(i)))) else None
    }
  private[render] def retire(resource: BatchRetained): Unit =
    require(Thread.currentThread() eq owner,"batch GPU owner thread mismatch")
    if children.remove(resource) then {BatchShim.bgpu_program_close(resource.handle);resource.clear()}
  def close(): Unit =
    require(Thread.currentThread() eq owner,"batch GPU owner thread mismatch")
    if handle!=null then
      children.toVector.foreach(retire);compiler.close();BatchShim.bgpu_close(handle);handle=null

final class BatchRetained private[render](private val owner: BatchGpu,private[render] var handle: Ptr[Byte],
    private[render] var program: BatchProgram) extends AutoCloseable:
  def render(): ImageSnapshot=owner.replay(this,true).get
  /** Synchronous queue completion and resolve, excluding readback. */
  def submit(): Unit={owner.replay(this,false);()}
  def close(): Unit=owner.retire(this)
  private[render] def clear(): Unit={handle=null;program=null}

object BatchGpu:
  def liveHealthRecords: Int=BatchShim.bgpu_health_live()
  def liveOwners: Int=BatchShim.bgpu_live()
  def open(limits: BatchLimits=BatchLimits()): BatchGpu = Zone.acquire { implicit zone =>
    val candidates=Vector(FilePath.of("probes/render/src/main/resources/batch.wgsl"),FilePath.of("src/main/resources/batch.wgsl"))
    val shader=Files.readString(candidates.find(Files.exists(_)).getOrElse(throw new IllegalStateException("missing batch shader")))
    val out=alloc[Ptr[Byte]]();val error=alloc[Byte](512)
    val code=BatchShim.bgpu_open(toCString(shader),limits.meshVertices,limits.meshEntries,limits.glyphEntries,limits.imageEntries,out,error)
    if code!=0 then throw new IllegalStateException(s"batch GPU open: ${fromCString(error)}")
    val handle= !out
    try new BatchGpu(handle,new DeviceGeneration("wgpu-native-29.0.1.1/batch-v1",fromCString(BatchShim.bgpu_adapter(handle))),limits)
    catch
      case e: Throwable => BatchShim.bgpu_close(handle);throw e
  }
@extern private[render] object BatchShim:
  def bgpu_lost(h: Ptr[Byte]): CInt=extern
  def bgpu_lost_reason(h: Ptr[Byte]): CInt=extern
  def bgpu_health_live(): CInt=extern
  @blocking def bgpu_destroy_device(h: Ptr[Byte]): Unit=extern
  @blocking def bgpu_open(shader: CString,vertices: CInt,meshes: CInt,masks: CInt,images: CInt,out: Ptr[Ptr[Byte]],error: Ptr[Byte]): CInt=extern
  @blocking def bgpu_close(handle: Ptr[Byte]): Unit=extern
  def bgpu_adapter(handle: Ptr[Byte]): CString=extern
  def bgpu_error(handle: Ptr[Byte]): CString=extern
  def bgpu_live(): CInt=extern
  @blocking def bgpu_mesh_acquire(handle: Ptr[Byte],id: CInt,data: Ptr[CFloat],count: CInt,out: Ptr[CInt]): CInt=extern
  def bgpu_mesh_release(handle: Ptr[Byte],id: CInt): Unit=extern
  @blocking def bgpu_asset_acquire(handle: Ptr[Byte],kind: CInt,id: CInt,data: Ptr[Byte],w: CInt,h: CInt,out: Ptr[CInt]): CInt=extern
  def bgpu_asset_release(handle: Ptr[Byte],kind: CInt,id: CInt): Unit=extern
  def bgpu_stat(handle: Ptr[Byte],field: CInt): CLongLong=extern
  @blocking def bgpu_retain(handle: Ptr[Byte],data: Ptr[CFloat],ndata: CInt,indices: Ptr[CUnsignedInt],nindices: CInt,
    steps: Ptr[Byte],nsteps: CInt,w: CInt,h: CInt,layers: CInt,meshes: Ptr[CInt],nmeshes: CInt,
    assets: Ptr[CInt],kinds: Ptr[CInt],nassets: CInt,out: Ptr[Ptr[Byte]]): CInt=extern
  @blocking def bgpu_replay(program: Ptr[Byte],out: Ptr[Byte]): CInt=extern
  @blocking def bgpu_program_close(program: Ptr[Byte]): Unit=extern
