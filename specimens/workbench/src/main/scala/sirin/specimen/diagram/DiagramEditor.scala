package sirin.specimen.diagram

import sirin.*
import scala.annotation.unused

final case class DiagramNode(id: Long,label: String,at: Point)
final case class DiagramEdge(from: Long,to: Long)
final case class DiagramGraph(nodes: Vector[DiagramNode],edges: Vector[DiagramEdge]):
  require(nodes.size<=512 && edges.size<=2048,"diagram exceeds 512 nodes or 2,048 edges")
  require(nodes.map(_.id).distinct.size==nodes.size && nodes.forall(n => n.id>0 && n.label.length<=80 &&
    Vector(n.at.x.toDouble,n.at.y.toDouble).forall(v => v.isFinite && math.abs(v)<=1000000)),"invalid diagram nodes")
  private val ids=nodes.map(_.id).toSet
  require(edges.distinct.size==edges.size && edges.forall(e => e.from!=e.to && ids(e.from) && ids(e.to)),"invalid diagram edges")
final case class DiagramCamera(origin: Point=Point.origin,scale: Double=1):
  require(scale.isFinite && scale>=0.125 && scale<=4 && Vector(origin.x.toDouble,origin.y.toDouble).forall(v => v.isFinite && math.abs(v)<=1000000),"invalid diagram camera")
  def world(at: Point): Point=Point(((at.x.toDouble-origin.x.toDouble)/scale).lp,((at.y.toDouble-origin.y.toDouble)/scale).lp)
  def screen(at: Point): Point=Point((at.x.toDouble*scale+origin.x.toDouble).lp,(at.y.toDouble*scale+origin.y.toDouble).lp)
private final case class History(graph: DiagramGraph,undo: Vector[DiagramGraph]=Vector.empty,redo: Vector[DiagramGraph]=Vector.empty)
private final case class Gesture(graph: DiagramGraph,camera: DiagramCamera,start: Point,selected: Set[Long],pan: Boolean)
final case class DiagramScene(graph: DiagramGraph,camera: DiagramCamera,selected: Set[Long])

/** Public-API client. One graph history entry per completed drag; view motion is separate. */
final class DiagramEditor(scope: Scope,initial: DiagramGraph=DiagramEditor.sample):
  private val history=scope.cell(History(initial))
  private val selected=scope.cell(Set.empty[Long])
  private val cameraCell=scope.cell(DiagramCamera())
  private val gesture=scope.cell(Option.empty[Gesture])
  private val panCell=scope.cell(false)
  val graph: Signal[DiagramGraph]=history.map(_.graph)
  val selection: Signal[Set[Long]]=selected
  val camera: Signal[DiagramCamera]=cameraCell
  val panning: Signal[Boolean]=panCell
  val dragging: Signal[Boolean]=gesture.map(_.nonEmpty)
  val scene: Signal[DiagramScene]=(graph,camera,selection).mapN(DiagramScene.apply)
  private val idle=gesture.map(_.isEmpty)
  private def commit(next: DiagramGraph)(using UiTxn): Unit=
    val old=history.get
    if next!=old.graph then history.set(History(next,(old.undo:+old.graph).takeRight(64)))
  def select(ids: Set[Long])(using UiTxn): Unit=
    UiTxn.require(gesture.get.isEmpty,"finish the active gesture first")
    UiTxn.require(ids.subsetOf(graph.get.nodes.map(_.id).toSet),"unknown diagram selection")
    selected.set(ids)
  private def sanitizeSelection()(using UiTxn): Unit=selected.update(_ intersect graph.get.nodes.map(_.id).toSet)
  val undo: Command[Unit,Unit]=scope.action[Unit]("diagram.undo","Undo",enabled=(idle,history).mapN((ready,h) => ready && h.undo.nonEmpty)) { _ =>
    val h=history.get
    h.undo.lastOption.foreach(previous => history.set(History(previous,h.undo.dropRight(1),(h.redo:+h.graph).takeRight(64))))
    sanitizeSelection()
  }
  val redo: Command[Unit,Unit]=scope.action[Unit]("diagram.redo","Redo",enabled=(idle,history).mapN((ready,h) => ready && h.redo.nonEmpty)) { _ =>
    val h=history.get
    h.redo.lastOption.foreach(next => history.set(History(next,(h.undo:+h.graph).takeRight(64),h.redo.dropRight(1))))
    sanitizeSelection()
  }
  val delete: Command[Unit,Unit]=scope.action[Unit]("diagram.delete","Delete",enabled=(idle,selection).mapN((ready,s) => ready && s.nonEmpty)) { _ =>
    val gone=selected.get;val g=graph.get
    commit(DiagramGraph(g.nodes.filterNot(n => gone(n.id)),g.edges.filterNot(e => gone(e.from) || gone(e.to))))
    selected.set(Set.empty)
  }
  val connect: Command[Unit,Unit]=scope.action[Unit]("diagram.connect","Connect",enabled=(idle,scene).mapN { (ready,s) =>
    val ids=s.selected.toVector.sorted
    ready && ids.size==2 && s.graph.edges.size<2048 && !s.graph.edges.contains(DiagramEdge(ids.head,ids.last))
  }) { _ =>
    val ids=selected.get.toVector.sorted;val g=graph.get
    commit(g.copy(edges=g.edges:+DiagramEdge(ids.head,ids.last)))
  }
  val add: Command[Unit,Unit]=scope.action[Unit]("diagram.add","Add node",enabled=(idle,graph).mapN((ready,g) => ready && g.nodes.size<512)) { _ =>
    val g=graph.get;val id=Math.addExact(g.nodes.map(_.id).maxOption.getOrElse(0L),1L)
    val at=cameraCell.get.world(Point(100.lp,100.lp))
    commit(g.copy(nodes=g.nodes:+DiagramNode(id,s"Node $id",at)));selected.set(Set(id))
  }
  val panMode: Command[Unit,Unit]=scope.action[Unit]("diagram.pan","Pan mode",enabled=idle)(_ => panCell.update(!_))
  def zoom(at: Point,factor: Double)(using UiTxn): Unit=
    UiTxn.require(gesture.get.isEmpty,"finish the active gesture first")
    UiTxn.require(factor.isFinite && factor>0,"invalid zoom factor")
    val old=cameraCell.get;val world=old.world(at);val scale=math.max(0.125,math.min(4,old.scale*factor))
    cameraCell.set(DiagramCamera(Point((at.x.toDouble-world.x.toDouble*scale).lp,(at.y.toDouble-world.y.toDouble*scale).lp),scale))
  val zoomIn: Command[Unit,Unit]=scope.action[Unit]("diagram.zoom-in","Zoom in",enabled=idle)(_ => zoom(Point(300.lp,180.lp),1.25))
  val zoomOut: Command[Unit,Unit]=scope.action[Unit]("diagram.zoom-out","Zoom out",enabled=idle)(_ => zoom(Point(300.lp,180.lp),0.8))
  val reset: Command[Unit,Unit]=scope.action[Unit]("diagram.reset","Reset view",enabled=idle)(_ => cameraCell.set(DiagramCamera()))
  def moveSelection(by: Point)(using UiTxn): Unit=
    UiTxn.require(gesture.get.isEmpty,"finish the active gesture first")
    val ids=selected.get;val g=graph.get
    commit(g.copy(nodes=g.nodes.map(n => if ids(n.id) then n.copy(at=n.at+by) else n)))
  private[diagram] def down(event: PointerEvent)(using UiTxn): PointerResponse=
    if gesture.get.nonEmpty then PointerResponse.Handled
    else if event.button==PointerButton.Secondary || panCell.get || event.mods.has(Mods.Alt) then
      gesture.set(Some(Gesture(graph.get,cameraCell.get,event.position,Set.empty,true)));PointerResponse.Capture
    else if event.button!=PointerButton.Primary then PointerResponse.Ignore
    else event.hit match
      case Hit.Part(part) =>
        val id=part.value
        UiTxn.require(graph.get.nodes.exists(_.id==id),"diagram part is no longer present")
        if event.mods.has(Mods.Shift) then selected.update(s => if s(id) then s-id else s+id)
        else if !selected.get(id) then selected.set(Set(id))
        if selected.get.nonEmpty then
          gesture.set(Some(Gesture(graph.get,cameraCell.get,event.position,selected.get,false)));PointerResponse.Capture
        else PointerResponse.Handled
      case _ => selected.set(Set.empty);PointerResponse.Handled
  private[diagram] def move(at: Point)(using UiTxn): Unit=gesture.get.foreach { start =>
    val delta=at-start.start
    if start.pan then cameraCell.set(start.camera.copy(origin=start.camera.origin+delta))
    else
      val shift=Point((delta.x.toDouble/start.camera.scale).lp,(delta.y.toDouble/start.camera.scale).lp)
      history.update(_.copy(graph=start.graph.copy(nodes=start.graph.nodes.map(n => if start.selected(n.id) then n.copy(at=n.at+shift) else n))))
  }
  private[diagram] def end(cancel: Boolean)(using UiTxn): Unit=gesture.get.foreach { start =>
    val h=history.get
    if cancel then {history.set(h.copy(graph=start.graph));cameraCell.set(start.camera)}
    else if !start.pan && h.graph!=start.graph then history.set(History(h.graph,(h.undo:+start.graph).takeRight(64)))
    gesture.set(None)
  }
  private val definition=new DiagramSurface(this)
  private val toolbar=Row(Button(delete),Button(connect)).spacing(6.lp).padding(6.lp)
  val view: View=Column(
    Text("Diagram editor").style(TextStyle.Heading),
    Text(panning.map(on => if on then "Pan mode: drag the canvas. Select mode: switch Pan mode off." else "Drag nodes; Shift selects more. Secondary drag pans; wheel zooms.")),
    Row(Button(add),Button(undo),Button(redo),Button(panMode),Button(zoomIn),Button(zoomOut),Button(reset)).spacing(6.lp),
    Layout.named("diagram-overlay",scene,DiagramOverlay)(
      Slot("canvas")->Element("diagram",ElementInputs(Signal.const(()),scene,scene,scene,Presentation.const(())),definition,focusable=true),
      Slot("tools")->toolbar).grow
  ).spacing(10.lp).padding(16.lp)

private object DiagramGeometry:
  val size=Size(132.lp,44.lp)
  def rect(node: DiagramNode): Rect=Rect(node.at,size)
  def screen(node: DiagramNode,camera: DiagramCamera): Rect=Rect(camera.screen(node.at),Size((132*camera.scale).lp,(44*camera.scale).lp))
  def visible(node: DiagramNode,camera: DiagramCamera,size: Size): Boolean=
    val r=screen(node,camera)
    r.origin.x.toDouble<size.width.toDouble && r.origin.y.toDouble<size.height.toDouble &&
      r.origin.x.toDouble+r.size.width.toDouble>0 && r.origin.y.toDouble+r.size.height.toDouble>0

private final class DiagramSurface(editor: DiagramEditor) extends Element[Unit,DiagramScene,DiagramScene,DiagramScene,Unit]:
  import DiagramGeometry.*
  override def textRequests(s: DiagramScene,@unused a: Unit,viewport: Size)(using @unused c: Committed): Seq[TextRequest]=
    s.graph.nodes.filter(visible(_,s.camera,viewport)).map(n => TextRequest(n.label,TextStyle.Label))
  def paint(s: DiagramScene,@unused a: Unit,viewport: Size,canvas: Canvas)(using @unused c: Committed): Unit=
    canvas.fill(Rect(Point.origin,viewport),canvas.theme.color(ColorRole.Surface))
    canvas.withTransform(Transform2D.camera(s.camera.origin,s.camera.scale)) {
      val nodes=s.graph.nodes.map(n => n.id->n).toMap
      s.graph.edges.foreach { e =>
        val from=nodes(e.from);val to=nodes(e.to)
        canvas.line(rect(from).center,rect(to).center,Color.rgba(110,132,160),1.5.lp)
      }
      s.graph.nodes.filter(visible(_,s.camera,viewport)).foreach { n =>
        val r=rect(n)
        canvas.fill(r,Color.rgba(40,53,72),6.lp)
        canvas.stroke(r,canvas.theme.color(if s.selected(n.id) then ColorRole.Accent else ColorRole.Muted),if s.selected(n.id) then 2.lp else 1.lp,6.lp)
        canvas.text(n.label,n.at+Point(12.lp,12.lp),TextStyle.Label,canvas.theme.color(ColorRole.OnSurface))
      }
    }
  override def hitTest(s: DiagramScene,@unused viewport: Size,at: Point)(using @unused c: Committed): Hit=
    s.graph.nodes.reverseIterator.find(n => rect(n).contains(s.camera.world(at))).fold(Hit.Self)(n => Hit.Part(PartId(n.id)))
  override def semantics(s: DiagramScene,@unused viewport: Size)(using @unused c: Committed): Seq[SemanticNode]=
    s.graph.nodes.zipWithIndex.map((n,i) => SemanticNode(PartId(n.id),Semantics(Role.GraphicNode,name=Some(n.label),
      selected=Some(s.selected(n.id)),positionInSet=Some((i+1,s.graph.nodes.size))),screen(n,s.camera),Set(SemanticAction.Activate,SemanticAction.Custom("Move right"))))
  override def pointer(e: PointerEvent)(using UiTxn): PointerResponse=e.phase match
    case PointerPhase.Down => editor.down(e)
    case PointerPhase.Move if e.captured => editor.move(e.position);PointerResponse.Handled
    case PointerPhase.Up if e.captured => editor.end(false);PointerResponse.Release
    case PointerPhase.Cancel if e.captured => editor.end(true);PointerResponse.Release
    case _ => PointerResponse.Ignore
  override def wheel(e: WheelEvent)(using UiTxn): Boolean=
    if !editor.dragging.get then editor.zoom(e.position,math.exp(math.max(-4,math.min(4,e.delta.y.toDouble))*0.12))
    true
  override def key(e: KeyEvent)(using UiTxn): Boolean=
    if e.key==Key.Escape then {editor.end(true);editor.select(Set.empty);true}
    else if editor.dragging.get then false
    else e.key match
      case Key.Character(c) if e.mods.has(Mods.Primary) && c.toLower=='z' =>
        (if e.mods.has(Mods.Shift) then editor.redo else editor.undo).dispatch(());true
      case Key.Backspace | Key.Delete => editor.delete.dispatch(());true
      case Key.ArrowLeft | Key.ArrowRight | Key.ArrowUp | Key.ArrowDown if editor.selection.get.nonEmpty =>
        val step=if e.mods.has(Mods.Shift) then 1.0 else 8.0
        val delta=e.key match
          case Key.ArrowLeft => Point((-step).lp,0.lp)
          case Key.ArrowRight => Point(step.lp,0.lp)
          case Key.ArrowUp => Point(0.lp,(-step).lp)
          case _ => Point(0.lp,step.lp)
        editor.moveSelection(delta);true
      case _ => false
  override def action(part: PartId,action: SemanticAction)(using UiTxn): Boolean=
    if editor.dragging.get || !editor.graph.get.nodes.exists(_.id==part.value) then false
    else action match
      case SemanticAction.Activate => editor.select(Set(part.value));true
      case SemanticAction.Custom("Move right") => editor.select(Set(part.value));editor.moveSelection(Point(8.lp,0.lp));true
      case _ => false

private object DiagramOverlay extends LayoutPolicy[DiagramScene]:
  def layout(s: DiagramScene,c: Constraints,ch: LayoutChildren)(using @unused committed: Committed): Size=
    val full=ch.measure(Slot("canvas"),c);ch.place(Slot("canvas"),Point.origin)
    s.graph.nodes.find(n => s.selected(n.id)).foreach { node =>
      val bar=ch.measure(Slot("tools"),c.loosen)
      val at=s.camera.screen(node.at)
      ch.place(Slot("tools"),Point(math.max(0,math.min(at.x.toDouble,full.width.toDouble-bar.width.toDouble)).lp,
        math.max(0,at.y.toDouble-bar.height.toDouble-6).lp))
    }
    full

object DiagramEditor:
  val sample: DiagramGraph=DiagramGraph(Vector(
    DiagramNode(1,"Stimulus",Point(40.lp,100.lp)),DiagramNode(2,"Encoder",Point(240.lp,70.lp)),
    DiagramNode(3,"Readout",Point(440.lp,150.lp))),Vector(DiagramEdge(1,2),DiagramEdge(2,3)))
  def grid(count: Int): DiagramGraph=
    require(count>=0 && count<=512,"diagram grid count must be between 0 and 512")
    DiagramGraph(Vector.tabulate(count)(i => DiagramNode(i.toLong+1,s"Node ${i+1}",Point(((i%16)*155+20).lp,((i/16)*70+20).lp))),
      Vector.tabulate(math.max(0,count-1))(i => DiagramEdge(i.toLong+1,i.toLong+2)))

@main def diagramEditor(): Unit=Application.run { app =>
  val editor=new DiagramEditor(app.child("diagram"))
  Windows.single("diagram")(_ => Window("Sirin Diagram",MenuBar(Menu("Edit",MenuItem(editor.undo),MenuItem(editor.redo),MenuItem(editor.delete))))(editor.view))
}
