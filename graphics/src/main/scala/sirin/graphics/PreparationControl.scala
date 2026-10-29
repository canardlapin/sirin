package sirin.graphics

/** One cooperative worker request. Work counts are deterministic; cancellation
  * cannot interrupt a native call or an individual bounded library operation. */
final class PreparationControl(val maxWork: Long=64000000L,cancelled: () => Boolean=() => false):
  require(maxWork>0 && maxWork<=1000000000L,"preparation work limit")
  private val owner=Thread.currentThread()
  private var work=0L
  def usedWork: Long=work
  def checkpoint(units: Long=1): Unit =
    require(Thread.currentThread() eq owner,"preparation control owner mismatch")
    if cancelled() then throw new InterruptedException("obsolete graphics preparation")
    require(units>=0 && units<=maxWork-work,"preparation work budget")
    work+=units

final case class PreparationCacheStats(entries: Int,payloadBytes: Long,hits: Long,misses: Long)

/** Pure geometry survives a GPU generation. Source and flattened coordinates
  * count toward declared payload; Scala object overhead is excluded. */
final class PreparationCache(val maxEntries: Int=256,val maxBytes: Long=4194304L) extends AutoCloseable:
  require(maxEntries>0 && maxEntries<=256 && maxBytes>0 && maxBytes<=4194304,"preparation cache limits")
  private class Identity(val path: Path):
    override def hashCode(): Int=System.identityHashCode(path)
    override def equals(other: Any): Boolean=other match
      case x: Identity => x.path eq path
      case _ => false
  private case class Key(path: Identity,stroke: Option[StrokeStyle],linear: Affine,tolerance: Double,depth: Int)
  private case class Entry(flat: FlatPath,points: Int,required: Int,bytes: Long)
  private val owner=Thread.currentThread()
  private val entries=scala.collection.mutable.LinkedHashMap.empty[Key,Entry]
  private var bytes=0L;private var hits=0L;private var misses=0L
  private var live=true
  private def check(): Unit={require(Thread.currentThread() eq owner,"preparation cache owner mismatch");require(live,"preparation cache closed")}
  def stats: PreparationCacheStats={check();PreparationCacheStats(entries.size,bytes,hits,misses)}
  private[graphics] def geometry(path: Path,stroke: Option[StrokeStyle],transform: Affine,tolerance: Double,depth: Int,
      control: PreparationControl)(make: Affine => (FlatPath,Int,Int)): (FlatPath,Int,Int,Boolean) =
    check();control.checkpoint()
    val linear=transform.copy(tx=0,ty=0)
    // Translation arithmetic must fit well inside the existing device error.
    require(math.max(math.ulp(transform.tx),math.ulp(transform.ty))<=tolerance/32,"cached translation precision")
    val key=Key(new Identity(path),stroke,linear,tolerance,depth)
    val (entry,cached)=entries.remove(key) match
      case Some(value) => hits+=1;entries(key)=value;(value,true)
      case None =>
        misses+=1
        val (flat,points,required)=make(linear)
        control.checkpoint()
        val cost=path.segments.size.toLong*128+points.toLong*16+stroke.fold(0L)(_.dashes.size.toLong*8)
        val value=Entry(flat,points,required,cost)
        if cost<=maxBytes then
          while entries.nonEmpty && (entries.size>=maxEntries || bytes+cost>maxBytes) do
            bytes-=entries.remove(entries.head._1).get.bytes
          entries(key)=value;bytes+=cost
        (value,false)
    val moved=if transform.tx==0 && transform.ty==0 then entry.flat else
      FlatPath(entry.flat.contours.map(_.map { p =>
        control.checkpoint()
        val q=Point(p.x+transform.tx,p.y+transform.ty)
        require(math.hypot((q.x-p.x)-transform.tx,(q.y-p.y)-transform.ty)<=tolerance/32,"cached point translation precision")
        q
      }))
    (moved,entry.points,entry.required,cached)
  def prepare(drawing: Drawing,target: RenderTarget,capabilities: RenderCapabilities=RenderCapabilities(),
      limits: PreparationLimits=PreparationLimits(),control: PreparationControl=new PreparationControl): Either[PreparationFailure,PreparedDrawing] =
    check();DrawingPreparation.prepareUsing(drawing,target,capabilities,limits,control,Some(this))
  def close(): Unit={require(Thread.currentThread() eq owner,"preparation cache owner mismatch");entries.clear();bytes=0;live=false}

/** Backend-independent indication that an actual graphics generation is lost. */
final class GraphicsDeviceLost extends IllegalStateException("graphics device generation lost")
