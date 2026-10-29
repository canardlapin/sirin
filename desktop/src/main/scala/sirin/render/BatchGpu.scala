package sirin.render

import sirin.graphics.*
import sirin.desktop.ffm.Memory.*
import sirin.desktop.ffm.Calls

final case class BatchLimits(meshVertices: Int=1048576,meshEntries: Int=256,glyphEntries: Int=256,imageEntries: Int=16):
  require(meshVertices>=3 && meshVertices<=1048576 && meshEntries>0 && meshEntries<=1024 &&
    glyphEntries>0 && glyphEntries<=256 && imageEntries>0 && imageEntries<=16,"invalid batch resource limits")
final case class BatchGpuStats(programs: Long,payloadBytes: Long,meshUploadBytes: Long,textureUploadBytes: Long,
    programUploadBytes: Long,evictions: Long,pinnedEntries: Long,entries: Long,submissions: Long)

/** Worker-confined batch resources with an owned test device or an independent
  * reference to the window device. Host uploads fence at bounded submission. */
final class BatchGpu private(private[render] var handle: Ptr[Byte],val generation: DeviceGeneration,limits: BatchLimits) extends AutoCloseable:
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
  private[sirin] def compileCamera(drawing: PreparedDrawing,control: PreparationControl): BatchProgram =
    check();require(drawing.target.device eq generation,"camera generation mismatch")
    compiler.compile(drawing,control,viewportClip=false)
  private[sirin] def hasGlyphInk(font: ResolvedFont,id: Int,scale: Double): Boolean={check();compiler.hasGlyphInk(font,id,scale)}
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
              val f=alloc[CFloat](asset.rgba.length);asset.rgba.indices.foreach(i => f(i)=asset.rgba(i));pointer[Byte](f.raw)
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
        val steps=alloc[CInt](math.max(1,program.steps.size)*4)
        program.steps.zipWithIndex.foreach { (step,i) =>
          steps(i*4)=step.kind;steps(i*4+1)=step.first;steps(i*4+2)=step.count;steps(i*4+3)=java.lang.Float.floatToRawIntBits(step.alpha)
        }
        val meshIds=alloc[CInt](math.max(1,acquiredMeshes.size));acquiredMeshes.indices.foreach(i => meshIds(i)=acquiredMeshes(i))
        val assetIds=alloc[CInt](math.max(1,acquiredAssets.size));val kinds=alloc[CInt](math.max(1,acquiredAssets.size))
        acquiredAssets.indices.foreach { i => kinds(i)=acquiredAssets(i)._1;assetIds(i)=acquiredAssets(i)._2 }
        val out=alloc[Ptr[Byte]]();val t=program.drawing.target
        control.checkpoint()
        status(BatchShim.bgpu_retain(handle,data,words.length,indices,program.triangleCount,pointer[Byte](steps.raw),program.steps.size,
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
  def submitBatch(resources: Vector[BatchRetained]): Unit =
    check();require(resources.nonEmpty && resources.size<=16 && resources.forall(children),"batch submit ownership/count")
    Zone.acquire { implicit zone =>
      val pointers=alloc[Ptr[Byte]](resources.size)
      resources.zipWithIndex.foreach((r,i) => pointers(i)=r.handle)
      val code=Calls.int("sirin_desktop_batch_submit",pointers,resources.size)
      if code!=0 then
        if isLost then throw new GraphicsDeviceLost
        throw new IllegalStateException(s"batch submission status=$code: ${fromCString(BatchShim.bgpu_error(handle))}")
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
  private[render] def exportTile(resource: BatchRetained): GpuTile =
    check();require(children(resource),"batch program closed or wrong owner")
    Zone.acquire { implicit zone =>
      val out=alloc[Ptr[Byte]]()
      require(Calls.int("sirin_desktop_tile_export",resource.handle,out)==0,"GPU output budget or export failure")
      new GpuTile(!out,resource.program.drawing.target.width,resource.program.drawing.target.height)
    }
  private[render] def camera(resource: BatchRetained,prior: Option[GpuTile],parameters: Array[Float]): GpuTile =
    check();require(children(resource),"camera program owner")
    require(parameters.length==12 && parameters.forall(_.isFinite),"camera parameters")
    Zone.acquire { implicit zone =>
      val values=alloc[Float](12);parameters.indices.foreach(i => values(i)=parameters(i))
      val out=alloc[Ptr[Byte]]()
      val previous=prior.map(_.handle).getOrElse(pointer[Byte](java.lang.foreign.MemorySegment.NULL))
      val status=Calls.int("sirin_desktop_camera",resource.handle,previous,values,out)
      val native= !out
      try
        if isLost then throw new GraphicsDeviceLost
        require(status==0,s"camera submission status=$status")
        new GpuTile(native,resource.program.drawing.target.width,resource.program.drawing.target.height)
      catch case e: Throwable =>
        if native!=null then Calls.unit("sirin_desktop_tile_close",native)
        throw e
    }
  def close(): Unit =
    require(Thread.currentThread() eq owner,"batch GPU owner thread mismatch")
    if handle!=null then
      children.toVector.foreach(retire);compiler.close();BatchShim.bgpu_close(handle);handle=null

final class BatchRetained private[render](private val owner: BatchGpu,private[render] var handle: Ptr[Byte],
    private[render] var program: BatchProgram) extends AutoCloseable:
  def render(): ImageSnapshot=owner.replay(this,true).get
  /** Synchronous queue completion and resolve, excluding readback. */
  def submit(): Unit={owner.replay(this,false);()}
  def exportTile(): GpuTile = owner.exportTile(this)
  private[sirin] def camera(prior: Option[GpuTile],parameters: Array[Float]): GpuTile=owner.camera(this,prior,parameters)
  def close(): Unit=owner.retire(this)
  private[render] def clear(): Unit={handle=null;program=null}

object BatchGpu:
  def shaderSource: String =
    val stream=Option(getClass.getResourceAsStream("/batch.wgsl")).getOrElse(throw new IllegalStateException("missing batch shader"))
    try new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8) finally stream.close()
  /** Adopts the independently referenced handle prepared by the window owner. */
  def shared(handle: java.lang.foreign.MemorySegment): BatchGpu =
    val native=pointer[Byte](handle)
    require(native!=null,"missing shared renderer")
    try new BatchGpu(native,new DeviceGeneration("wgpu-native-29.0.1.1/batch-host-v1",fromCString(BatchShim.bgpu_adapter(native))),BatchLimits())
    catch case e: Throwable => {BatchShim.bgpu_close(native);throw e}
  def liveHealthRecords: Int=BatchShim.bgpu_health_live()
  def liveOwners: Int=BatchShim.bgpu_live()
  def open(limits: BatchLimits=BatchLimits()): BatchGpu = Zone.acquire { implicit zone =>
    val shader=shaderSource
    val out=alloc[Ptr[Byte]]();val error=alloc[Byte](512)
    val code=BatchShim.bgpu_open(toCString(shader),limits.meshVertices,limits.meshEntries,limits.glyphEntries,limits.imageEntries,out,error)
    if code!=0 then throw new IllegalStateException(s"batch GPU open: ${fromCString(error)}")
    val handle= !out
    require(Calls.int("sirin_desktop_batch_defer",handle)==0,"desktop defer rejected")
    try new BatchGpu(handle,new DeviceGeneration("wgpu-native-29.0.1.1/batch-v1",fromCString(BatchShim.bgpu_adapter(handle))),limits)
    catch
      case e: Throwable => BatchShim.bgpu_close(handle);throw e
  }
private[render] object BatchShim:
  def bgpu_lost(h: Ptr[Byte]): Int=Calls.int("bgpu_lost",h)
  def bgpu_lost_reason(h: Ptr[Byte]): Int=Calls.int("bgpu_lost_reason",h)
  def bgpu_health_live(): Int=Calls.int("bgpu_health_live")
  def bgpu_destroy_device(h: Ptr[Byte]): Unit=Calls.unit("bgpu_destroy_device",h)
  def bgpu_open(shader: CString,v: Int,m: Int,g: Int,i: Int,out: Ptr[Ptr[Byte]],error: Ptr[Byte]): Int=Calls.int("bgpu_open",shader,v,m,g,i,out,error)
  def bgpu_close(h: Ptr[Byte]): Unit=Calls.unit("bgpu_close",h)
  def bgpu_adapter(h: Ptr[Byte]): CString=Calls.ptr[Byte]("bgpu_adapter",h)
  def bgpu_error(h: Ptr[Byte]): CString=Calls.ptr[Byte]("bgpu_error",h)
  def bgpu_live(): Int=Calls.int("bgpu_live")
  def bgpu_stat(h: Ptr[Byte],i: Int): Long=Calls.long("bgpu_stat",h,i)
  def bgpu_mesh_acquire(h: Ptr[Byte],id: Int,data: Ptr[Float],n: Int,out: Ptr[Int]): Int=Calls.int("bgpu_mesh_acquire",h,id,if data==null then pointer[Float](java.lang.foreign.MemorySegment.NULL) else data,n,out)
  def bgpu_mesh_release(h: Ptr[Byte],id: Int): Unit=Calls.unit("bgpu_mesh_release",h,id)
  def bgpu_asset_acquire(h: Ptr[Byte],k: Int,id: Int,data: Ptr[Byte],w: Int,v: Int,out: Ptr[Int]): Int=Calls.int("bgpu_asset_acquire",h,k,id,data,w,v,out)
  def bgpu_asset_release(h: Ptr[Byte],k: Int,id: Int): Unit=Calls.unit("bgpu_asset_release",h,k,id)
  def bgpu_retain(h: Ptr[Byte],data: Ptr[Float],n: Int,indices: Ptr[Int],ni: Int,steps: Ptr[Byte],ns: Int,w: Int,v: Int,layers: Int,meshes: Ptr[Int],nm: Int,assets: Ptr[Int],kinds: Ptr[Int],na: Int,out: Ptr[Ptr[Byte]]): Int=Calls.int("bgpu_retain",h,data,n,indices,ni,steps,ns,w,v,layers,meshes,nm,assets,kinds,na,out)
  def bgpu_replay(p: Ptr[Byte],out: Ptr[Byte]): Int=Calls.int("bgpu_replay",p,out)
  def bgpu_program_close(p: Ptr[Byte]): Unit=Calls.unit("bgpu_program_close",p)
