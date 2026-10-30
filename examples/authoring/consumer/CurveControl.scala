package consumer

import scala.annotation.unused
import sirin.*
import sirin.drawing.CanvasGraphics.*
import sirin.graphics as g

/** Deliberately outside the sirin package: no implementation-private capabilities. */
object CurveControl:
  def apply(selected: Cell[Boolean]): View =
    val part = PartId(0)
    val curve = g
      .Path
      .build { path =>
        path.moveTo(4.lp, 28.lp)
        path.cubicTo(g.Point(12.lp, 0.lp), g.Point(28.lp, 48.lp), g.Point(44.lp, 12.lp))
      }
    Element.simple(
      "curve-control",
      selected,
      new Element.Simple[Boolean]:
        override def measure(
            @unused
            state: Boolean,
            constraints: Constraints
        )(using
            @unused
            c: Committed
        ): Size = Size(
          48.lp.max(constraints.minWidth).min(constraints.maxWidth),
          40.lp.max(constraints.minHeight).min(constraints.maxHeight)
        )
        def paint(state: Boolean, size: Size, canvas: Canvas)(using
            @unused
            c: Committed
        ): Unit =
          val paint = g
            .Paint
            .LinearGradient(
              g.Point(0.lp, 0.lp),
              g.Point(size.width, size.height),
              Vector(g.ColorStop(0, g.Color.rgba8(30, 80, 160)), g.ColorStop(1, g.Color.rgba8(100, 220, 220)))
            )
          canvas
            .graphics
            .stroke(
              curve,
              paint,
              g.StrokeStyle
                .logical(
                  if state then
                    4.lp
                  else
                    2.lp
                )
            )
        override def activation(
            @unused
            state: Boolean
        )(using
            @unused
            c: Committed
        ): Option[ElementActivation] = Some(ElementActivation(part, enabled = true))
        override def semantics(state: Boolean, size: Size)(using
            @unused
            c: Committed
        ): Seq[SemanticNode] = Seq(
          SemanticNode(
            part,
            Semantics(Role.Button, name = Some("Select curve"), selected = Some(state)),
            Rect(Point.origin, size),
            Set(SemanticAction.Activate)
          )
        )
        override def action(target: PartId, action: SemanticAction)(using UiTxn): Boolean =
          if target != part || action != SemanticAction.Activate then
            false
          else
            selected.update(!_)
            true
      ,
      focusable = true
    )
