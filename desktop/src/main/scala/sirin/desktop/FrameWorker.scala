package sirin.desktop

import java.nio.file.{Files,Path}
import sirin.*
import sirin.graphics.{Affine,DrawingPreparation,FontData,ImageSnapshot,ParagraphOptions}
import sirin.graphics.text.{NativeFont,NativeParagraph}
import sirin.graphics.unicode.NativeUnicode
import sirin.host.*
import sirin.render.OffscreenGpu
import sirin.graphics.desktop.TiledRenderer
import sirin.text.ResolvedTextLayout

final case class RenderedFrame(request: FrameRequest, layout: LayoutResult, pixels: ImageSnapshot,
    programBytes: Long, readbackBytes: Long, maskBytes: Long, workerNanos: Long, worker: String, tiles: Int, capture: Path)

object DevelopmentTheme extends Theme:
  def reducedMotion: Boolean = true
  def color(role: ColorRole): Color = role match
    case ColorRole.Surface => Color.rgba(20,25,35)
    case ColorRole.OnSurface => Color.rgba(230,233,239)
    case ColorRole.Muted => Color.rgba(158,170,190)
    case ColorRole.Accent => Color.rgba(115,185,248)
    case _ => Color.rgba(215,223,238)

/** Every native owner is created/closed on this Scala worker, including on failure. */
object FrameWorker:
  def render(request: FrameRequest, fontRoot: Path, captureRoot: Path)(using cancellation: Cancellation): RenderedFrame =
    val began=System.nanoTime()
    def check(): Unit = if cancellation.cancelled then throw new InterruptedException("obsolete frame")
    check()
    val payload=request.payload
    val bytes=Vector("NotoSans-Regular.ttf","NotoSansHebrew-Regular.ttf","NotoNaskhArabic-Regular.ttf","NotoSansDevanagari-Regular.ttf")
      .map(name => FontData.copy(Files.readAllBytes(fontRoot.resolve(name))))
    val unicode=NativeUnicode.open()
    val faces=scala.collection.mutable.Map.empty[TextStyle,Vector[NativeFont]]
    def configured(style: TextStyle): Vector[NativeFont] = faces.getOrElseUpdate(style, {
      val size=style match
        case TextStyle.Title => 28.0
        case TextStyle.Heading => 19.0
        case TextStyle.Caption => 12.0
        case _ => 14.0
      val opened=scala.collection.mutable.ArrayBuffer.empty[NativeFont]
      try { bytes.foreach(data => opened+=NativeFont.open(data,size)); opened.toVector }
      catch case e: Throwable => { opened.foreach(_.close()); throw e }
    })
    try
      val layout=sirin.host.Layout(payload.tree,payload.logicalWidth,payload.logicalHeight,payload.scrolls) { text =>
        check()
        ResolvedTextLayout(NativeParagraph.wrap(text.content,configured(text.style),unicode,text.maxWidth.toDouble,ParagraphOptions()))
      }
      check()
      val drawing=layout.drawing(DevelopmentTheme)
      val gpu=OffscreenGpu.open()
      try
        val target=gpu.target(payload.pixelWidth,payload.pixelHeight,1).copy(logicalToDevice=Affine.scale(
          payload.pixelWidth/payload.logicalWidth,payload.pixelHeight/payload.logicalHeight))
        val prepared=DrawingPreparation.prepare(drawing,target).fold(error => throw new IllegalStateException(error.toString),identity)
        val tiled=TiledRenderer.render(gpu,prepared)(() => cancellation.cancelled)
        check()
        val capture=captureRoot.resolve(s"frame-${request.generation}.png")
        save(tiled.image,capture)
        RenderedFrame(request,layout,tiled.image,tiled.programBytes,tiled.readbackBytes,tiled.maskBytes,System.nanoTime()-began,Thread.currentThread().getName,tiled.tiles,capture)
      finally gpu.close()
    finally
      faces.valuesIterator.flatten.foreach(_.close())
      unicode.close()

  private def save(pixels: ImageSnapshot,file: Path): Unit =
    val bytes=pixels.pixelsCopy
    val image=new java.awt.image.BufferedImage(pixels.width,pixels.height,java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for y <- 0 until pixels.height; x <- 0 until pixels.width do
      val i=(y*pixels.width+x)*4
      image.setRGB(x,y,((bytes(i+3)&255)<<24)|((bytes(i)&255)<<16)|((bytes(i+1)&255)<<8)|(bytes(i+2)&255))
    Files.createDirectories(file.getParent)
    require(javax.imageio.ImageIO.write(image,"png",file.toFile),"PNG writer unavailable")
