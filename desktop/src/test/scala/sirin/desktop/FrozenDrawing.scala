package sirin.desktop

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.nio.file.{Files, Path}
import sirin.graphics.*
import sirin.graphics.desktop.TileCache
import sirin.graphics.text.NativeFont
import sirin.host.{FrameRequest, LayoutResult}
import sirin.render.OffscreenGpu

/** Trusted, same-build diagnostic artifact. Contains no element code, UI handlers or graph. */
final case class FrozenDrawing(width: Int, height: Int, scale: Affine, base: Drawing, overlay: Drawing)

object FrozenDrawing:
  def save(path: Path, request: FrameRequest, layout: LayoutResult): FrozenDrawing =
    val p = request.payload
    val frozen = FrozenDrawing(
      p.pixelWidth,
      p.pixelHeight,
      Affine.scale(p.pixelWidth / p.logicalWidth, p.pixelHeight / p.logicalHeight),
      layout.drawing(DevelopmentTheme, fieldFocus = p.focus),
      layout.overlay(DevelopmentTheme, p.focus, p.keyboardFocus, p.pressed)
    )
    Files.createDirectories(path.getParent)
    val output = new ObjectOutputStream(Files.newOutputStream(path))
    try output.writeObject(frozen)
    finally output.close()
    frozen

  def read(path: Path): FrozenDrawing =
    val input = new ObjectInputStream(Files.newInputStream(path))
    try input.readObject().asInstanceOf[FrozenDrawing]
    finally input.close()

  /** Repeat the reference path with fresh fonts, device, preparation and tile caches. */
  def replay(value: FrozenDrawing): ImageSnapshot =
    val gpu = OffscreenGpu.open()
    val fonts = scala.collection.mutable.Map.empty[ResolvedFont, NativeFont]
    def raster(font: ResolvedFont, id: Int, scale: Double, budget: Int): GlyphRaster =
      val face = fonts.getOrElseUpdate(font, NativeFont.open(font.data, font.size, font.faceIndex))
      require(
        face.font.metrics == font.metrics && face.font.shaperVersion == font.shaperVersion &&
          face.font.rasterizerVersion == font.rasterizerVersion,
        "frozen font build mismatch"
      )
      face.rasterize(id, scale, maxPixels = budget)
    val base = new TileCache(gpu, raster, 128, 32, 24L * 1024 * 1024)
    val composite = new TileCache(gpu, raster, 128, 32, 24L * 1024 * 1024)
    try
      val target = gpu.target(value.width, value.height, 1).copy(logicalToDevice = value.scale)
      def prepare(drawing: Drawing): PreparedDrawing = DrawingPreparation
        .prepare(drawing, target)
        .fold(e => throw new IllegalArgumentException(e.toString), identity)
      base.render(prepare(value.base), () => false)
      val inverse = value.scale.inverse.get
      val drawing = Drawing.record { canvas =>
        base
          .snapshots
          .foreach { (rect, image) =>
            val origin = inverse(Point(rect.x, rect.y))
            val end = inverse(Point(rect.x + rect.width, rect.y + rect.height))
            canvas.image(image.full, Rect(origin.x, origin.y, end.x - origin.x, end.y - origin.y), Sampling.Nearest)
          }
        canvas.draw(value.overlay)
      }
      composite.render(prepare(drawing), () => false).image
    finally
      composite.close()
      base.close()
      fonts.values.foreach(_.close())
      gpu.close()
