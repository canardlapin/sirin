package sirin.render

import sirin.graphics.*
import sirin.graphics.text.NativeFont
import scala.collection.mutable.ArrayBuffer

final class PixelProgram private[render] (private[render] val words: Array[Float], val drawing: PreparedDrawing,
    val maskBytes: Long, val prepareNanos: Long, private[render] val assets: Vector[PixelAsset],
    private[render] val assetPatches: Vector[(Int,Int)]):
  def uploadBytes: Long = words.length.toLong*4
  def readbackBytes: Long = ((drawing.target.width*4+255)& ~255).toLong*drawing.target.height

object PixelProgram:
  private[render] val linearBytes=Array.tabulate(256) { i =>
    val x=i/255.0
    if x<=0.04045 then x/12.92 else math.pow((x+0.055)/1.055,2.4)
  }
  /** Primitive storage avoids boxing each expanded image component. */
  private final class Words(initial: Int,limit: Int):
    private var values=new Array[Float](initial)
    private var length=initial
    def size: Int=length
    def update(i: Int,value: Float): Unit=values(i)=value
    def ++=(items: IterableOnce[Float]): Unit=items.iterator.foreach(add)
    def ++=(items: Array[Float]): Unit=
      ensure(items.length);System.arraycopy(items,0,values,length,items.length);length+=items.length
    private def ensure(count: Int): Unit=
      require(length.toLong+count<=limit,"program storage budget")
      if length+count>values.length then values=java.util.Arrays.copyOf(values,math.min(limit,math.max(length+count,math.max(64,values.length*2))))
    private def add(value: Float): Unit={ensure(1);values(length)=value;length+=1}
    def toArray: Array[Float]=java.util.Arrays.copyOf(values,length)
  def glyphScale(m: Affine): Double =
    val s=Vector(m.a,m.b,m.c,m.d).map(math.abs).max
    if s==0 then 0 else
      val a=m.a/s;val b=m.b/s;val c=m.c/s;val d=m.d/s
      val tr=a*a+b*b+c*c+d*d;val det=a*d-b*c
      s*math.sqrt((tr+math.sqrt(math.max(0,tr*tr-4*det*det)))/2)
  private enum Op:
    case Fill(path: FlatPath,color: PreparedPaint,rule: FillRule,clips: Vector[PreparedClip])
    case Quad(asset: PixelAsset,source: Rect,dest: Rect,
      sampling: Sampling,alpha: Double,matrix: Affine,clips: Vector[PreparedClip],paint: Option[(PreparedPaint,Point)])
    case Begin(alpha: Double)
    case End
  def compile(drawing: PreparedDrawing,maxOps: Int = 1024,maxVec4: Int = 2097152,rasterize: Option[(ResolvedFont,Int,Double,Int) => GlyphRaster] = None): PixelProgram =
    compileUsing(drawing,maxOps,maxVec4,None,rasterize)
  private[render] def cached(drawing: PreparedDrawing,cache: PixelResourceCache): PixelProgram =
    compileUsing(drawing,1024,2097152,Some(cache),None)
  private def compileUsing(drawing: PreparedDrawing,maxOps: Int,maxVec4: Int,resources: Option[PixelResourceCache],rasterize: Option[(ResolvedFont,Int,Double,Int) => GlyphRaster]): PixelProgram =
    val began=System.nanoTime()
    require(maxOps>0 && maxOps<=4096 && maxVec4>0 && maxVec4<=4194304,"invalid program budgets")
    val cache=resources.getOrElse(new PixelResourceCache(rasterize=rasterize))
    val seenMasks=scala.collection.mutable.HashSet.empty[PixelAsset]
    var maskBytes=0L
    var sourcePixels=0L
    def claimPixels(w: Int,h: Int): Unit =
      sourcePixels+=w.toLong*h
      require(sourcePixels<=maxVec4,"expanded image pixel budget")
    val ops=ArrayBuffer.empty[Op]
    var sampleWork=0L
    def edgeCount(p: FlatPath): Long = p.contours.filter(_.size>1).map(_.size.toLong).sum
    def work(op: Op): Long = op match
      case Op.Fill(p,paint,_,clips) =>
        val stops=paint match
          case PreparedPaint.Solid(_) => 0
          case PreparedPaint.LinearGradient(_,_,_,s,_) => s.size
        1+edgeCount(p)+stops+clips.map(c => edgeCount(c.path)).sum
      case Op.Quad(_,_,_,_,_,_,clips,paint) =>
        val stops=paint.toVector.flatMap(_._1 match
          case PreparedPaint.Solid(_) => Vector.empty
          case PreparedPaint.LinearGradient(_,_,_,s,_) => s)
        8+stops.size+clips.map(c => edgeCount(c.path)).sum
      case _ => 1
    def append(op: Op): Unit =
      require(ops.size<maxOps,"expanded operation budget")
      sampleWork+=work(op)*drawing.target.width*drawing.target.height*16
      require(sampleWork<=500000000L,"per-sample work budget")
      ops+=op
    try
      drawing.commands.foreach {
        case PreparedCommand.Fill(p,c,r,clips) => append(Op.Fill(p,c,r,clips))
        case PreparedCommand.BeginLayer(a) => append(Op.Begin(a))
        case PreparedCommand.EndLayer => append(Op.End)
        case PreparedCommand.Image(src,dest,sampling,alpha,m,clips) =>
          claimPixels(src.image.width,src.image.height)
          append(Op.Quad(cache.image(src.image),src.source,dest,sampling,alpha,m,clips,None))
        case PreparedCommand.Glyphs(run,origin,color,m,clips) =>
          val scale=glyphScale(m)
          if scale>0 && m.inverse.nonEmpty then
            run.glyphs.foreach { glyph =>
              val (mask,asset)=cache.glyph(run.font,glyph.id,scale,maxVec4)
              if seenMasks.add(asset) then maskBytes+=mask.byteSize
              require(maskBytes<=maxVec4.toLong,"mask byte budget")
              if mask.width>0 && mask.height>0 then
                claimPixels(mask.width,mask.height)
                val s=mask.deviceScale
                append(Op.Quad(asset,Rect(0,0,mask.width,mask.height),
                  Rect(origin.x+glyph.position.x+mask.left/s,origin.y+glyph.position.y-mask.top/s,mask.width/s,mask.height/s),
                  Sampling.Linear,1,m,clips,Some((color,m(Point(0,0))))))
            }
      }
      val data=new Words((1+ops.size*8)*4,maxVec4*4)
      def number(x: Double): Float = { val f=x.toFloat;require(f.isFinite,"nonfinite GPU value");f }
      def vector(v: Double*): Int =
        require(v.size==4 && data.size/4<maxVec4,"program storage budget")
        val index=data.size/4;data++=v.map(number);index
      def put(i: Int,v: Double*): Unit = v.zipWithIndex.foreach((x,j) => data(i*4+j)=number(x))
      def edges(path: FlatPath): (Int,Int) =
        val start=data.size/4
        path.contours.filter(_.size>1).foreach { contour =>
          contour.zip(contour.drop(1):+contour.head).foreach { (a,b) =>
            val values=Vector(a.x,a.y,b.x,b.y)
            require(values.forall(x => math.abs(number(x).toDouble-x)<=drawing.target.tolerance/8),"GPU coordinate precision")
            require(values.forall(x => math.abs(x)<=1048576),"GPU coordinate range")
            vector(a.x,a.y,b.x,b.y)
          }
        }
        (start,data.size/4-start)
      def clips(cs: Vector[PreparedClip]): (Int,Int) =
        val records=cs.map(c => {val (a,b)=edges(c.path);(a,b,c.rule.ordinal)})
        val start=data.size/4;records.foreach((a,b,c) => vector(a,b,c,0));(start,cs.size)
      def linear(x: Double): Double = if x<=0.04045 then x/12.92 else math.pow((x+0.055)/1.055,2.4)
      val assets=ArrayBuffer.empty[PixelAsset]
      val patches=ArrayBuffer.empty[(Int,Int)]
      def brush(base: Int,paint: PreparedPaint,anchor: Point): Unit = paint match
        case PreparedPaint.Solid(value) =>
          put(base+1,linear(value.red)*value.alpha,linear(value.green)*value.alpha,linear(value.blue)*value.alpha,value.alpha)
        case PreparedPaint.LinearGradient(x,y,offset,stops,extend) =>
          val corners=Vector(Point(0,0),Point(drawing.target.width,0),Point(0,drawing.target.height),Point(drawing.target.width,drawing.target.height))
          require(corners.forall { p =>
            val exact=(p.x-anchor.x)*x+(p.y-anchor.y)*y+offset
            require(exact.isFinite && math.abs(exact)<=65536,"gradient parameter range")
            val packed=(p.x-number(anchor.x))*number(x)+(p.y-number(anchor.y))*number(y)+number(offset)
            math.abs(exact-packed)<=1e-6
          },"gradient projection precision")
          val start=data.size/4
          stops.foreach { stop =>
            val value=stop.color
            vector(stop.offset,0,0,0)
            vector(linear(value.red)*value.alpha,linear(value.green)*value.alpha,linear(value.blue)*value.alpha,value.alpha)
          }
          val record=vector(x,y,offset,extend.ordinal)
          vector(start,stops.size,anchor.x,anchor.y)
          data((base+7)*4+1)=number(record+1)
      put(0,drawing.target.width,drawing.target.height,ops.size,0)
      ops.zipWithIndex.foreach { (op,i) =>
        val base=1+i*8
        op match
          case Op.Begin(a) => put(base,3,0,0,0);put(base+1,0,0,0,a)
          case Op.End => put(base,4,0,0,0)
          case Op.Fill(path,c,rule,cs) =>
            val (start,count)=edges(path);val (clipStart,clipCount)=clips(cs)
            put(base,1,start,count,rule.ordinal)
            brush(base,c,path.contours.iterator.flatMap(_.headOption).nextOption().getOrElse(Point(0,0)))
            put(base+6,0,0,0,clipStart);data((base+7)*4)=number(clipCount)
          case Op.Quad(asset,src,dest,sampling,alpha,m,cs,paint) =>
            val w=asset.width;val h=asset.height
            val (clipStart,clipCount)=clips(cs)
            Vector(src.x,src.y,src.width,src.height,dest.x,dest.y,dest.width,dest.height).foreach { x =>
              require(math.abs(number(x).toDouble-x)<=drawing.target.tolerance/8,"texture rectangle precision")
            }
            m.inverse.filter(_ => dest.width>0 && dest.height>0).foreach { inv =>
              val approximate=Affine(inv.a.toFloat,inv.b.toFloat,inv.c.toFloat,inv.d.toFloat,inv.tx.toFloat,inv.ty.toFloat)
              val corners=Vector(Point(0,0),Point(drawing.target.width,0),Point(0,drawing.target.height),Point(drawing.target.width,drawing.target.height))
              require(corners.forall(p => math.hypot(inv(p).x-approximate(p).x,inv(p).y-approximate(p).y)<=1.0/128),"inverse texture precision")
              val start=if resources.nonEmpty then
                val index=assets.indexOf(asset)
                val slot=if index>=0 then index else {assets+=asset;assets.size-1}
                patches+=(((base+6)*4,slot))
                0
              else
                val start=data.size/4
                data++=asset.words
                start
              put(base,2,0,0,0);put(base+2,inv.a,inv.c,inv.tx,alpha);put(base+3,inv.b,inv.d,inv.ty,sampling.ordinal)
              put(base+4,dest.x,dest.y,dest.width,dest.height);put(base+5,src.x,src.y,src.width,src.height)
              put(base+6,start,w,h,clipStart);put(base+7,clipCount,0,if resources.nonEmpty then 1 else 0,if paint.nonEmpty then 1 else 0)
              paint.foreach((value,anchor) => brush(base,value,anchor))
            }
      }
      require(data.size/4<=maxVec4,"program storage budget")
      new PixelProgram(data.toArray,drawing,maskBytes,System.nanoTime()-began,assets.toVector,patches.toVector)
    finally if resources.isEmpty then cache.close()

private[render] final class PixelAsset(val width: Int,val height: Int,val words: Array[Float]):
  val token: Int = PixelAsset.allocate()
  def byteSize: Long=words.length.toLong*4
private[render] object PixelAsset:
  private val next=new java.util.concurrent.atomic.AtomicInteger(1)
  @scala.annotation.tailrec def allocate(): Int =
    val value=next.get()
    require(value>0 && value<Int.MaxValue,"pixel asset identity exhausted")
    if next.compareAndSet(value,value+1) then value else allocate()

final case class PixelCacheStats(entries: Int,bytes: Long,hits: Long,misses: Long,rasterizations: Long,decodedImages: Long)

/** Explicit, confined LRU for immutable decoded payloads and exact static glyph
  * coverage. Source font/image snapshots and ordinary object overhead are not
  * included in the decoded-payload byte count. No live native face is cached.
  */
private[render] final class PixelResourceCache(val maxBytes: Long = 4194304,val maxEntries: Int = 128,rasterize: Option[(ResolvedFont,Int,Double,Int) => GlyphRaster] = None) extends AutoCloseable:
  require(maxBytes>0 && maxBytes<=67108864 && maxEntries>0 && maxEntries<=1024,"invalid pixel cache limits")
  private val owner=Thread.currentThread()
  private var open=true
  private case class GlyphKey(data: FontData,face: Int,size: Double,id: Int,scale: Double,
    metrics: FontMetrics,shaper: String,rasterizer: String,units: Int,count: Int)
  private case class Entry(asset: PixelAsset,mask: Option[GlyphRaster]):
    def bytes: Long=asset.byteSize+mask.map(_.byteSize.toLong).getOrElse(0L)
  private val entries=scala.collection.mutable.LinkedHashMap.empty[Any,Entry]
  private var used=0L;private var hits=0L;private var misses=0L;private var rasters=0L;private var images=0L
  private def check(): Unit =
    require(Thread.currentThread() eq owner,"pixel cache thread mismatch")
    require(open,"pixel cache is closed")
  def stats: PixelCacheStats = {check();PixelCacheStats(entries.size,used,hits,misses,rasters,images)}
  private def obtain(key: Any)(make: => Entry): Entry =
    check()
    entries.remove(key) match
      case Some(entry) => hits+=1;entries(key)=entry;entry
      case None =>
        misses+=1
        val entry=make
        if entry.bytes<=maxBytes then
          while entries.nonEmpty && (entries.size>=maxEntries || used+entry.bytes>maxBytes) do
            val old=entries.remove(entries.head._1).get;used-=old.bytes
          entries(key)=entry;used+=entry.bytes
        entry
  private[render] def image(image: ImageSnapshot): PixelAsset = obtain(image) {
    require(image.width.toLong*image.height*16<=67108864,"decoded image budget")
    val bytes=image.pixelsCopy
    val words=new Array[Float](bytes.length)
    var i=0
    while i<bytes.length do
      val alpha=(bytes(i+3)&255)/255.0
      words(i)=(PixelProgram.linearBytes(bytes(i)&255)*alpha).toFloat
      words(i+1)=(PixelProgram.linearBytes(bytes(i+1)&255)*alpha).toFloat
      words(i+2)=(PixelProgram.linearBytes(bytes(i+2)&255)*alpha).toFloat
      words(i+3)=alpha.toFloat;i+=4
    images+=1
    Entry(new PixelAsset(image.width,image.height,words),None)
  }.asset
  private[render] def glyph(font: ResolvedFont,id: Int,scale: Double,maxPixels: Int): (GlyphRaster,PixelAsset) =
    val key=GlyphKey(font.data,font.faceIndex,font.size,id,scale,font.metrics,font.shaperVersion,font.rasterizerVersion,font.unitsPerEm,font.glyphCount)
    val entry=obtain(key) {
      val mask=rasterize match
        case Some(provider) => provider(font,id,scale,maxPixels)
        case None =>
          val face=NativeFont.open(font.data,font.size,font.faceIndex)
          try
            require(face.font.metrics==font.metrics && face.font.shaperVersion==font.shaperVersion &&
              face.font.rasterizerVersion==font.rasterizerVersion,"resolved font mismatch")
            face.rasterize(id,scale,maxPixels=maxPixels)
          finally face.close()
      rasters+=1
      val coverage=mask.coverageCopy
      val words=new Array[Float](coverage.length*4)
      coverage.indices.foreach(i => (0 until 4).foreach(c => words(i*4+c)=(coverage(i)&255)/255f))
      Entry(new PixelAsset(mask.width,mask.height,words),Some(mask))
    }
    require(entry.mask.get.byteSize<=maxPixels,"mask byte budget")
    (entry.mask.get,entry.asset)
  def close(): Unit =
    require(Thread.currentThread() eq owner,"pixel cache thread mismatch")
    entries.clear();used=0;open=false
