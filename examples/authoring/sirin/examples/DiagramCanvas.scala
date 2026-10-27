// A-0 authoring corpus, program 6: a custom interactive surface built only on
// the public extension API (Element, Layout, Canvas, semantics).
//
// Exercises: custom paint and hit testing, pointer capture for dragging,
// click and shift-click selection, keyboard nudging, accessible semantic
// children with actions, a presentation animation (drag lift), and a custom
// layout that floats a toolbar over the selected node.
package sirin.examples.diagram

import sirin.*

final case class NodeId(value: Int)
final case class Node(id: NodeId, label: String, at: Point)
final case class Edge(from: NodeId, to: NodeId)

/** The diagram model; owned by a workspace scope. */
final class Diagram(scope: Scope):
  val nodes: KeyedCell[NodeId, Node] = scope.keyed(
    Seq(
      Node(NodeId(1), "Stimulus", Point(40.lp, 60.lp)),
      Node(NodeId(2), "Encoder", Point(220.lp, 40.lp)),
      Node(NodeId(3), "Readout", Point(400.lp, 120.lp))
    )
  )(_.id)
  val edges: Cell[Seq[Edge]]       = scope.cell(Seq(Edge(NodeId(1), NodeId(2)), Edge(NodeId(2), NodeId(3))))
  val selection: Cell[Set[NodeId]] = scope.cell(Set.empty[NodeId])

  val deleteSelection: Command[Unit, Unit] =
    scope.action("diagram.delete", "Delete", enabled = selection.map(_.nonEmpty)) { _ =>
      val gone = selection.get
      gone.foreach(nodes.remove)
      edges.update(_.filterNot(e => gone(e.from) || gone(e.to)))
      selection.set(Set.empty)
    }

  def moveSelected(by: Point)(using UiTxn): Unit =
    selection.get.foreach(id => nodes.update(id)(n => n.copy(at = n.at + by)))

object Geometry:
  val nodeSize: Size            = Size(132.lp, 44.lp)
  def rect(n: Node): Rect       = Rect(n.at, nodeSize)
  def part(id: NodeId): PartId  = PartId(id.value.toLong)
  def node(p: PartId): NodeId   = NodeId(p.value.toInt)

/** Model inputs exclude lift; lift affects paint only through Presentation. */
final case class Scene(nodes: IndexedSeq[Node], edges: Seq[Edge], selected: Set[NodeId], dragging: Option[NodeId])

final case class Drag(node: NodeId, grab: Point)

final class DiagramElement(diagram: Diagram, drag: Cell[Option[Drag]], focus: NodeRef) extends Element[Unit, Scene, IndexedSeq[Node], Scene, Double]:
  import Geometry.*

  override def paint(s: Scene, lift: Double, size: Size, canvas: Canvas)(using Committed): Unit =
    val t = canvas.theme
    canvas.fill(Rect(Point.origin, size), t.color(ColorRole.Surface))
    val byId = s.nodes.map(n => n.id -> n).toMap
    for e <- s.edges; a <- byId.get(e.from); b <- byId.get(e.to) do
      canvas.line(rect(a).center, rect(b).center, t.color(ColorRole.Border), 1.5.lp)
    for n <- s.nodes do
      val r      = rect(n)
      val lifted = if s.dragging.contains(n.id) then lift else 0.0
      if lifted > 0 then
        canvas.withOpacity(0.25 * lifted) {
          canvas.fill(r.copy(origin = r.origin + Point(0.lp, (4 * lifted).lp)), t.color(ColorRole.OnSurface), 6.lp)
        }
      canvas.fill(r, t.color(ColorRole.SurfaceRaised), 6.lp)
      canvas.stroke(r, t.color(if s.selected(n.id) then ColorRole.Selection else ColorRole.Border), if s.selected(n.id) then 2.lp else 1.lp, 6.lp)
      canvas.text(n.label, r.origin + Point(12.lp, 14.lp), TextStyle.Label, t.color(ColorRole.OnSurface))

  /** Topmost node wins; the background is the element itself. */
  override def hitTest(nodes: IndexedSeq[Node], size: Size, at: Point)(using Committed): Hit =
    nodes.reverseIterator.find(n => rect(n).contains(at)).fold(Hit.Self)(n => Hit.Part(part(n.id)))

  override def semantics(s: Scene, size: Size)(using Committed): Seq[SemanticNode] =
    s.nodes.zipWithIndex.map { (n, i) =>
      SemanticNode(
        part(n.id),
        Semantics(Role.GraphicNode, name = Some(n.label), selected = Some(s.selected(n.id)), positionInSet = Some((i + 1, s.nodes.size))),
        rect(n),
        Set(SemanticAction.Activate, SemanticAction.Custom("Move right"), SemanticAction.Custom("Move down"))
      )
    }

  override def pointer(e: PointerEvent)(using UiTxn): PointerResponse =
    e.phase match
      case PointerPhase.Down if e.button == PointerButton.Primary =>
        e.hit match
          case Hit.Part(p) =>
            val id = node(p)
            focus.focusPart(p)
            if e.mods.has(Mods.Shift) then diagram.selection.update(sel => if sel(id) then sel - id else sel + id)
            else if !diagram.selection.get(id) then diagram.selection.set(Set(id))
            diagram.nodes.get(id).foreach(n => drag.set(Some(Drag(id, e.position - n.at))))
            PointerResponse.Capture
          case _ =>
            diagram.selection.set(Set.empty)
            PointerResponse.Handled
      case PointerPhase.Move if e.captured =>
        // One transaction per delivered move after declared source coalescing.
        drag.get.foreach(d => diagram.nodes.update(d.node)(_.copy(at = e.position - d.grab)))
        PointerResponse.Handled
      case PointerPhase.Up | PointerPhase.Cancel if e.captured =>
        drag.set(None)
        PointerResponse.Release
      case _ =>
        PointerResponse.Ignore

  override def key(e: KeyEvent)(using UiTxn): Boolean =
    val step = if e.mods.has(Mods.Shift) then 1.lp else 8.lp
    val by = e.key match
      case Key.ArrowLeft  => Some(Point(Lp.zero - step, Lp.zero))
      case Key.ArrowRight => Some(Point(step, Lp.zero))
      case Key.ArrowUp    => Some(Point(Lp.zero, Lp.zero - step))
      case Key.ArrowDown  => Some(Point(Lp.zero, step))
      case _              => None
    by.exists { d =>
      diagram.moveSelected(d)
      diagram.selection.get.nonEmpty
    }

  override def action(p: PartId, a: SemanticAction)(using UiTxn): Boolean =
    val id = node(p)
    a match
      case SemanticAction.Activate =>
        diagram.selection.set(Set(id)); focus.focusPart(p); true
      case SemanticAction.Custom("Move right") =>
        diagram.nodes.update(id)(n => n.copy(at = n.at + Point(8.lp, Lp.zero))); true
      case SemanticAction.Custom("Move down") =>
        diagram.nodes.update(id)(n => n.copy(at = n.at + Point(Lp.zero, 8.lp))); true
      case _ => false

/** Child 0 fills the area; child 1 floats above the anchor rectangle. */
object FloatAbove extends LayoutPolicy[Option[Rect]]:
  def layout(anchor: Option[Rect], c: Constraints, children: LayoutChildren)(using Committed): Size =
    val full = children.measure(Slot("canvas"), c)
    children.place(Slot("canvas"), Point.origin)
    if anchor.isDefined then
      val bar = children.measure(Slot("toolbar"), c.loosen)
      // No anchor leaves the toolbar mounted but unplaced and inaccessible.
      val at = anchor.fold(Point.origin)(r => Point(r.origin.x, (r.origin.y - bar.height - 6.lp).max(Lp.zero)))
      children.place(Slot("toolbar"), at)
    full

val DiagramView = Component.withDeps[Diagram, Unit]("DiagramView") { (d, scope, _) =>
  val drag = scope.cell(Option.empty[Drag])
  val focus = scope.ref()
  val lift = scope.animate(drag.map(d => if d.isDefined then 1.0 else 0.0), Transition.quick)
  val scene: Signal[Scene] =
    (d.nodes.snapshot, d.edges, d.selection).mapN((ns, es, sel) => (ns, es, sel))
      .zip(drag.map(_.map(_.node)))
      .map { case ((ns, es, sel), dragging) => Scene(ns, es, sel, dragging) }
  val anchor: Signal[Option[Rect]] =
    (d.nodes.snapshot, d.selection).mapN((ns, sel) => ns.find(n => sel(n.id)).map(Geometry.rect))
  Layout.named("FloatAbove", anchor, FloatAbove)(
    Slot("canvas") -> Element("Diagram",
      ElementInputs(Signal.const(()), scene, d.nodes.snapshot, scene, lift),
      DiagramElement(d, drag, focus), focusable = true).ref(focus)
        .semantics(focus.focusedPart.map(part => Semantics(Role.Canvas, name = Some("Diagram"),
          activeDescendant = part.map(focus.part))))
        .shortcut(Chord(Key.Backspace), d.deleteSelection),
    Slot("toolbar") -> Row(Button(d.deleteSelection)).padding(Spacing.xsmall)
      .semantics(Semantics(Role.Toolbar, name = Some("Node actions")))
  )
}

@main def diagramCanvas(): Unit =
  Application.run { app =>
    val diagram = Diagram(app.child("diagram"))
    Windows.single("diagram")(_ => Window("Diagram", MenuBar(Menu("Edit", MenuItem(diagram.deleteSelection))))(DiagramView.bind(diagram).view))
  }
