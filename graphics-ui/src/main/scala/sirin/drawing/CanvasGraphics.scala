package sirin.drawing

import sirin.Canvas
import sirin.graphics.Canvas2D

/** Public adapter capability. Alternative drawing hosts can implement it too. */
trait GraphicsCanvas extends Canvas:
  def graphics: Canvas2D

/** The complete drawing vocabulary inside an Element paint phase. Use the same Path, Paint, ImageRegion, Drawing and
  * Canvas2D scopes as standalone drawing. Coordinates accept the shared Lp unit; device conversion stays explicit.
  */
object CanvasGraphics:
  extension (canvas: Canvas)
    def graphics: Canvas2D =
      canvas match
        case target: GraphicsCanvas =>
          target.graphics
        case _ =>
          throw new UnsupportedOperationException("this canvas has no graphics recording capability")
