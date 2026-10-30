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
final case class Placed(node: Node, bounds: Box, clip: Box, children: Vector[Placed], text: Option[TextLayout])
final case class FieldLabels(label: Option[ResolvedTextLayout],error: Option[ResolvedTextLayout],hint: Option[ResolvedTextLayout])
final case class LayoutResult(root: Placed, viewports: Vector[ViewportRequest], text: PreparedText,
    scrollOffsets: Map[Long,Double],editors: Map[Long,EditSnapshot]=Map.empty,fieldLabels: Map[Long,FieldLabels]=Map.empty):
  def editorState(p: Placed): EditSnapshot = p.node.content match
    case Content.Editable(id,value,_,_,_,_) => editors.getOrElse(id,EditSnapshot(value,EditSelection.at(0)))
    case _ => throw new IllegalArgumentException("not a field")
  def editorArea(p: Placed): Box = p.node.content match
    case Content.Editable(_,_,label,_,_,error) =>
      val top=if label.nonEmpty then 20.0 else 0.0
      Box(p.bounds.x+10,p.bounds.y+top+8,math.max(0,p.bounds.width-20),math.max(0,p.bounds.height-top-16-(if error.nonEmpty then 20 else 0)))
    case _ => p.bounds
  def editorCaret(p: Placed): TextCaret =
    val layout=p.text.get;val head=editorState(p).visibleSelection.head
    layout.caret(head).getOrElse(layout.graphemeBoundaries.flatMap(layout.caretsAt).minBy(c => math.abs(c.position.utf16Offset-head.utf16Offset)))
  def textOrigin(p: Placed): Point =
    val area=editorArea(p);val caret=editorCaret(p).bounds
    Point((area.x-math.max(0,caret.origin.x.toDouble-area.width+2)).lp,
      (area.y-math.max(0,caret.origin.y.toDouble+caret.size.height.toDouble-area.height)).lp)
  def fields: Vector[Placed] =
    def visit(p: Placed): Vector[Placed]=Option.when(p.node.content.isInstanceOf[Content.Editable])(p).toVector++p.children.flatMap(visit)
    visit(root)

  def drawing(theme: Theme,focus: Option[NodeId]=None,keyboardFocus: Boolean=false,pressed: Option[NodeId]=None,fieldFocus: Option[NodeId]=None): Drawing = RecordingCanvas.record(theme,text) { canvas =>
    def paint(p: Placed): Unit =
      canvas.withClip(Rect(Point(p.clip.x.lp,p.clip.y.lp),Size(p.clip.width.lp,p.clip.height.lp))) {
        p.node.content match
          case Content.Button(_,_,enabled,_,primary) =>
            def rectangle(b: Box) = Rect(Point(b.x.lp,b.y.lp),Size(b.width.lp,b.height.lp))
            val base=if pressed.contains(p.node.id) then Color.rgba(65,112,160) else if primary then Color.rgba(45,90,140) else Color.rgba(44,53,69)
            val focused=keyboardFocus && focus.contains(p.node.id)
            if focused then canvas.fill(rectangle(p.bounds),theme.color(ColorRole.Accent))
            val inner=if focused then Box(p.bounds.x+2,p.bounds.y+2,math.max(0,p.bounds.width-4),math.max(0,p.bounds.height-4)) else p.bounds
            canvas.fill(rectangle(inner),base)
            canvas.text(p.text.get,Point((p.bounds.x+12).lp,(p.bounds.y+8).lp),theme.color(if enabled then ColorRole.OnSurface else ColorRole.Muted))
          case Content.Editable(id,_,label,_,_,error) =>
            def rect(b: Box)=Rect(Point(b.x.lp,b.y.lp),Size(b.width.lp,b.height.lp))
            val area=editorArea(p);val top=if label.nonEmpty then 20 else 0
            val border=Box(p.bounds.x,p.bounds.y+top,p.bounds.width,math.max(0,p.bounds.height-top-(if error.nonEmpty then 20 else 0)))
            canvas.fill(rect(border),if error.nonEmpty then Color.rgba(222,100,100) else Color.rgba(89,103,126))
            canvas.fill(rect(Box(border.x+1,border.y+1,math.max(0,border.width-2),math.max(0,border.height-2))),Color.rgba(28,34,46))
            fieldLabels.get(id).foreach { labels =>
              labels.label.foreach(t => canvas.text(t,Point(p.bounds.x.lp,p.bounds.y.lp),theme.color(ColorRole.OnSurface)))
              labels.error.foreach(t => canvas.text(t,Point(p.bounds.x.lp,(border.y+border.height+2).lp),Color.rgba(255,145,145)))
            }
            canvas.withClip(rect(area.intersect(p.clip))) {
              val layout=p.text.get;val state=editorState(p);val origin=textOrigin(p)
              def region(r: TextSelectionRegion,color: Color): Unit = canvas.fill(Rect(Point((origin.x.toDouble+r.bounds.origin.x.toDouble).lp,(origin.y.toDouble+r.bounds.origin.y.toDouble).lp),r.bounds.size),color)
              def selected(from: Int,until: Int): Vector[TextSelectionRegion] =
                val stops=layout.graphemeBoundaries
                val a=stops.filter(_<=from).lastOption.getOrElse(0);val b=stops.find(_>=until).getOrElse(layout.text.length)
                layout.selection(a,b)
              val selection=state.visibleSelection
              if fieldFocus.orElse(focus).contains(p.node.id) && !selection.empty then selected(selection.start,selection.end).foreach(region(_,Color.rgba(62,110,169)))
              if layout.text.isEmpty then fieldLabels.get(id).flatMap(_.hint).foreach(t => canvas.text(t,origin,theme.color(ColorRole.Muted)))
              else canvas.text(layout,origin,theme.color(ColorRole.OnSurface))
              state.marked.foreach { (a,b) => selected(a,b).foreach { r =>
                canvas.fill(Rect(Point((origin.x.toDouble+r.bounds.origin.x.toDouble).lp,(origin.y.toDouble+r.bounds.origin.y.toDouble+r.bounds.size.height.toDouble-2).lp),Size(r.bounds.size.width,1.lp)),theme.color(ColorRole.Accent))
              }}
              if fieldFocus.orElse(focus).contains(p.node.id) then
                val caret=editorCaret(p).bounds
                canvas.fill(Rect(Point((origin.x.toDouble+caret.origin.x.toDouble).lp,(origin.y.toDouble+caret.origin.y.toDouble).lp),Size(1.lp,caret.size.height)),theme.color(ColorRole.Accent))
            }
          case Content.Scroll(_,_) if keyboardFocus && focus.contains(p.node.id) =>
            val b=p.bounds
            canvas.fill(Rect(Point(b.x.lp,b.y.lp),Size(2.lp,b.height.lp)),theme.color(ColorRole.Accent))
          case Content.Label(_,_,color) => canvas.text(p.text.get,Point(p.bounds.x.lp,p.bounds.y.lp),theme.color(color))
          case _ => ()
      }
      p.children.foreach(paint)
    canvas.fill(Rect(Point.origin,Size(root.bounds.width.lp,root.bounds.height.lp)),theme.color(ColorRole.Surface))
    paint(root)
  }

  /** Focus/press decoration composes above a reusable base through shared graphics. */
  def overlay(theme: Theme,focus: Option[NodeId],keyboard: Boolean,pressed: Option[NodeId]): Drawing =
    RecordingCanvas.record(theme,text) { canvas =>
      def rect(b: Box)=Rect(Point(b.x.lp,b.y.lp),Size(b.width.lp,b.height.lp))
      def visit(p: Placed): Unit =
        canvas.withClip(rect(p.clip)) {
          p.node.content match
            case Content.Button(_,_,_,_,_) =>
              val b=p.bounds
              if pressed.contains(p.node.id) then canvas.fill(rect(b),Color.rgba(50,130,220,46))
              if keyboard && focus.contains(p.node.id) then
                val thickness=math.min(2,math.min(b.width,b.height)/2)
                val color=theme.color(ColorRole.Accent)
                Vector(Box(b.x,b.y,b.width,thickness),Box(b.x,b.y+b.height-thickness,b.width,thickness),
                  Box(b.x,b.y,thickness,b.height),Box(b.x+b.width-thickness,b.y,thickness,b.height)).foreach(r => canvas.fill(rect(r),color))
            case Content.Scroll(_,_) if keyboard && focus.contains(p.node.id) =>
              canvas.fill(rect(Box(p.bounds.x,p.bounds.y,math.min(2,p.bounds.width),p.bounds.height)),theme.color(ColorRole.Accent))
            case _ => ()
        }
        p.children.foreach(visit)
      visit(root)
    }

/** Pure full layout reference for the first host. No incremental-layout claim. */
object Layout:
  private final case class Measured(node: Node,width: Double,height: Double,children: Vector[(Measured,Double,Double)],text: Option[TextLayout])
  def apply(root: Node, width: Double, height: Double, scrolls: Map[Long,Double] = Map.empty,editors: Map[Long,EditSnapshot]=Map.empty)(
      resolve: TextRequest => ResolvedTextLayout): LayoutResult =
    require(width.isFinite && height.isFinite && width>0 && height>0,"positive finite root size required")
    val prepared=scala.collection.mutable.LinkedHashMap.empty[TextRequest,ResolvedTextLayout]
    val offsets=scala.collection.mutable.Map.empty[Long,Double]
    val fieldLabels=scala.collection.mutable.Map.empty[Long,FieldLabels]
    def bounded(value: Double, limit: Double): Double = math.max(0,math.min(value,limit))
    def grow(node: Node): Boolean = node.content match
      case Content.Reference(_,child) => grow(child)
      case Content.Styled(Hint.Grow,_) => true
      case Content.Styled(_,child) => grow(child)
      case _ => false
    def measure(node: Node,w: Double,h: Double,fillWidth: Boolean = false,fillHeight: Boolean = false): Measured =
      def leaf(x: Double,y: Double,text: Option[TextLayout]=None) = Measured(node,if fillWidth then w else bounded(x,w),if fillHeight then h else bounded(y,h),Vector.empty,text)
      node.content match
        case Content.Empty => leaf(0,0)
        case Content.Space(size) => leaf(size,size)
        case Content.Reference(_,child) =>
          val c=measure(child,w,h,fillWidth,fillHeight)
          Measured(node,c.width,c.height,Vector((c,0,0)),None)
        case Content.Button(_,label,_,_,_) =>
          val request=TextRequest(label,TextStyle.Body,Lp(math.max(0,w-24)))
          val text=prepared.getOrElseUpdate(request,resolve(request))
          leaf(text.size.width.toDouble+24,text.size.height.toDouble+16,Some(text))
        case Content.Editable(id,value,label,hint,multiple,error) =>
          val shown=editors.getOrElse(id,EditSnapshot(value,EditSelection.at(0))).display
          val limit=if multiple then math.max(1,w-20) else Double.PositiveInfinity
          def resolveText(value: String,style: TextStyle,max: Double) =
            val request=TextRequest(value,style,Lp(max));prepared.getOrElseUpdate(request,resolve(request))
          val layout: TextLayout=if sirin.text.DocumentTextLayout.hasBreaks(shown) then
            sirin.text.DocumentTextLayout(shown)(part => resolveText(part,TextStyle.Body,limit))
          else resolveText(shown,TextStyle.Body,limit)
          fieldLabels(id)=FieldLabels(Option.when(label.nonEmpty)(resolveText(label,TextStyle.Caption,math.max(1,w))),
            error.map(t => resolveText(t,TextStyle.Caption,math.max(1,w))),Option.when(hint.nonEmpty)(resolveText(hint,TextStyle.Body,math.max(1,w-20))))
          leaf(w,(if multiple then 140 else layout.size.height.toDouble+16)+(if label.nonEmpty then 20 else 0)+(if error.nonEmpty then 20 else 0),Some(layout))
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
    LayoutResult(result,requests.result(),PreparedText.fromResolved(prepared.toVector),offsets.toMap,editors,fieldLabels.toMap)
