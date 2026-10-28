package sirin.graphics

import scala.collection.mutable.ArrayBuffer

/** Bounded path-space line/Bezier normal-ribbon expansion. Every polygon has the same
  * winding, so one nonzero fill forms their union without alpha overdraw.
  */
private[graphics] object StrokePreparation:
  def outline(path: Path, style: StrokeStyle, transform: Affine, tolerance: Double, maxPoints: Int, maxDepth: Int = 24): Path =
    val polygons = ArrayBuffer.empty[Vector[Point]]
    var work = 0
    var vertices = 0
    def step(): Unit =
      work += 1
      require(work <= maxPoints * 8L, "stroke work budget")
    def polygon(ps: Vector[Point]): Unit =
      vertices += ps.size + 2
      require(vertices <= maxPoints, "stroke point budget")
      val area = ps.zip(ps.drop(1) :+ ps.head).map((a,b) => (a.x-ps.head.x)*(b.y-ps.head.y)-(a.y-ps.head.y)*(b.x-ps.head.x)).sum
      require(area.isFinite, "stroke polygon overflow")
      if area != 0 then polygons += (if area > 0 then ps else ps.reverse)
    val radius = style.width/2
    require(radius>0,"stroke width underflow")
    // Frobenius norm bounds the largest affine singular value.
    val density = math.hypot(math.hypot(transform.a,transform.b),math.hypot(transform.c,transform.d))
    val deviceRadius = radius*density
    require(deviceRadius.isFinite, "stroke radius overflow")
    def plus(a: Point,b: Point): Point =
      val p=Point(a.x+b.x,a.y+b.y)
      require(math.hypot((p.x-a.x)-b.x,(p.y-a.y)-b.y)*density<=tolerance/8,"stroke coordinate precision")
      p
    def minus(a: Point,b: Point): Point = plus(a,Point(-b.x,-b.y))
    def times(a: Point,s: Double): Point = Point(a.x*s,a.y*s)
    def cross(a: Point,b: Point): Double = a.x*b.y-a.y*b.x
    def disk(p: Point): Unit =
      val error = tolerance/2
      val angle = if deviceRadius <= error then math.Pi/2 else
        2*math.asin(math.sqrt(math.min(1,error/(2*deviceRadius))))
      val count = math.max(8,math.ceil(2*math.Pi/angle))
      require(count.isFinite && count <= maxPoints-vertices-2, "round stroke point budget")
      polygon(Vector.tabulate(count.toInt)(i => plus(p,Point(radius*math.cos(i*2*math.Pi/count),radius*math.sin(i*2*math.Pi/count)))))
    def cap(p: Point,u: Point,start: Boolean): Unit = style.cap match
      case LineCap.Butt => ()
      case LineCap.Round => disk(p)
      case LineCap.Square =>
        val n=Point(-u.y*radius,u.x*radius)
        val extension=times(u,if start then -radius else radius)
        polygon(Vector(plus(p,n),minus(p,n),plus(minus(p,n),extension),plus(plus(p,n),extension)))
    import StrokeCurves.{Piece,Contour}
    def stroke(raw: Vector[Piece],closed: Boolean): Unit =
      step()
      val pieces=raw.filter(_.length>0)
      if pieces.isEmpty && raw.nonEmpty then
        style.cap match
          case LineCap.Round => disk(raw.head.a)
          case LineCap.Square => cap(raw.head.a,raw.head.u,true);cap(raw.head.a,raw.head.u,false)
          case LineCap.Butt => ()
      else if pieces.nonEmpty then
        pieces.foreach { piece =>
          step()
          val n=Point(-piece.u.y*radius,piece.u.x*radius)
          val m=Point(-piece.v.y*radius,piece.v.x*radius)
          val a=plus(piece.a,n);val b=minus(piece.a,n)
          val c=minus(piece.b,m);val d=plus(piece.b,m)
          // Splitting permits wide offset ribbons that fold on their inner side.
          polygon(Vector(a,b,c));polygon(Vector(a,c,d))
        }
        val pairs=pieces.zip(if closed then pieces.drop(1):+pieces.head else pieces.drop(1))
        pairs.foreach { (before,after) =>
          step()
          val u=before.v;val v=after.u;val p=before.b
          val turn=cross(u,v)
          if style.join==LineJoin.Round && (turn!=0 || u.x*v.x+u.y*v.y<0) then disk(p)
          else if turn!=0 then
            val sign=if turn>0 then -1.0 else 1.0
            val a=plus(p,Point(-u.y*radius*sign,u.x*radius*sign))
            val b=plus(p,Point(-v.y*radius*sign,v.x*radius*sign))
            // Half-angle identity avoids catastrophic 1 + dot cancellation
            // near a reversal. Refuse numerically ill-conditioned admitted
            // miters instead of claiming the device-space error budget holds.
            val nx=u.x+v.x;val ny=u.y+v.y
            val norm=math.hypot(nx,ny)
            val denominator=norm*norm/2
            val miter=if norm>0 then 2/norm else Double.PositiveInfinity
            if style.join==LineJoin.Miter then
              require(miter.isFinite || style.miterLimit<1e8,"miter angle precision")
            if style.join==LineJoin.Miter && miter<=style.miterLimit then
              require(deviceRadius*(64*math.ulp(1.0))/denominator<=tolerance/16,"miter angle precision")
              val offset=Point(-ny*radius*sign/denominator,nx*radius*sign/denominator)
              polygon(Vector(p,a,plus(p,offset),b))
            else polygon(Vector(p,a,b))
        }
        if !closed then {cap(pieces.head.a,pieces.head.u,true);cap(pieces.last.b,pieces.last.v,false)}
    val pattern=style.normalizedDashes
    def contour(raw: Vector[Piece],closed: Boolean): Unit =
      if pattern.isEmpty then stroke(raw,closed)
      else if raw.nonEmpty then
        val ps=raw
        var index=0
        val remainder=style.dashOffset % pattern.sum
        var phase=if remainder<0 then remainder+pattern.sum else remainder
        while phase>0 && phase>=pattern(index) do
          step();phase-=pattern(index);index=(index+1)%pattern.size
        var remaining=pattern(index)-phase
        val fragments=ArrayBuffer.empty[Vector[Piece]]
        val current=ArrayBuffer.empty[Piece]
        def finish(): Unit =
          if current.nonEmpty then {fragments+=current.toVector;current.clear()}
        def advance(p: Point,tangent: Point): Unit =
          while remaining==0 do
            step()
            if index%2==0 then
              if pattern(index)==0 then style.cap match
                case LineCap.Round => disk(p)
                case LineCap.Square => cap(p,tangent,true);cap(p,tangent,false)
                case LineCap.Butt => ()
              finish()
            index=(index+1)%pattern.size;remaining=pattern(index)
        ps.foreach { piece =>
          val a=piece.a
          val length=piece.length
          require(length.isFinite,"dash extent overflow")
          val tangent=piece.u
          var distance=0.0
          var p=a
          if length==0 then
            advance(a,tangent)
            if index%2==0 then current+=piece
          while distance<length do
            step();advance(p,tangent)
            val take=math.min(remaining,length-distance)
            val next=distance+take
            require(next>distance,"dash precision exhausted")
            val q=piece.at(next/length)
            if index%2==0 then
              current+=piece.slice(distance/length,next/length)
            distance=next;p=q;remaining=math.max(0,remaining-take)
            if remaining==0 then advance(p,piece.direction(distance/length))
        }
        finish()
        // A dash that crosses the explicit close has a join at the seam.
        if closed && fragments.size>1 && fragments.head.head.a==ps.head.a && fragments.last.last.b==ps.head.a then
          val joined=fragments.last++fragments.head
          fragments.remove(fragments.size-1);fragments(0)=joined
        fragments.foreach(p => stroke(p,closed && p.size>1 && p.head.a==p.last.b))
    val contours=StrokeCurves.flatten(path,style,density,tolerance,maxPoints,maxDepth)
    contours.foreach { case Contour(pieces,closed) => contour(pieces,closed) }
    Path(polygons.toVector.flatMap(p => Vector(PathSegment.MoveTo(p.head))++p.tail.map(PathSegment.LineTo.apply):+PathSegment.Close))
