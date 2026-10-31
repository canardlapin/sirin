package sirin.host

import sirin.*
import sirin.drawing.PreparedText
import sirin.graphics as g

class LayoutClippingSuite extends munit.FunSuite:
  test("node paint uses its complete placed clip once instead of restacking ancestor rectangles") {
    // The scroll surface paints its keyboard focus mark in both the base drawing and the overlay.
    val runtime=new sirin.engine.HeadlessRuntime
    val surface=ScrollSeam.surface(runtime)
    val id=surface.id
    val child=Placed(surface,Box(20,10,100,100),Box(20,20,40,40),Vector.empty,None)
    val root=Placed(Node(NodeId(Vector("root")),Content.Styled(Hint.Grow,child.node)),Box(0,0,100,100),Box(0,0,80,80),Vector(child),None)
    val layout=LayoutResult(root,Vector.empty,PreparedText.empty)
    val theme=new Theme:
      def reducedMotion=true
      def color(role: ColorRole)=if role==ColorRole.Accent then Color.rgba(100,150,200) else Color.rgba(20,25,35)
    val target=g.RenderTarget(new g.DeviceGeneration("test","clip"),100,100,g.Affine.identity)
    def prepared(drawing: g.Drawing)=g.DrawingPreparation.prepare(drawing,target).fold(e => fail(e.toString),identity)
    def clips(drawing: g.Drawing)=prepared(drawing).commands.collect {case g.PreparedCommand.Fill(_,_,_,cs) => cs}
    val base=clips(layout.drawing(theme,Some(id),true))
    assertEquals(base.map(_.size),Vector(0,1))
    val overlay=clips(layout.overlay(theme,Some(id),true,None))
    assertEquals(overlay.map(_.size),Vector(1))
    for clip <- Vector(base.last.head,overlay.head.head) do
      val points=clip.path.contours.flatten
      assertEquals((points.map(_.x).min,points.map(_.y).min,points.map(_.x).max,points.map(_.y).max),(20.0,20.0,60.0,60.0))
    runtime.close()
  }
