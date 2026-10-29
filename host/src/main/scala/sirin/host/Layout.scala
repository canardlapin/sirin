package sirin.host

import sirin.*
import sirin.drawing.{PreparedText,RecordingCanvas,TextRequest}
import sirin.graphics.Drawing
import sirin.text.ResolvedTextLayout

final case class Box(x: Double, y: Double, width: Double, height: Double):
  require(Vector(x,y,width,height).forall(_.isFinite) && width>=0 && height>=0,"invalid host box")
  def intersect(other: Box): Box =
    val left=math.max(x,other.x);val top=math.max(y,other.y)
    Box(left,top,math.max(0,math.min(x+width,other.x+other.width)-left),math.max(0,math.min(y+height,other.y+other.height)-top))
final case class ViewportRequest(instance: Long, viewport: sirin.engine.RowViewport)
final case class Placed(node: Node, bounds: Box, clip: Box, children: Vector[Placed], text: Option[ResolvedTextLayout])
final case class LayoutResult(root: Placed, viewports: Vector[ViewportRequest], text: PreparedText,
    scrollOffsets: Map[Long,Double]):
  def drawing(theme: Theme): Drawing = RecordingCanvas.record(theme,text) { canvas =>
    def paint(p: Placed): Unit = canvas.withClip(Rect(Point(p.clip.x.lp,p.clip.y.lp),Size(p.clip.width.lp,p.clip.height.lp))) {
      p.node.content match
        case Content.Label(_,_,color) => canvas.text(p.text.get,Point(p.bounds.x.lp,p.bounds.y.lp),theme.color(color))
        case _ => ()
      p.children.foreach(paint)
    }
    canvas.fill(Rect(Point.origin,Size(root.bounds.width.lp,root.bounds.height.lp)),theme.color(ColorRole.Surface))
    paint(root)
  }

/** Pure full layout reference for the first host. No incremental-layout claim. */
object Layout:
  private final case class Measured(node: Node,width: Double,height: Double,children: Vector[(Measured,Double,Double)],text: Option[ResolvedTextLayout])
  def apply(root: Node, width: Double, height: Double, scrolls: Map[Long,Double] = Map.empty)(
      resolve: TextRequest => ResolvedTextLayout): LayoutResult =
    require(width.isFinite && height.isFinite && width>0 && height>0,"positive finite root size required")
    val prepared=scala.collection.mutable.LinkedHashMap.empty[TextRequest,ResolvedTextLayout]
    val offsets=scala.collection.mutable.Map.empty[Long,Double]
    def bounded(value: Double, limit: Double): Double = math.max(0,math.min(value,limit))
    def grow(node: Node): Boolean = node.content match
      case Content.Styled(Hint.Grow,_) => true
      case Content.Styled(_,child) => grow(child)
      case _ => false
    def measure(node: Node,w: Double,h: Double,fillWidth: Boolean = false,fillHeight: Boolean = false): Measured =
      def leaf(x: Double,y: Double,text: Option[ResolvedTextLayout]=None) = Measured(node,if fillWidth then w else bounded(x,w),if fillHeight then h else bounded(y,h),Vector.empty,text)
      node.content match
        case Content.Empty => leaf(0,0)
        case Content.Space(size) => leaf(size,size)
        case Content.Label(text,style,_) =>
          val request=TextRequest(text,style,Lp(w))
          val layout=prepared.getOrElseUpdate(request,resolve(request))
          leaf(layout.size.width.toDouble,layout.size.height.toDouble,Some(layout))
        case Content.Styled(hint,child) => hint match
          case Hint.Padding(x,y) =>
            require(x.isFinite && y.isFinite && x>=0 && y>=0,"invalid padding")
            val c=measure(child,math.max(0,w-2*x),math.max(0,h-2*y),fillWidth,fillHeight)
            Measured(node,bounded(c.width+2*x,w),bounded(c.height+2*y,h),Vector((c,x,y)),None)
          case Hint.Width(value) =>
            require(value.isFinite && value>=0,"invalid width")
            val c=measure(child,bounded(value,w),h,true,fillHeight)
            Measured(node,bounded(value,w),c.height,Vector((c,0,0)),None)
          case Hint.Height(value) =>
            require(value.isFinite && value>=0,"invalid height")
            val c=measure(child,w,bounded(value,h),fillWidth,true)
            Measured(node,c.width,bounded(value,h),Vector((c,0,0)),None)
          case Hint.Grow =>
            val c=measure(child,w,h,fillWidth,fillHeight);Measured(node,c.width,c.height,Vector((c,0,0)),None)
        case Content.Stack(horizontal,gap,alignment,children) =>
          require(gap.isFinite && gap>=0,"invalid stack gap")
          require(alignment!=Alignment.Baseline,"baseline layout remains deferred")
          val active=children.filterNot(_.content==Content.Empty)
          val main=if horizontal then w else h
          val cross=if horizontal then h else w
          val available=math.max(0,main-gap*math.max(0,active.size-1))
          def expanding(child: Node): Boolean = available.isFinite && grow(child)
          def childMeasure(child: Node,mainSize: Double,fillMain: Boolean): Measured =
            measure(child,if horizontal then mainSize else cross,if horizontal then cross else mainSize,
              if horizontal then fillMain else alignment==Alignment.Stretch && cross.isFinite,
              if horizontal then alignment==Alignment.Stretch && cross.isFinite else fillMain)
          val fixed=active.filterNot(expanding).map(child => child -> childMeasure(child,available,false)).toMap
          val used=fixed.valuesIterator.map(c => if horizontal then c.width else c.height).sum
          val growing=active.count(expanding)
          val share=if growing==0 then 0.0 else math.max(0,available-used)/growing
          val measuredChildren=active.map(child => if expanding(child) then childMeasure(child,share,true) else fixed(child))
          val naturalCross=measuredChildren.map(c => if horizontal then c.height else c.width).maxOption.getOrElse(0.0)
          val crossSize=if (if horizontal then fillHeight else fillWidth) then cross else bounded(naturalCross,cross)
          var cursor=0.0
          val placed=measuredChildren.map { c =>
            val extent=if horizontal then c.height else c.width
            val offset=alignment match
              case Alignment.Center => math.max(0,(crossSize-extent)/2)
              case Alignment.End => math.max(0,crossSize-extent)
              case _ => 0.0
            val item=(c,if horizontal then cursor else offset,if horizontal then offset else cursor)
            cursor+=(if horizontal then c.width else c.height)+gap
            item
          }
          val naturalMain=bounded(math.max(0,cursor-(if active.isEmpty then 0 else gap)),main)
          val mainSize=if (if horizontal then fillWidth else fillHeight) then main else naturalMain
          Measured(node,if horizontal then mainSize else crossSize,if horizontal then crossSize else mainSize,placed,None)
        case Content.Scroll(instance,child) =>
          require(h.isFinite,"scroll viewport needs a finite allocated height")
          val c=measure(child,w,Double.PositiveInfinity)
          val offset=math.max(0,math.min(scrolls.getOrElse(instance,0.0),math.max(0,c.height-h)))
          require(offset.isFinite,"invalid scroll offset")
          offsets(instance)=offset
          Measured(node,w,h,Vector((c,0,-offset)),None)
        case Content.Virtual(_,first,total,rowHeight,children) =>
          require(rowHeight.isFinite && rowHeight>0 && total>=0 && first>=0 && first+children.size<=total,"invalid virtual metadata")
          val rows=children.zipWithIndex.map { (child,index) =>
            val c=measure(child,w,rowHeight).copy(height=rowHeight)
            (c,0.0,(first+index)*rowHeight)
          }
          Measured(node,w,total*rowHeight,rows,None)
    val measured=measure(root,width,height,true,true).copy(width=width,height=height)
    val requests=Vector.newBuilder[ViewportRequest]
    def place(m: Measured,x: Double,y: Double,ancestorClip: Box): Placed =
      val bounds=Box(x,y,m.width,m.height)
      val clip=ancestorClip.intersect(bounds)
      m.node.content match
        case Content.Virtual(instance,_,_,_,_) =>
          requests+=ViewportRequest(instance,sirin.engine.RowViewport(Lp(math.max(0,clip.y-y)),Lp(clip.height)))
        case _ => ()
      val children=m.children.map((child,dx,dy) => place(child,x+dx,y+dy,clip))
      Placed(m.node,bounds,clip,children,m.text)
    val result=place(measured,0,0,Box(0,0,width,height))
    LayoutResult(result,requests.result(),PreparedText.fromResolved(prepared.toVector),offsets.toMap)
