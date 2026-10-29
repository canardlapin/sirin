package sirin.graphics

import sirin.render.*
import sirin.graphics.text.NativeFont
import scala.collection.mutable.ArrayBuffer

/** Verification only. Tiles the unchanged reference interpreter, with exact
  * bounding-box rejection of fills outside a tile. Layer commands and all
  * image/glyph draws stay in order. No source work budget is increased.
  * This helper lives in the probe, not the public graphics module. */
private[sirin] object BatchOracle:
  def render(reference: OffscreenGpu,original: PreparedDrawing): ImageSnapshot =
    val width=original.target.width;val height=original.target.height
    val result=new Array[Byte](width*height*4)
    for y <- 0 until height by 16;x <- 0 until width by 16 do
      val w=math.min(16,width-x);val h=math.min(16,height-y)
      val shift=Affine.translation(-x,-y)
      def path(p: FlatPath)=FlatPath(p.contours.map(_.map(shift(_))))
      def clips(cs: Vector[PreparedClip])=cs.map(c => c.copy(path=path(c.path)))
      val commands=original.commands.flatMap {
        case PreparedCommand.Fill(p,c,r,cs) =>
          val points=p.contours.flatten
          if points.isEmpty || points.forall(_.x<x) || points.forall(_.x>x+w) || points.forall(_.y<y) || points.forall(_.y>y+h) then Vector.empty
          else Vector(PreparedCommand.Fill(path(p),c,r,clips(cs)))
        case PreparedCommand.Image(src,dest,sampling,alpha,m,cs) => Vector(PreparedCommand.Image(src,dest,sampling,alpha,m.andThen(shift),clips(cs)))
        case PreparedCommand.Glyphs(run,origin,c,m,cs) => Vector(PreparedCommand.Glyphs(run,origin,c,m.andThen(shift),clips(cs)))
        case other => Vector(other)
      }
      val target=RenderTarget(reference.generation,w,h,original.target.logicalToDevice.andThen(shift),original.target.tolerance)
      val tile=new PreparedDrawing(original.source,target,original.capabilities,commands,original.resources,original.visitedCommands,original.preparedPoints)
      val pixels=reference.render(reference.compile(tile)).pixelsCopy
      for row <- 0 until h do Array.copy(pixels,row*w*4,result,((y+row)*width+x)*4,w*4)
    ImageSnapshot.copyRgba8(width,height,result)

  private def edges(p: FlatPath): Vector[(Point,Point)]=p.contours.filter(_.size>1).flatMap(ps => ps.zip(ps.drop(1):+ps.head))
  private def boundaries(prepared: PreparedDrawing): Vector[(Point,Point)] =
    def rect(r: Rect,m: Affine)=edges(FlatPath(Vector(Vector(m(Point(r.x,r.y)),m(Point(r.x+r.width,r.y)),m(Point(r.x+r.width,r.y+r.height)),m(Point(r.x,r.y+r.height))))))
    prepared.commands.flatMap {
      case PreparedCommand.Fill(p,_,_,cs) => edges(p)++cs.flatMap(c => edges(c.path))
      case PreparedCommand.Image(_,dest,_,_,m,cs) => rect(dest,m)++cs.flatMap(c => edges(c.path))
      case PreparedCommand.Glyphs(run,origin,_,m,cs) =>
        val a=m.a;val b=m.b;val c=m.c;val d=m.d
        val tr=a*a+b*b+c*c+d*d;val det=a*d-b*c
        val density=math.sqrt((tr+math.sqrt(math.max(0,tr*tr-4*det*det)))/2)
        val face=NativeFont.open(run.font.data,run.font.size,run.font.faceIndex)
        try run.glyphs.flatMap { g =>
          val mask=face.rasterize(g.id,density)
          if mask.width==0 || mask.height==0 then Vector.empty
          else rect(Rect(origin.x+g.position.x+mask.left/mask.deviceScale,origin.y+g.position.y-mask.top/mask.deviceScale,
            mask.width/mask.deviceScale,mask.height/mask.deviceScale),m)
        }++cs.flatMap(c => edges(c.path)) finally face.close()
      case _ => Vector.empty
    }
  final case class Comparison(checked: Int,excluded: Int,worst: Int,mismatches: Int,firstMismatch: Option[String])
  def compare(actual: ImageSnapshot,reference: ImageSnapshot,prepared: PreparedDrawing): Comparison =
    require(actual.width==reference.width && actual.height==reference.height,"oracle dimensions differ")
    val ties=Array.fill(actual.width*actual.height)(false)
    boundaries(prepared).foreach { (u,v) =>
      val minx=math.max(0,math.floor(math.min(u.x,v.x)-0.0001).toInt)
      val maxx=math.min(actual.width-1,math.floor(math.max(u.x,v.x)+0.0001).toInt)
      val miny=math.max(0,math.floor(math.min(u.y,v.y)-0.0001).toInt)
      val maxy=math.min(actual.height-1,math.floor(math.max(u.y,v.y)+0.0001).toInt)
      val dx=v.x-u.x;val dy=v.y-u.y;val length=dx*dx+dy*dy
      var y=miny
      while y<=maxy do
        var x=minx
        while x<=maxx do
          val index=y*actual.width+x;var sy=0
          while sy<4 && !ties(index) do
            var sx=0
            while sx<4 && !ties(index) do
              val px=x+(sx+0.5)/4;val py=y+(sy+0.5)/4
              val t=if length==0 then 0.0 else math.max(0,math.min(1,((px-u.x)*dx+(py-u.y)*dy)/length))
              ties(index)=math.hypot(px-u.x-dx*t,py-u.y-dy*t)<=0.0001;sx+=1
            sy+=1
          x+=1
        y+=1
    }
    val a=actual.pixelsCopy;val b=reference.pixelsCopy
    var excluded=0;var worst=0;var mismatches=0;val first=ArrayBuffer.empty[String]
    for pixel <- ties.indices do
      if ties(pixel) then excluded+=1
      else for c <- 0 until 4 do
        val i=pixel*4+c;val delta=math.abs((a(i)&255)-(b(i)&255));worst=math.max(worst,delta)
        if delta>2 then
          mismatches+=1
          if first.isEmpty then first+=s"x=${pixel%actual.width} y=${pixel/actual.width} channel=$c actual=${a(i)&255} reference=${b(i)&255}"
    Comparison(ties.length-excluded,excluded,worst,mismatches,first.headOption)
