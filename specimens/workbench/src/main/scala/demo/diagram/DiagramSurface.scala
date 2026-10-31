package demo.diagram

import scala.annotation.unused
import sirin.*
import sirin.drawing.CanvasGraphics.*
import sirin.graphics as g

/** Pure drawing/hit/semantic definition; no model or owner-side handler is retained here. */
/** The presentation input is the drag lift, 0 to 1: paint only, so it never reaches hit testing or semantics. */
private[diagram] object DiagramSurface extends Element[Unit, DiagramScene, DiagramScene, DiagramScene, Double]:
  private val labelWidth = 108.lp
  private def visible(scene: DiagramScene, viewport: Size): Vector[DiagramNode] =
    scene.graph.nodes.filter(DiagramGeometry.visible(_, scene.camera, viewport))
  override def textRequests(scene: DiagramScene, @unused lift: Double, viewport: Size)(using
      @unused c: Committed
  ): Seq[TextRequest] =
    visible(scene, viewport).map(node => TextRequest(node.label, TextStyle.Label, labelWidth))

  /** Conservative cubic control-hull culling; the public renderer still owns curve flattening. */
  private[diagram] def edge(scene: DiagramScene, link: DiagramEdge, viewport: Size): Option[g.Path] =
    val from = scene.graph.index(link.from).at + Point(DiagramGeometry.nodeSize.width, 22.lp)
    val to = scene.graph.index(link.to).at + Point(0.lp, 22.lp)
    val dx = to.x.toDouble - from.x.toDouble
    val dy = to.y.toDouble - from.y.toDouble
    if dx == 0 && dy == 0 then return None
    val a = if math.abs(dx) < 1 then from + Point((dx / 3).lp, (dy / 3).lp)
    else Point(((from.x.toDouble + to.x.toDouble) / 2).lp, from.y)
    val b = if math.abs(dx) < 1 then to - Point((dx / 3).lp, (dy / 3).lp)
    else Point(((from.x.toDouble + to.x.toDouble) / 2).lp, to.y)
    val points = Vector(from, a, b, to).map(scene.camera.screen)
    val left = points.map(_.x.toDouble).min
    val top = points.map(_.y.toDouble).min
    val bounds = Rect(
      Point((left - 2).lp, (top - 2).lp),
      Size((points.map(_.x.toDouble).max - left + 4).lp, (points.map(_.y.toDouble).max - top + 4).lp)
    )
    Option.when(DiagramGeometry.intersects(bounds, viewport)) {
      g.Path.build { path =>
        path.moveTo(from.x, from.y)
        path.cubicTo(g.Point(a.x, a.y), g.Point(b.x, b.y), g.Point(to.x, to.y))
      }
    }

  def paint(scene: DiagramScene, lift: Double, viewport: Size, canvas: Canvas)(using Committed): Unit =
    canvas.fill(Rect(Point.origin, viewport), canvas.theme.color(ColorRole.Surface))
    canvas.clipped(Rect(Point.origin, viewport)) { clipped =>
      clipped.transformed(Transform2D.camera(scene.camera.origin, scene.camera.scale)) { world =>
        scene.graph.edges.flatMap(edge(scene, _, viewport)).foreach { path =>
          world.graphics.stroke(
            path,
            g.Paint.Solid(g.Color.rgba8(110, 145, 185)),
            g.StrokeStyle.logical(1.5.lp)
          )
        }
        visible(scene, viewport).foreach { node =>
          val bounds = DiagramGeometry.rect(node)
          val path = g.Path.roundedRectangle(
            g.Rect(
              node.at.x,
              node.at.y,
              DiagramGeometry.nodeSize.width,
              DiagramGeometry.nodeSize.height
            ),
            6.lp
          )
          if lift > 0 && scene.selected(node.id) then
            world.withOpacity(0.35 * lift) {
              world.withOffset(Point(0.lp, (4 * lift).lp)) {
                world.graphics.fill(path, g.Paint.Solid(g.Color.rgba8(0, 0, 0)))
              }
            }
          val paint = g.Paint.LinearGradient(
            g.Point(node.at.x, node.at.y),
            g.Point(node.at.x, node.at.y + DiagramGeometry.nodeSize.height),
            Vector(
              g.ColorStop(0, g.Color.rgba8(49, 70, 95)),
              g.ColorStop(1, g.Color.rgba8(30, 43, 61))
            )
          )
          world.graphics.fill(path, paint)
          world.stroke(
            bounds,
            world.theme.color(if scene.selected(node.id) then ColorRole.Accent else ColorRole.Border),
            if scene.selected(node.id) then 2.lp else 1.lp,
            6.lp
          )
          world.clipped(Rect(node.at + Point(12.lp, 8.lp), Size(labelWidth, 28.lp))) { label =>
            label.text(
              label.textServices.layout(node.label, TextStyle.Label, labelWidth),
              node.at + Point(12.lp, 8.lp),
              label.theme.color(ColorRole.OnSurface)
            )
          }
        }
      }
    }
  override def hitTest(scene: DiagramScene, viewport: Size, at: Point)(using @unused c: Committed): Hit =
    DiagramGeometry.hit(scene, viewport, at)
  override def semantics(scene: DiagramScene, viewport: Size)(using @unused c: Committed): Seq[SemanticNode] =
    val root = SemanticNode(
      PartId(0),
      Semantics(Role.Canvas, name = Some("Diagram")),
      Rect(Point.origin, viewport),
      Set.empty
    )
    root +: scene.graph.nodes.zipWithIndex.collect {
      case (node, index) if DiagramGeometry.visible(node, scene.camera, viewport) =>
        SemanticNode(
          PartId(node.id),
          Semantics(
            Role.GraphicNode,
            name = Some(node.label),
            selected = Some(scene.selected(node.id)),
            positionInSet = Some((index + 1, scene.graph.nodes.size))
          ),
          DiagramGeometry.screen(node, scene.camera),
          Set(SemanticAction.Activate, SemanticAction.Custom("Move right"))
        )
    }

private[diagram] final class DiagramHandlers(editor: DiagramEditor) extends ElementHandlers:
  override def pointer(event: PointerEvent)(using UiTxn): PointerResponse = event.phase match
    case PointerPhase.Down                   => editor.begin(event)
    case PointerPhase.Move if event.captured =>
      editor.move(event.position)
      PointerResponse.Handled
    case PointerPhase.Up if event.captured     => editor.release(event)
    case PointerPhase.Cancel if event.captured =>
      editor.cancelGesture()
      PointerResponse.Release
    case _ => PointerResponse.Ignore
  override def wheel(event: WheelEvent)(using UiTxn): Boolean =
    if !editor.dragging.get then
      editor.zoom(event.position, math.exp(math.max(-4, math.min(4, event.delta.y.toDouble)) * 0.12))
    true
  override def key(event: KeyEvent)(using UiTxn): Boolean =
    if event.key == Key.Escape then
      if editor.dragging.get then editor.cancelGesture() else editor.select(Set.empty)
      true
    else if editor.dragging.get then false
    else
      event.key match
        case Key.Character(c) if event.mods.has(Mods.Primary) && c.toLower == 'z' && !event.repeat =>
          (if event.mods.has(Mods.Shift) then editor.redo else editor.undo).dispatch(())
          true
        case Key.Backspace | Key.Delete if event.mods.isEmpty =>
          editor.delete.dispatch(())
          true
        case Key.ArrowLeft | Key.ArrowRight | Key.ArrowUp | Key.ArrowDown
            if (event.mods.isEmpty || event.mods == Mods.Shift) && editor.selection.get.nonEmpty =>
          val step = if event.mods.has(Mods.Shift) then 1.0 else 8.0
          val delta = event.key match
            case Key.ArrowLeft  => Point((-step).lp, 0.lp)
            case Key.ArrowRight => Point(step.lp, 0.lp)
            case Key.ArrowUp    => Point(0.lp, (-step).lp)
            case _              => Point(0.lp, step.lp)
          editor.moveSelection(delta)
          true
        case _ => false
  override def action(part: PartId, action: SemanticAction)(using UiTxn): Boolean =
    if editor.dragging.get || !editor.graph.get.index.contains(part.value) then false
    else
      action match
        case SemanticAction.Activate =>
          editor.select(Set(part.value))
          true
        case SemanticAction.Custom("Move right") =>
          editor.select(Set(part.value))
          editor.moveSelection(Point(8.lp, 0.lp))
          true
        case _ => false
