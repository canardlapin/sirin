package demo.diagram

import scala.annotation.unused
import sirin.*

private[diagram] object DiagramView:
  def apply(editor: DiagramEditor): View =
    val tools = Row(Button(editor.delete), Button(editor.connect)).spacing(6.lp).padding(6.lp)
    Column(
      Text("Diagram editor").style(TextStyle.Heading),
      Text(editor.panning.map(on =>
        if on then "Pan mode: drag the canvas. Toggle Pan mode to select nodes."
        else "Drag nodes · Shift selects more · Secondary drag pans · Wheel zooms · Escape cancels"
      )),
      Row(
        Button(editor.add),
        Button(editor.undo),
        Button(editor.redo),
        Button(editor.panMode),
        Button(editor.zoomIn),
        Button(editor.zoomOut),
        Button(editor.reset),
        Button(editor.cancel)
      ).spacing(6.lp),
      Layout.named("diagram-overlay", editor.scene, DiagramOverlay)(
        Slot("canvas") -> editor.surface,
        Slot("tools") -> tools
      ).grow
    ).spacing(10.lp).padding(16.lp)

private[diagram] object DiagramOverlay extends LayoutPolicy[DiagramScene]:
  def layout(scene: DiagramScene, constraints: Constraints, children: LayoutChildren)(using
      @unused c: Committed
  ): Size =
    val viewport = children.measure(Slot("canvas"), constraints)
    children.place(Slot("canvas"), Point.origin)
    scene.graph.nodes.find(n => scene.selected(n.id) && DiagramGeometry.visible(n, scene.camera, viewport))
      .foreach { node =>
        val tools = children.measure(Slot("tools"), constraints.loosen)
        val at = scene.camera.screen(node.at)
        children.place(
          Slot("tools"),
          Point(
            math.max(0, math.min(at.x.toDouble, viewport.width.toDouble - tools.width.toDouble)).lp,
            math.max(
              0,
              math.min(
                at.y.toDouble - tools.height.toDouble - 6,
                viewport.height.toDouble - tools.height.toDouble
              )
            ).lp
          )
        )
      }
    viewport
