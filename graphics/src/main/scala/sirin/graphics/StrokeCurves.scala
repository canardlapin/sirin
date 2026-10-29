package sirin.graphics

import scala.collection.mutable.ArrayBuffer

/** A chord with exact endpoint tangent directions. Its normal ribbon is admitted
  * using the Bezier derivative cone, not just centerline flatness. */
private[graphics] object StrokeCurves:
  final case class Piece(a: Point,b: Point,u: Point,v: Point):
    def length: Double = math.hypot(b.x-a.x,b.y-a.y)
    def at(t: Double): Point = if t==0 then a else if t==1 then b else Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)
    def direction(t: Double): Point = unit(Point(u.x+(v.x-u.x)*t,u.y+(v.y-u.y)*t),u)
    def slice(from: Double,to: Double): Piece = Piece(at(from),at(to),direction(from),direction(to))
  final case class Contour(pieces: Vector[Piece],closed: Boolean)
  def unit(p: Point,fallback: Point): Point =
    val length=math.hypot(p.x,p.y)
    require(length.isFinite,"stroke tangent overflow")
    if length==0 then fallback else Point(p.x/length,p.y/length)
  private def sub(a: Point,b: Point)=Point(a.x-b.x,a.y-b.y)
  private def midpoint(a: Point,b: Point)=Point(a.x/2+b.x/2,a.y/2+b.y/2)

  def flatten(path: Path,style: StrokeStyle,density: Double,tolerance: Double,maxPoints: Int,maxDepth: Int): Vector[Contour] =
    val contours=ArrayBuffer.empty[Contour]
    val pieces=ArrayBuffer.empty[Piece]
    var current=Point(0,0);var start=current
    var work=0
    def append(p: Piece): Unit =
      work+=1;require(work<=maxPoints,"stroke curve point budget")
      require(p.length.isFinite,"stroke curve extent overflow")
      pieces+=p
    def line(a: Point,b: Point): Unit =
      val u=unit(sub(b,a),Point(1,0));append(Piece(a,b,u,u))
    def finish(closed: Boolean): Unit =
      if pieces.nonEmpty then contours+=Contour(pieces.toVector,closed)
      pieces.clear()
    // Stationary interior parameters have no unique normal. Refuse them before
    // subdivision instead of painting a guessed cusp or silently deleting it.
    def cusp(a: Point,b: Point,c: Point,d: Point): Unit =
      val ab=sub(b,a);val bc=sub(c,b);val cd=sub(d,c)
      val scale=Vector(ab.x,ab.y,bc.x,bc.y,cd.x,cd.y).map(math.abs).max
      if scale>0 then
        def coefficients(x: Double,y: Double,z: Double)=Vector((x-2*y+z)/scale,2*(y-x)/scale,x/scale)
        val xs=coefficients(ab.x,bc.x,cd.x);val ys=coefficients(ab.y,bc.y,cd.y)
        def roots(v: Vector[Double]): Vector[Double] =
          val aa=v(0);val bb=v(1);val cc=v(2)
          if aa==0 then if bb==0 then Vector.empty else Vector(-cc/bb)
          else
            val disc=bb*bb-4*aa*cc
            if disc<0 then Vector.empty else
              val q= -0.5*(bb+math.copySign(math.sqrt(disc),bb))
              if q==0 then Vector(-bb/(2*aa)) else Vector(q/aa,cc/q)
        val candidates=roots(if xs.forall(_==0) then ys else xs)
        def value(v: Vector[Double],t: Double)=(v(0)*t+v(1))*t+v(2)
        require(!candidates.exists(t => t>0 && t<1 && math.hypot(value(xs,t),value(ys,t))<=1e-12),"stroke cusp unsupported")
    def curve(a: Point,b: Point,c: Point,d: Point,arcBudget: Double,depth: Int): Unit =
      work+=1;require(work<=maxPoints*8L,"stroke curve work budget")
      val chord=sub(d,a);val length=math.hypot(chord.x,chord.y)
      val vectors=Vector(sub(b,a),sub(c,b),sub(d,c))
      val perimeter=vectors.map(v => math.hypot(v.x,v.y)).sum
      require(perimeter.isFinite && length.isFinite,"stroke curve extent overflow")
      if perimeter==0 then line(a,d)
      else
        val u=unit(chord,Point(1,0))
        val cone=vectors.filter(v => v.x!=0 || v.y!=0).map { v =>
          val norm=math.hypot(v.x,v.y)
          ((v.x*u.x+v.y*u.y)/norm,math.abs(v.x*u.y-v.y*u.x)/norm)
        }
        val forward=length>0 && cone.forall(_._1>0)
        val sine=cone.map(_._2).max
        // Control hull bounds center error; the derivative cone bounds all
        // normals, including interpolated normals at dash endpoints.
        val flat=Vector(b,c).map(p => math.abs((p.x-a.x)*u.y-(p.y-a.y)*u.x)).max
        val error=density*(flat+style.width*sine)
        if forward && error<=tolerance/8 && math.max(0,perimeter-length)<=arcBudget then
          val first=vectors.find(v => v.x!=0 || v.y!=0).get
          val last=vectors.reverse.find(v => v.x!=0 || v.y!=0).get
          append(Piece(a,d,unit(first,u),unit(last,u)))
        else
          require(depth<maxDepth,"stroke curve subdivision budget")
          val ab=midpoint(a,b);val bc=midpoint(b,c);val cd=midpoint(c,d)
          val abc=midpoint(ab,bc);val bcd=midpoint(bc,cd);val center=midpoint(abc,bcd)
          curve(a,ab,abc,center,arcBudget/2,depth+1)
          curve(center,bcd,cd,d,arcBudget/2,depth+1)
    def bezier(a: Point,b: Point,c: Point,d: Point,budget: Double): Unit =
      // Path-space widths/dashes require local curve arithmetic. Do not claim
      // the error bound when large origins consume that arithmetic's precision.
      require(Vector(a,b,c,d).forall(p =>
        math.hypot(math.ulp(p.x),math.ulp(p.y))*density*64<=tolerance/16),"stroke curve coordinate precision")
      cusp(a,b,c,d)
      val first=pieces.size
      curve(a,b,c,d,budget,0)
      // De Casteljau arithmetic can round the two representations of a shared
      // derivative differently. A smooth subdivision is one tangent, not a join.
      for i <- first until pieces.size-1 do
        val before=pieces(i);val after=pieces(i+1)
        val shared=unit(Point(before.v.x+after.u.x,before.v.y+after.u.y),before.v)
        pieces(i)=before.copy(v=shared);pieces(i+1)=after.copy(u=shared)
    val arcBudget=if density==0 then Double.MaxValue else tolerance/(16*density*math.max(1,path.segments.size))
    path.segments.foreach {
      case PathSegment.MoveTo(p) => finish(false);current=p;start=p
      case PathSegment.LineTo(p) => line(current,p);current=p
      case PathSegment.QuadraticTo(b,d) =>
        val a=current
        val c1=Point(a.x/3+b.x*(2.0/3),a.y/3+b.y*(2.0/3))
        val c2=Point(d.x/3+b.x*(2.0/3),d.y/3+b.y*(2.0/3))
        bezier(a,c1,c2,d,arcBudget);current=d
      case PathSegment.CubicTo(b,c,d) => bezier(current,b,c,d,arcBudget);current=d
      case PathSegment.Close =>
        if current!=start then line(current,start)
        finish(true);current=start
    }
    finish(false)
    contours.toVector
