package sirin.desktop

import java.nio.file.{Files,Path}
import sirin.*
import sirin.graphics.{Affine,DrawingPreparation,FontData,ImageSnapshot,ParagraphOptions}
import sirin.graphics.text.{NativeFont,NativeParagraph}
import sirin.graphics.unicode.NativeUnicode
import sirin.host.*
import sirin.render.OffscreenGpu
import sirin.text.ResolvedTextLayout

sealed trait FrameOutput:
  def request: FrameRequest
  def layout: LayoutResult
final case class LayoutFeedback(request: FrameRequest,layout: LayoutResult) extends FrameOutput
final case class RenderedFrame(request: FrameRequest, layout: LayoutResult, pixels: ImageSnapshot,
    programBytes: Long, readbackBytes: Long, maskBytes: Long, workerNanos: Long, worker: String, tiles: Int, capture: Path,profile: String="",queueNanos: Long=0,sessionSetupNanos: Long=0) extends FrameOutput

object DevelopmentTheme extends Theme:
  def reducedMotion: Boolean = true
  def color(role: ColorRole): Color = role match
    case ColorRole.Surface => Color.rgba(20,25,35)
    case ColorRole.OnSurface => Color.rgba(230,233,239)
    case ColorRole.Muted => Color.rgba(158,170,190)
    case ColorRole.Accent => Color.rgba(115,185,248)
    case _ => Color.rgba(215,223,238)

trait RenderBackend extends AutoCloseable:
  def render(request: FrameRequest,cancelled: () => Boolean): FrameOutput
  def stats: String

/** Long-lived native ownership stays entirely on its dedicated Scala-created thread. */
final class RenderSession(fontRoot: Path,captureRoot: Path,tileEdge: Int=128) extends RenderBackend:
  require(tileEdge>=32 && tileEdge<=192,"tile edge budget")
  private val unicode=NativeUnicode.open()
  private val faces=scala.collection.mutable.Map.empty[TextStyle,Vector[NativeFont]]
  private val extraFaces=scala.collection.mutable.Map.empty[sirin.graphics.ResolvedFont,NativeFont]
  private val layouts=scala.collection.mutable.LinkedHashMap.empty[sirin.drawing.TextRequest,ResolvedTextLayout]
  private val masks=scala.collection.mutable.LinkedHashMap.empty[(sirin.graphics.ResolvedFont,Int,Double),sirin.graphics.GlyphRaster]
  private var maskBytes=0L
  private lazy val bytes=Vector("NotoSans-Regular.ttf","NotoSansHebrew-Regular.ttf","NotoNaskhArabic-Regular.ttf","NotoSansDevanagari-Regular.ttf")
    .map(name => FontData.copy(Files.readAllBytes(fontRoot.resolve(name))))
  private val gpu=try OffscreenGpu.open() catch case e: Throwable => {unicode.close();throw e}
  private val tiles=new sirin.graphics.desktop.TileCache(gpu,raster,tileEdge,32,24L*1024*1024,onImageEvicted=image => composite.invalidateSource(image))
  private val composite=new sirin.graphics.desktop.TileCache(gpu,raster,tileEdge,32,24L*1024*1024)
  private var previousBase=Option.empty[(FramePayload,LayoutResult,sirin.graphics.RenderTarget)]
  var shaped=0L
  private def configured(style: TextStyle): Vector[NativeFont] = faces.getOrElseUpdate(style, {
    val size=style match
      case TextStyle.Title => 28.0
      case TextStyle.Heading => 19.0
      case TextStyle.Caption => 12.0
      case _ => 14.0
    val opened=scala.collection.mutable.ArrayBuffer.empty[NativeFont]
    try {bytes.foreach(data => opened+=NativeFont.open(data,size));opened.toVector}
    catch case e: Throwable => {opened.foreach(_.close());throw e}
  })
  private def raster(font: sirin.graphics.ResolvedFont,id: Int,scale: Double,budget: Int): sirin.graphics.GlyphRaster =
    val key=(font,id,scale)
    masks.remove(key) match
      case Some(mask) =>
        require(mask.byteSize<=budget,"glyph mask budget")
        masks(key)=mask;mask
      case None =>
        val face=faces.valuesIterator.flatten.find(_.font eq font).getOrElse(extraFaces.getOrElseUpdate(font,NativeFont.open(font.data,font.size,font.faceIndex)))
        require(face.font.metrics==font.metrics && face.font.shaperVersion==font.shaperVersion && face.font.rasterizerVersion==font.rasterizerVersion,"resolved font mismatch")
        val mask=face.rasterize(id,scale,maxPixels=budget)
        while masks.nonEmpty && (masks.size>=4096 || maskBytes+mask.byteSize>16L*1024*1024) do
          val old=masks.remove(masks.head._1).get;maskBytes-=old.byteSize
        require(mask.byteSize<=16L*1024*1024,"session mask budget")
        masks(key)=mask;maskBytes+=mask.byteSize;mask
  def render(request: FrameRequest,cancelled: () => Boolean): FrameOutput =
    val began=System.nanoTime()
    def check(): Unit = if cancelled() then throw new InterruptedException("obsolete frame")
    check()
    val payload=request.payload
    val key=payload.copy(focus=None,keyboardFocus=false,pressed=None)
    var layoutNanos=0L;var drawingNanos=0L
    val (layout,target,charges,baseProfile)=previousBase match
      case Some((prior,layout,target)) if prior==key =>
        (layout,target,(0L,0L,0L),"reused=true")
      case _ =>
        previousBase=None
        val layout=sirin.host.Layout(payload.tree,payload.logicalWidth,payload.logicalHeight,payload.scrolls) { text =>
          check()
          layouts.remove(text) match
            case Some(value) => layouts(text)=value;value
            case None =>
              val value=ResolvedTextLayout(NativeParagraph.wrap(text.content,configured(text.style),unicode,text.maxWidth.toDouble,ParagraphOptions()))
              if layouts.size>=256 then layouts.remove(layouts.head._1)
              layouts(text)=value;shaped+=1;value
        }
        check()
        if layout.viewports.exists(v => !payload.viewports.get(v.instance).contains(v.viewport)) then return LayoutFeedback(request,layout)
        val layoutAt=System.nanoTime();layoutNanos=layoutAt-began
        val drawing=layout.drawing(DevelopmentTheme)
        val target=gpu.target(payload.pixelWidth,payload.pixelHeight,1).copy(logicalToDevice=Affine.scale(
          payload.pixelWidth/payload.logicalWidth,payload.pixelHeight/payload.logicalHeight))
        val prepared=DrawingPreparation.prepare(drawing,target).fold(error => throw new IllegalStateException(error.toString),identity)
        drawingNanos=System.nanoTime()-layoutAt
        val base=tiles.render(prepared,cancelled)
        previousBase=Some((key,layout,target))
        (layout,target,(base.programBytes,base.readbackBytes,base.maskBytes),tiles.lastProfile)
    val scale=target.logicalToDevice
    val inverse=scale.inverse.get
    val overlay=layout.overlay(DevelopmentTheme,payload.focus,payload.keyboardFocus,payload.pressed)
    val composed=sirin.graphics.Drawing.record { canvas =>
      tiles.snapshots.foreach { (rect,image) =>
        val origin=inverse(sirin.graphics.Point(rect.x,rect.y))
        val end=inverse(sirin.graphics.Point(rect.x+rect.width,rect.y+rect.height))
        canvas.image(image.full,sirin.graphics.Rect(origin.x,origin.y,end.x-origin.x,end.y-origin.y),sirin.graphics.Sampling.Nearest)
      }
      canvas.draw(overlay)
    }
    val preparedComposite=DrawingPreparation.prepare(composed,target).fold(e => throw new IllegalStateException(e.toString),identity)
    val upper=composite.render(preparedComposite,cancelled)
    val result=upper.copy(programBytes=charges._1+upper.programBytes,readbackBytes=charges._2+upper.readbackBytes,maskBytes=charges._3+upper.maskBytes)
    check()
    val capture=captureRoot.resolve(s"frame-${request.generation}.png")
    if java.lang.Boolean.getBoolean("sirin.capture") then FrameWorker.save(result.image,capture)
    RenderedFrame(request,layout,result.image,result.programBytes,result.readbackBytes,result.maskBytes,System.nanoTime()-began,Thread.currentThread().getName,result.tiles,capture,s"layoutMs=${layoutNanos/1e6} drawingMs=${drawingNanos/1e6} base[$baseProfile] overlay[${composite.lastProfile}]")
  def stats: String = s"shaped=$shaped tileHits=${tiles.hits} tileMisses=${tiles.misses} baseCache=${tiles.stats} compositeCache=${composite.stats} resources=${gpu.resourceStats}"
  def close(): Unit =
    try {tiles.close();composite.close()}
    finally
      try gpu.close()
      finally
        faces.valuesIterator.flatten.foreach(_.close());extraFaces.values.foreach(_.close());unicode.close()
        layouts.clear();masks.clear();faces.clear();extraFaces.clear();previousBase=None

object FrameWorker:
  def render(request: FrameRequest,fontRoot: Path,captureRoot: Path)(using cancellation: Cancellation): FrameOutput =
    val session=new RenderSession(fontRoot,captureRoot)
    try session.render(request,() => cancellation.cancelled) finally session.close()
  private[desktop] def save(pixels: ImageSnapshot,file: Path): Unit =
    val bytes=pixels.pixelsCopy
    val image=new java.awt.image.BufferedImage(pixels.width,pixels.height,java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val packed=Array.tabulate(pixels.width*pixels.height) { p =>
      val i=p*4;((bytes(i+3)&255)<<24)|((bytes(i)&255)<<16)|((bytes(i+1)&255)<<8)|(bytes(i+2)&255)
    }
    image.setRGB(0,0,pixels.width,pixels.height,packed,0,pixels.width)
    Files.createDirectories(file.getParent)
    require(javax.imageio.ImageIO.write(image,"png",file.toFile),"PNG writer unavailable")

/** One replaceable pending frame and one replaceable immutable result; no idle polling.
  * This is a backend render service, not an application Work policy.
  */
final class RenderService(fontRoot: Path,captureRoot: Path,wake: () => Unit,factory: Option[() => RenderBackend]=None) extends AutoCloseable:
  private val queue=new java.util.concurrent.ArrayBlockingQueue[Option[(FrameRequest,Long)]](1)
  private val result=new java.util.concurrent.atomic.AtomicReference[Option[(Long,Either[Throwable,FrameOutput])]](None)
  private val latest=new java.util.concurrent.atomic.AtomicLong(0)
  private val stopped=new java.util.concurrent.atomic.AtomicBoolean(false)
  private val fatal=new java.util.concurrent.atomic.AtomicReference[Throwable](null)
  private val closeFailure=new java.util.concurrent.atomic.AtomicReference[Throwable](null)
  private val thread=new Thread(() => loop(),"sirin-render-owner")
  thread.setDaemon(true);thread.start()
  private def loop(): Unit =
    var session=Option.empty[RenderBackend]
    try
      val setupStarted=System.nanoTime()
      session=Some(factory.fold[RenderBackend](new RenderSession(fontRoot,captureRoot))(_()))
      val setupNanos=System.nanoTime()-setupStarted
      var running=true
      while running do
        queue.take() match
          case None => running=false
          case Some((request,submittedAt)) =>
            val startedAt=System.nanoTime()
            val value=try
              val rendered=session.get.render(request,() => stopped.get() || latest.get()!=request.generation)
              Right(rendered match
                case frame: RenderedFrame => frame.copy(queueNanos=startedAt-submittedAt,sessionSetupNanos=setupNanos)
                case other => other)
            catch case e: Throwable => Left(e)
            val notify=synchronized {
              if !stopped.get() && latest.get()==request.generation then {result.set(Some((request.generation,value)));true} else false
            }
            if notify then wake()
    catch case e: Throwable =>
      synchronized {fatal.set(e);result.set(Some((latest.get(),Left(e))))}
      if !stopped.get() then wake() else closeFailure.set(e)
    finally
      session.foreach { s =>
        try println(s"renderSession ${s.stats}")
        catch case e: Throwable => closeFailure.set(e)
        finally
          try s.close() catch case e: Throwable => closeFailure.set(e)
      }
  def submit(request: FrameRequest): Unit = synchronized {
    require(!stopped.get(),"renderer retired")
    Option(fatal.get()).foreach(throw _)
    require(thread.isAlive,"render owner terminated")
    latest.set(request.generation);queue.clear();result.set(None);require(queue.offer(Some((request,System.nanoTime()))))
  }
  def poll(): Option[(Long,Either[Throwable,FrameOutput])] = result.getAndSet(None)
  private[desktop] def awaitStopped(millis: Long): Boolean = {thread.join(millis); !thread.isAlive}
  def close(): Unit =
    synchronized {if stopped.compareAndSet(false,true) then {queue.clear();require(queue.offer(None))}}
    thread.join(10000)
    require(!thread.isAlive,"render owner did not retire")
    Option(closeFailure.get()).foreach(throw _)
