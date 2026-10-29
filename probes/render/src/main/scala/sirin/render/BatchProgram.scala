package sirin.render

import sirin.graphics.*
import sirin.graphics.text.NativeFont
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Probe-private immutable mesh. Vertices describe a disjoint triangle union,
  * including intersections with every clip, in device coordinates. */
private[render] final class BatchMesh(val positions: Array[Float]):
  val token=BatchIdentity.next()
  def count: Int=positions.length/2
  def bytes: Long=positions.length.toLong*4
private[render] object BatchIdentity:
  private val counter=new java.util.concurrent.atomic.AtomicInteger(1)
  @scala.annotation.tailrec def next(): Int =
    val n=counter.get();require(n>0 && n<Int.MaxValue,"batch identity exhausted")
    if counter.compareAndSet(n,n+1) then n else next()

/** Scanbeam decomposition of the winding intersection. Crossings split slabs;
  * inside each slab all edge orders are fixed and the result is trapezoids.
  * No triangulation of each overlapping stroke ribbon separately. */
private[render] object WindingMesh:
  final class Budget:
    var pairs=0L
    var triangles=0
  private case class Edge(a: Point,b: Point,group: Int,weight: Int=1):
    val low=math.min(a.y,b.y);val high=math.max(a.y,b.y)
    val delta=weight*(if b.y>a.y then 1 else -1)
    def x(y: Double): Double=a.x+(b.x-a.x)*((y-a.y)/(b.y-a.y))
  def tessellate(path: FlatPath,rule: FillRule,clips: Vector[PreparedClip],
      target: RenderTarget,budget: Budget): BatchMesh =
    val viewport=FlatPath(Vector(Vector(Point(0,0),Point(target.width,0),Point(target.width,target.height),Point(0,target.height))))
    val groups=Vector((path,rule))++clips.map(c => (c.path,c.rule)):+((viewport,FillRule.NonZero))
    val edges=ArrayBuffer.empty[Edge]
    val ys=ArrayBuffer.empty[Double]
    var inputEdges=0
    groups.zipWithIndex.foreach { case ((p,_),g) => p.contours.filter(_.size>1).foreach { ps =>
      ps.zip(ps.drop(1):+ps.head).foreach { (a,b) =>
        inputEdges+=1;require(inputEdges<=4096,"mesh edge budget")
        Vector(a.x,a.y,b.x,b.y).foreach(v => require(v.isFinite && math.abs(v)<=1048576,"mesh coordinate range"))
        ys+=a.y;ys+=b.y
        if a.y!=b.y then edges+=Edge(a,b,g)
      }
    }}
    require(edges.size<=4096,"mesh edge budget")
    // Internal triangle diagonals and adjacent ribbon interfaces have exact
    // opposite directed edges. Cancel them before generating crossing events;
    // this is an exact winding identity, including even/odd multiplicity.
    val net=mutable.LinkedHashMap.empty[(Point,Point,Int),Int]
    edges.foreach { e =>
      val (a,b,sign)=if e.a.y<e.b.y then (e.a,e.b,1) else (e.b,e.a,-1)
      val key=(a,b,e.group);net(key)=net.getOrElse(key,0)+sign
    }
    edges.clear()
    net.foreach { case ((a,b,g),weight) => if weight!=0 then edges+=Edge(a,b,g,weight) }
    budget.pairs+=edges.size.toLong*(edges.size-1)/2
    require(budget.pairs<=16000000,"mesh intersection work budget")
    var i=0
    while i<edges.size do
      val a=edges(i);var j=i+1
      while j<edges.size do
        val b=edges(j)
        val lo=math.max(a.low,b.low);val hi=math.min(a.high,b.high)
        if lo<hi then
          val ax=a.b.x-a.a.x;val ay=a.b.y-a.a.y
          val bx=b.b.x-b.a.x;val by=b.b.y-b.a.y
          val den=ax*by-ay*bx
          if den!=0 then
            val t=((b.a.x-a.a.x)*by-(b.a.y-a.a.y)*bx)/den
            val y=a.a.y+t*ay
            if y>lo && y<hi then ys+=y
        j+=1
      i+=1
    val levels=ys.filter(y => y>=0 && y<=target.height).distinct.sorted
    val vertices=ArrayBuffer.empty[Float]
    def tri(a: Point,b: Point,c: Point): Unit =
      val area=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
      if area!=0 then
        budget.triangles+=1;require(budget.triangles<=262144,"mesh triangle budget")
        Vector(a.x,a.y,b.x,b.y,c.x,c.y).foreach { x =>
          val f=x.toFloat
          require(f.isFinite && math.abs(f.toDouble-x)<=target.tolerance/8,"mesh float precision")
          vertices+=f
        }
    val strips=mutable.LinkedHashMap.empty[(Edge,Edge),(Double,Double)]
    def emit(pair: (Edge,Edge),bottom: Double,top: Double): Unit =
      val (left,right)=pair
      val a=Point(left.x(bottom),bottom);val b=Point(right.x(bottom),bottom)
      val c=Point(right.x(top),top);val d=Point(left.x(top),top)
      tri(a,b,c);tri(a,c,d)
    levels.zip(levels.drop(1)).foreach { (bottom,top) =>
      val present=mutable.HashSet.empty[(Edge,Edge)]
      val mid=bottom/2+top/2
      val active=edges.filter(e => e.low<mid && e.high>mid).sortBy(_.x(mid))
      val winding=Array.fill(groups.size)(0)
      var k=0;var admitted=false;var left: Option[Edge]=None
      while k<active.size do
        val edge=active(k);winding(edge.group)+=edge.delta
        val next=groups.indices.forall(g => if groups(g)._2==FillRule.EvenOdd then (math.abs(winding(g))%2)==1 else winding(g)!=0)
        if !admitted && next then left=Some(edge)
        if admitted && !next then
          val start=left.get
          if start.x(mid)<edge.x(mid) then
            val pair=(start,edge)
            strips.get(pair) match
              case Some((from,until)) if until==bottom => strips(pair)=(from,top)
              case Some((from,until)) => emit(pair,from,until);strips(pair)=(bottom,top)
              case None => strips(pair)=(bottom,top)
            present+=pair
          left=None
        admitted=next;k+=1
      strips.keysIterator.filterNot(present).toVector.foreach { pair =>
        val (from,until)=strips.remove(pair).get;emit(pair,from,until)
      }
    }
    strips.foreach { case (pair,(from,until)) => emit(pair,from,until) }
    new BatchMesh(vertices.toArray)

private[render] final class BatchAsset(val kind: Int,val width: Int,val height: Int,
    val rgba: Array[Float],val gray: Array[Byte]):
  val token=BatchIdentity.next()
  def bytes: Long=rgba.length.toLong*4+gray.length
private[render] case class BatchStep(kind: Int,first: Int,count: Int,alpha: Float)
private[render] case class MeshUse(mesh: Int,record: Int)
final class BatchProgram private[render](val drawing: PreparedDrawing,
    private[render] val words: Array[Float],private[render] val meshes: Vector[BatchMesh],
    private[render] val uses: Vector[MeshUse],private[render] val assets: Vector[BatchAsset],
    private[render] val patches: Vector[(Int,Int)],private[render] val steps: Vector[BatchStep],val prepareNanos: Long):
  val vertexCount: Int=uses.map(u => meshes(u.mesh).count).sum
  val drawBatches: Int=steps.count(_.kind==0)
  val triangleCount: Int=vertexCount/3
  val maxLayers: Int =
    var depth=0;var max=0
    steps.foreach { s => if s.kind==1 then {depth+=1;max=math.max(max,depth)} else if s.kind==2 then depth-=1 }
    max

final case class BatchCacheStats(meshEntries: Int,meshBytes: Long,assetEntries: Int,assetBytes: Long,
    meshHits: Long,assetHits: Long,rasterizations: Long,decodedImages: Long)

/** Confined CPU preparation cache. Payload bounds exclude source recordings,
  * ordinary object overhead and caller-held compiled programs. */
private[render] final class BatchCompiler extends AutoCloseable:
  private val owner=Thread.currentThread()
  private var open=true
  private case class MeshKey(path: FlatPath,rule: FillRule,clips: Vector[PreparedClip],width: Int,height: Int,tolerance: Double)
  private case class GlyphKey(font: FontData,face: Int,size: Double,id: Int,scale: Double,
      metrics: FontMetrics,shaper: String,rasterizer: String,units: Int,glyphs: Int)
  private val geometry=mutable.LinkedHashMap.empty[MeshKey,BatchMesh]
  private val pixels=mutable.LinkedHashMap.empty[Any,(BatchAsset,Option[GlyphRaster])]
  private var meshBytes=0L;private var assetBytes=0L
  private var mh=0L;private var ah=0L;private var rasters=0L;private var images=0L
  private def check(): Unit = {require(Thread.currentThread() eq owner,"batch compiler thread mismatch");require(open,"batch compiler closed")}
  def stats: BatchCacheStats = {check();BatchCacheStats(geometry.size,meshBytes,pixels.size,assetBytes,mh,ah,rasters,images)}
  private def linear(x: Double): Double=if x<=0.04045 then x/12.92 else math.pow((x+0.055)/1.055,2.4)
  private def obtain(key: Any)(make: => (BatchAsset,Option[GlyphRaster])): (BatchAsset,Option[GlyphRaster]) =
    pixels.remove(key) match
      case Some(entry) => ah+=1;pixels(key)=entry;entry
      case None =>
        val entry=make
        val bytes=entry._1.bytes+entry._2.map(_.byteSize.toLong).getOrElse(0L)
        require(bytes<=4194304,"CPU texture payload budget")
        while pixels.nonEmpty && (pixels.size>=256 || assetBytes+bytes>4194304) do
          val old=pixels.remove(pixels.head._1).get
          assetBytes-=old._1.bytes+old._2.map(_.byteSize.toLong).getOrElse(0L)
        pixels(key)=entry;assetBytes+=bytes;entry
  private def image(src: ImageSnapshot): BatchAsset = obtain(src) {
    require(src.width<=256 && src.height<=256,"image atlas cell budget")
    val bytes=src.pixelsCopy;val floats=new Array[Float](bytes.length)
    for i <- 0 until src.width*src.height do
      val a=(bytes(i*4+3)&255)/255.0
      for c <- 0 until 3 do floats(i*4+c)=(linear((bytes(i*4+c)&255)/255.0)*a).toFloat
      floats(i*4+3)=a.toFloat
    images+=1;(new BatchAsset(1,src.width,src.height,floats,Array.emptyByteArray),None)
  }._1
  private def glyph(font: ResolvedFont,id: Int,scale: Double): (GlyphRaster,BatchAsset) =
    val key=GlyphKey(font.data,font.faceIndex,font.size,id,scale,font.metrics,font.shaperVersion,font.rasterizerVersion,font.unitsPerEm,font.glyphCount)
    val entry=obtain(key) {
      val face=NativeFont.open(font.data,font.size,font.faceIndex)
      val mask=try
        require(face.font.metrics==font.metrics && face.font.shaperVersion==font.shaperVersion && face.font.rasterizerVersion==font.rasterizerVersion,"resolved font mismatch")
        face.rasterize(id,scale,maxPixels=4096)
      finally face.close()
      require(mask.width<=64 && mask.height<=64,"glyph atlas cell budget")
      rasters+=1
      (new BatchAsset(2,mask.width,mask.height,Array.emptyFloatArray,mask.coverageCopy),Some(mask))
    }
    (entry._2.get,entry._1)
  def compile(drawing: PreparedDrawing): BatchProgram =
    check();val began=System.nanoTime();val t=drawing.target
    require(t.width<=640 && t.height<=360,"batch target budget")
    val work=new WindingMesh.Budget
    val data=ArrayBuffer[Float](t.width.toFloat,t.height.toFloat,0,0)
    val meshes=ArrayBuffer.empty[BatchMesh];val assets=ArrayBuffer.empty[BatchAsset]
    val uses=ArrayBuffer.empty[MeshUse];val patches=ArrayBuffer.empty[(Int,Int)];val steps=ArrayBuffer.empty[BatchStep]
    var vertices=0;var depth=0;var records=0
    def vec(xs: Double*): Int =
      require(data.length<1048576,"batch command storage budget")
      val at=data.length/4
      data++=xs.map { x => val f=x.toFloat;require(f.isFinite,"nonfinite batch value");f };at
    def put(at: Int,xs: Double*): Unit = xs.zipWithIndex.foreach { (x,i) => val f=x.toFloat;require(f.isFinite,"nonfinite batch value");data(at*4+i)=f }
    def mesh(original: FlatPath,rule: FillRule,originalClips: Vector[PreparedClip]): (BatchMesh,Point) =
      val ps=original.contours.flatten
      val inside=ps.nonEmpty && ps.forall(p => p.x>=0 && p.y>=0 && p.x<=t.width && p.y<=t.height)
      val offset=if inside then Point(ps.map(_.x).min,ps.map(_.y).min) else Point(0,0)
      def shifted(p: FlatPath)=FlatPath(p.contours.map(_.map(q => Point(q.x-offset.x,q.y-offset.y))))
      val path=shifted(original);val clips=originalClips.map(c => c.copy(path=shifted(c.path)))
      val key=MeshKey(path,rule,clips,t.width,t.height,t.tolerance)
      geometry.remove(key) match
        case Some(value) => mh+=1;geometry(key)=value;(value,offset)
        case None =>
          val value=WindingMesh.tessellate(path,rule,clips,t,work)
          require(value.bytes<=8388608,"CPU mesh payload budget")
          while geometry.nonEmpty && (geometry.size>=256 || meshBytes+value.bytes>8388608) do
            val old=geometry.remove(geometry.head._1).get;meshBytes-=old.bytes
          geometry(key)=value;meshBytes+=value.bytes;(value,offset)
    def brush(base: Int,paint: PreparedPaint,anchor: Point): Unit = paint match
      case PreparedPaint.Solid(c) => put(base+1,linear(c.red)*c.alpha,linear(c.green)*c.alpha,linear(c.blue)*c.alpha,c.alpha)
      case PreparedPaint.LinearGradient(x,y,offset,stops,extend) =>
        val corners=Vector(Point(0,0),Point(t.width,0),Point(0,t.height),Point(t.width,t.height))
        require(corners.forall { p =>
          val exact=(p.x-anchor.x)*x+(p.y-anchor.y)*y+offset
          val packed=(p.x-anchor.x.toFloat)*x.toFloat+(p.y-anchor.y.toFloat)*y.toFloat+offset.toFloat
          exact.isFinite && math.abs(exact)<=65536 && math.abs(exact-packed)<=1e-6
        },"batch gradient projection precision")
        val start=data.length/4
        stops.foreach { s =>
          val c=s.color;vec(s.offset,0,0,0);vec(linear(c.red)*c.alpha,linear(c.green)*c.alpha,linear(c.blue)*c.alpha,c.alpha)
        }
        val record=vec(x,y,offset,extend.ordinal);vec(start,stops.size,anchor.x,anchor.y)
        put(base+7,record+1,0,0,0)
    def append(path: FlatPath,rule: FillRule,clips: Vector[PreparedClip])(record: Int => Unit): Unit =
      records+=1;require(records<=8192,"batch operation budget")
      val base=data.length/4
      for _ <- 0 until 8 do vec(0,0,0,0)
      record(base)
      val (geometry,offset)=mesh(path,rule,clips)
      require(math.abs(offset.x.toFloat.toDouble-offset.x)<=t.tolerance/8 && math.abs(offset.y.toFloat.toDouble-offset.y)<=t.tolerance/8,"mesh translation precision")
      data((base+6)*4+2)=offset.x.toFloat;data((base+6)*4+3)=offset.y.toFloat
      if geometry.count>0 then
        val n=meshes.indexOf(geometry);val slot=if n>=0 then n else {meshes+=geometry;meshes.size-1}
        uses+=MeshUse(slot,base)
        if steps.lastOption.exists(_.kind==0) then
          val old=steps.last;steps(steps.size-1)=old.copy(count=old.count+geometry.count)
        else steps+=BatchStep(0,vertices,geometry.count,1)
        vertices+=geometry.count
        require(vertices<=262144*3,"expanded batch triangle budget")
    def quad(asset: BatchAsset,src: Rect,dest: Rect,sampling: Sampling,alpha: Double,m: Affine,
        clips: Vector[PreparedClip],paint: Option[(PreparedPaint,Point)]): Unit =
      m.inverse.filter(_ => dest.width>0 && dest.height>0).foreach { inv =>
        val approx=Affine(inv.a.toFloat,inv.b.toFloat,inv.c.toFloat,inv.d.toFloat,inv.tx.toFloat,inv.ty.toFloat)
        require(Vector(Point(0,0),Point(t.width,0),Point(0,t.height),Point(t.width,t.height)).forall(p => math.hypot(inv(p).x-approx(p).x,inv(p).y-approx(p).y)<=1.0/128),"batch inverse texture precision")
        Vector(src.x,src.y,src.width,src.height,dest.x,dest.y,dest.width,dest.height).foreach(x => require(math.abs(x.toFloat.toDouble-x)<=t.tolerance/8,"batch texture rectangle precision"))
        val p=FlatPath(Vector(Vector(m(Point(dest.x,dest.y)),m(Point(dest.x+dest.width,dest.y)),m(Point(dest.x+dest.width,dest.y+dest.height)),m(Point(dest.x,dest.y+dest.height)))))
        append(p,FillRule.NonZero,clips) { base =>
          val n=assets.indexOf(asset);val slot=if n>=0 then n else
            assets+=asset
            require(assets.count(_.kind==1)<=16 && assets.count(_.kind==2)<=256,"compiled texture inventory budget")
            assets.size-1
          patches+=((base*4+2,slot))
          put(base,asset.kind,sampling.ordinal,0,0)
          put(base+2,inv.a,inv.c,inv.tx,alpha);put(base+3,inv.b,inv.d,inv.ty,0)
          put(base+4,dest.x,dest.y,dest.width,dest.height);put(base+5,src.x,src.y,src.width,src.height)
          put(base+6,asset.width,asset.height,0,0)
          paint.foreach((v,a) => brush(base,v,a))
        }
      }
    drawing.commands.foreach {
      case PreparedCommand.Fill(p,c,r,cs) => append(p,r,cs)(base => brush(base,c,p.contours.iterator.flatMap(_.headOption).nextOption().getOrElse(Point(0,0))))
      case PreparedCommand.Image(src,dest,sampling,alpha,m,cs) => quad(image(src.image),src.source,dest,sampling,alpha,m,cs,None)
      case PreparedCommand.Glyphs(run,origin,c,m,cs) =>
        val s=Vector(m.a,m.b,m.c,m.d).map(math.abs).max
        val density=if s==0 then 0.0 else
          val a=m.a/s;val b=m.b/s;val c=m.c/s;val d=m.d/s
          val tr=a*a+b*b+c*c+d*d;val det=a*d-b*c
          s*math.sqrt((tr+math.sqrt(math.max(0,tr*tr-4*det*det)))/2)
        if density>0 && m.inverse.nonEmpty then run.glyphs.foreach { g =>
          val (mask,asset)=glyph(run.font,g.id,density)
          if mask.width>0 && mask.height>0 then quad(asset,Rect(0,0,mask.width,mask.height),
            Rect(origin.x+g.position.x+mask.left/mask.deviceScale,origin.y+g.position.y-mask.top/mask.deviceScale,mask.width/mask.deviceScale,mask.height/mask.deviceScale),
            Sampling.Linear,1,m,cs,Some((c,m(Point(0,0)))))
        }
      case PreparedCommand.BeginLayer(a) => depth+=1;require(depth<=2,"batch layer budget");steps+=BatchStep(1,0,0,a.toFloat)
      case PreparedCommand.EndLayer => require(depth>0,"unbalanced batch layer");depth-=1;steps+=BatchStep(2,0,0,1)
    }
    require(depth==0,"unbalanced batch layer")
    new BatchProgram(drawing,data.toArray,meshes.toVector,uses.toVector,assets.toVector,patches.toVector,steps.toVector,System.nanoTime()-began)
  def close(): Unit =
    require(Thread.currentThread() eq owner,"batch compiler thread mismatch")
    geometry.clear();pixels.clear();meshBytes=0;assetBytes=0;open=false
