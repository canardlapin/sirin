package sirin.graphics.examples

import sirin.graphics.*

/** A public-only consumer: reusable diagram content with a selection overlay.
  * No theme, mounted component, device, or runtime required.
  */
object VectorSpecimen:
  def record(): Drawing =
    val frame = Path.rectangle(Rect(0, 0, 320, 200))
    val mark = Path.rectangle(Rect(-3, -3, 6, 6))
    val ink = Paint.Solid(Color.rgba8(30, 50, 90))
    val curve = Path.build { p =>
      p.moveTo(20, 100)
      p.cubicTo(Point(80, 0), Point(220, 200), Point(300, 100))
    }
    val background = Paint.LinearGradient(Point(0, 0), Point(320, 200), Vector(
      ColorStop(0, Color.rgba8(240, 245, 255)), ColorStop(1, Color.rgba8(210, 225, 245))
    ))
    val content = Drawing.record { c =>
      c.fill(frame, background)
      c.stroke(curve, ink, StrokeStyle(width = 1.5, dashes = Vector(6, 3), cap = LineCap.Round))
      c.instances(mark, InstanceBatch(
        Vector(Affine.translation(20, 100), Affine.translation(160, 100), Affine.translation(300, 100)),
        InstanceValues.Constant(ink)
      ))
    }
    Drawing.record { c =>
      c.clipped(frame) { clipped =>
        clipped.draw(content)
        clipped.layer(0.4) { overlay =>
          overlay.transformed(Affine.translation(160, 100)) { selected =>
            selected.stroke(mark, Paint.Solid(Color.rgba8(255, 100, 0)), StrokeStyle(width = 3))
          }
        }
      }
    }
