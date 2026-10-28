package sirin.graphics.examples

import sirin.graphics.*

/** A public-only image-viewer recording: one owned snapshot, reused with
  * different crops, filtering and placement. It does not depend on a decoder.
  */
object ImageSpecimen:
  def record(): Drawing =
    val pixels = Array(255, 0, 0, 255, 0, 255, 0, 255,
      0, 0, 255, 255, 255, 255, 255, 0).map(_.toByte)
    val image = ImageSnapshot.copyRgba8(2, 2, pixels)
    val fullSize = Drawing.record { c =>
      c.image(image.full, Rect(0, 0, 200, 200), Sampling.Nearest)
    }
    Drawing.record { c =>
      c.draw(fullSize)
      c.transformed(Affine.translation(220, 0)) { moved =>
        moved.image(ImageRegion(image, Rect(0.5, 0.5, 1, 1)), Rect(0, 0, 100, 100), Sampling.Linear)
      }
    }
