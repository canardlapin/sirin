package sirin.desktop

import java.nio.file.{Files, Path}
import sirin.*
import sirin.graphics.{Affine, DrawingPreparation, FontData, ImageSnapshot, ParagraphOptions}
import sirin.graphics.text.{NativeFont, NativeParagraph}
import sirin.graphics.unicode.NativeUnicode
import sirin.host.*
import sirin.render.OffscreenGpu
import sirin.text.ResolvedTextLayout

sealed trait FrameOutput extends AutoCloseable:
  def request: FrameRequest
  def layout: LayoutResult
  def close(): Unit = ()
final case class LayoutFeedback(request: FrameRequest, layout: LayoutResult) extends FrameOutput
final case class RenderedFrame(
    request: FrameRequest,
    layout: LayoutResult,
    pixels: ImageSnapshot,
    programBytes: Long,
    readbackBytes: Long,
    maskBytes: Long,
    workerNanos: Long,
    worker: String,
    tiles: Int,
    capture: Path,
    profile: String = "",
    queueNanos: Long = 0,
    sessionSetupNanos: Long = 0,
    gpuFrame: Option[sirin.render.GpuFrame] = None
) extends FrameOutput:
  def width: Int = gpuFrame.fold(pixels.width)(_.width)
  def height: Int = gpuFrame.fold(pixels.height)(_.height)
  def present(window: NativeWindow): Boolean =
    gpuFrame.fold(window.present(pixels.pixelsCopy, pixels.width, pixels.height))(_.present(window))
  override def close(): Unit = gpuFrame.foreach(_.close())

object DevelopmentTheme extends Theme:
  def reducedMotion: Boolean = true
  def color(role: ColorRole): Color =
    role match
      case ColorRole.Surface =>
        Color.rgba(20, 25, 35)
      case ColorRole.OnSurface =>
        Color.rgba(230, 233, 239)
      case ColorRole.Muted =>
        Color.rgba(158, 170, 190)
      case ColorRole.Accent =>
        Color.rgba(115, 185, 248)
      case _ =>
        Color.rgba(215, 223, 238)

trait RenderBackend extends OwnedRenderBackend[FrameRequest, FrameOutput]

/** Long-lived native ownership stays entirely on its dedicated Scala-created thread. */
final class RenderSession(
    fontRoot: Path,
    captureRoot: Path,
    tileEdge: Int = 128,
    batch: Option[sirin.render.BatchGpu] = None,
    editorSystemFonts: Boolean = false
) extends RenderBackend:
  require(tileEdge >= 32 && tileEdge <= 192, "tile edge budget")
  private val elementMeasurements = new ElementMeasureCache
  private var displayScale = Option.empty[(Double, Double)]
  private var displayEpoch = 0L
  private def phaseEpochs(payload: FramePayload): PhaseEpochs =
    val scale = (payload.pixelWidth / payload.logicalWidth, payload.pixelHeight / payload.logicalHeight)
    if !displayScale.contains(scale) then
      displayScale = Some(scale)
      displayEpoch = Math.addExact(displayEpoch, 1L)
    PhaseEpochs(displayScale = displayEpoch)
  private val unicode = NativeUnicode.open()
  private val faces = scala.collection.mutable.Map.empty[TextStyle, Vector[NativeFont]]
  private val extraFaces = scala.collection.mutable.Map.empty[sirin.graphics.ResolvedFont, NativeFont]
  private val layouts = scala.collection.mutable.LinkedHashMap.empty[sirin.drawing.TextRequest, ResolvedTextLayout]
  private val masks = scala
    .collection
    .mutable
    .LinkedHashMap
    .empty[(sirin.graphics.ResolvedFont, Int, Double), sirin.graphics.GlyphRaster]
  private var maskBytes = 0L
  private lazy val bytes = Vector(
    "NotoSans-Regular.ttf",
    "NotoSansHebrew-Regular.ttf",
    "NotoNaskhArabic-Regular.ttf",
    "NotoSansDevanagari-Regular.ttf"
  ).map(name => FontData.copy(Files.readAllBytes(fontRoot.resolve(name))))
  private lazy val gpu =
    try
      OffscreenGpu.open()
    catch
      case e: Throwable =>
        unicode.close()
        throw e
  private lazy val tiles =
    new sirin.graphics.desktop.TileCache(
      gpu,
      raster,
      tileEdge,
      32,
      24L * 1024 * 1024,
      onImageEvicted = image => composite.invalidateSource(image)
    )
  private lazy val composite = new sirin.graphics.desktop.TileCache(gpu, raster, tileEdge, 32, 24L * 1024 * 1024)
  private var previousBase = Option.empty[(FramePayload, LayoutResult, sirin.graphics.RenderTarget)]
  private lazy val gpuTiles = new sirin.graphics.desktop.GpuTileCache(batch.get, tileEdge)
  private val preparation = new sirin.graphics.PreparationCache
  private var gpuBase = Option.empty[(FramePayload, LayoutResult, sirin.graphics.PreparedDrawing)]
  private var gpuDrawing = Option.empty[(FramePayload, LayoutResult, sirin.graphics.PreparedDrawing)]
  var shaped = 0L
  private def configured(style: TextStyle): Vector[NativeFont] = faces.getOrElseUpdate(
    style, {
      val size =
        style match
          case TextStyle.Title =>
            28.0
          case TextStyle.Heading =>
            19.0
          case TextStyle.Caption =>
            12.0
          case _ =>
            14.0
      val opened = scala.collection.mutable.ArrayBuffer.empty[NativeFont]
      try {
        bytes.foreach(data => opened += NativeFont.open(data, size))
        opened.toVector
      } catch
        case e: Throwable =>
          opened.foreach(_.close())
          throw e
    }
  )
  private val cjk = scala.collection.mutable.Map.empty[TextStyle, NativeFont]
  private lazy val cjkData = FontData.copy(
    Files.readAllBytes(Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"))
  )
  private def textFaces(style: TextStyle, text: String): Vector[NativeFont] =
    val base = configured(style)
    val needsCjk =
      editorSystemFonts && text.exists(c => (c >= '\u3040' && c <= '\u30ff') || (c >= '\u3400' && c <= '\u9fff'))
    if !needsCjk then
      base
    else
      val face = cjk.getOrElseUpdate(
        style, {
          val f = NativeFont.open(cjkData, base.head.font.size)
          extraFaces(f.font) = f
          f
        }
      )
      base :+ face
  private def raster(
      font: sirin.graphics.ResolvedFont,
      id: Int,
      scale: Double,
      budget: Int
  ): sirin.graphics.GlyphRaster =
    val key = (font, id, scale)
    masks.remove(key) match
      case Some(mask) =>
        require(mask.byteSize <= budget, "glyph mask budget")
        masks(key) = mask
        mask
      case None =>
        val face = faces
          .valuesIterator
          .flatten
          .find(_.font eq font)
          .getOrElse(extraFaces.getOrElseUpdate(font, NativeFont.open(font.data, font.size, font.faceIndex)))
        require(
          face.font.metrics == font.metrics && face.font.shaperVersion == font.shaperVersion &&
            face.font.rasterizerVersion == font.rasterizerVersion,
          "resolved font mismatch"
        )
        val mask = face.rasterize(id, scale, maxPixels = budget)
        while masks.nonEmpty && (masks.size >= 4096 || maskBytes + mask.byteSize > 16L * 1024 * 1024) do
          val old = masks.remove(masks.head._1).get
          maskBytes -= old.byteSize
        require(mask.byteSize <= 16L * 1024 * 1024, "session mask budget")
        masks(key) = mask
        maskBytes += mask.byteSize
        mask
  def render(request: FrameRequest, cancelled: () => Boolean): FrameOutput =
    if batch.nonEmpty then
      return renderGpu(request, cancelled)
    val began = System.nanoTime()
    def check(): Unit =
      if cancelled() then
        throw new InterruptedException("obsolete frame")
    check()
    val payload = request.payload
    val key =
      // A focused text control keeps its key through `textFocus`; other focus lives only in the overlay.
      payload.copy(focus = None, keyboardFocus = false, pressed = None)
    var layoutNanos = 0L
    var drawingNanos = 0L
    val (layout, target, charges, baseProfile) =
      previousBase match
        case Some((prior, layout, target)) if prior == key =>
          (layout, target, (0L, 0L, 0L), "reused=true")
        case _ =>
          previousBase = None
          val layout =
            sirin
              .host
              .Layout(
                payload.tree,
                payload.logicalWidth,
                payload.logicalHeight,
                payload.scrolls,
                elementMeasurements,
                phaseEpochs(payload)
              ) { text =>
                check()
                layouts.remove(text) match
                  case Some(value) =>
                    layouts(text) = value
                    value
                  case None =>
                    val value =
                      if text.maxWidth.toDouble == Double.PositiveInfinity then
                        ResolvedTextLayout(
                          NativeParagraph.layout(
                            text.content,
                            textFaces(text.style, text.content),
                            unicode,
                            ParagraphOptions()
                          )
                        )
                      else
                        ResolvedTextLayout(
                          NativeParagraph.wrap(
                            text.content,
                            textFaces(text.style, text.content),
                            unicode,
                            text.maxWidth.toDouble,
                            ParagraphOptions()
                          )
                        )
                    if layouts.size >= 256 then
                      layouts.remove(layouts.head._1)
                    layouts(text) = value
                    shaped += 1
                    value
              }
          check()
          if layout.viewports.exists(v => !payload.viewports.get(v.instance).contains(v.viewport)) then
            return LayoutFeedback(request, layout)
          val layoutAt = System.nanoTime()
          layoutNanos = layoutAt - began
          val drawing = layout.drawing(DevelopmentTheme, textFocus = payload.textFocus)
          val target = gpu
            .target(payload.pixelWidth, payload.pixelHeight, 1)
            .copy(logicalToDevice =
              Affine.scale(payload.pixelWidth / payload.logicalWidth, payload.pixelHeight / payload.logicalHeight)
            )
          val prepared = DrawingPreparation
            .prepare(drawing, target)
            .fold(error => throw new IllegalStateException(error.toString), identity)
          drawingNanos = System.nanoTime() - layoutAt
          val base = tiles.render(prepared, cancelled)
          previousBase = Some((key, layout, target))
          (layout, target, (base.programBytes, base.readbackBytes, base.maskBytes), tiles.lastProfile)
    val scale = target.logicalToDevice
    val inverse = scale.inverse.get
    val overlay = layout.overlay(DevelopmentTheme, payload.focus, payload.keyboardFocus, payload.pressed)
    val composed = sirin
      .graphics
      .Drawing
      .record { canvas =>
        tiles
          .snapshots
          .foreach { (rect, image) =>
            val origin = inverse(sirin.graphics.Point(rect.x, rect.y))
            val end = inverse(sirin.graphics.Point(rect.x + rect.width, rect.y + rect.height))
            canvas.image(
              image.full,
              sirin.graphics.Rect(origin.x, origin.y, end.x - origin.x, end.y - origin.y),
              sirin.graphics.Sampling.Nearest
            )
          }
        canvas.draw(overlay)
      }
    val preparedComposite = DrawingPreparation
      .prepare(composed, target)
      .fold(e => throw new IllegalStateException(e.toString), identity)
    val upper = composite.render(preparedComposite, cancelled)
    val result = upper.copy(
      programBytes = charges._1 + upper.programBytes,
      readbackBytes = charges._2 + upper.readbackBytes,
      maskBytes = charges._3 + upper.maskBytes
    )
    check()
    val capture = captureRoot.resolve(s"frame-${request.generation}.png")
    if java.lang.Boolean.getBoolean("sirin.capture") then
      FrameWorker.save(result.image, capture)
    RenderedFrame(
      request,
      layout,
      result.image,
      result.programBytes,
      result.readbackBytes,
      result.maskBytes,
      System.nanoTime() - began,
      Thread.currentThread().getName,
      result.tiles,
      capture,
      s"layoutMs=${layoutNanos / 1e6} drawingMs=${drawingNanos / 1e6} base[$baseProfile] overlay[${composite
          .lastProfile}]"
    )
  private def renderGpu(request: FrameRequest, cancelled: () => Boolean): FrameOutput =
    val began = System.nanoTime()
    def check(): Unit =
      if cancelled() then
        throw new InterruptedException("obsolete GPU frame")
    check()
    val control = new sirin.graphics.PreparationControl(cancelled = cancelled)
    val payload = request.payload
    val baseKey =
      // A focused text control keeps its key through `textFocus`; other focus lives only in the overlay.
      payload.copy(focus = None, keyboardFocus = false, pressed = None)
    val before = batch.get.stats
    var prepared = 0
    val (layout, drawing) =
      gpuDrawing match
        case Some((prior, layout, drawing)) if prior == payload =>
          (layout, drawing)
        case _ =>
          gpuDrawing = None
          val (layout, base) =
            gpuBase match
              case Some((prior, layout, base)) if prior == baseKey =>
                (layout, base)
              case _ =>
                gpuBase = None
                val layout =
                  sirin
                    .host
                    .Layout(
                      payload.tree,
                      payload.logicalWidth,
                      payload.logicalHeight,
                      payload.scrolls,
                      elementMeasurements,
                      phaseEpochs(payload)
                    ) { text =>
                      check()
                      layouts.remove(text) match
                        case Some(value) =>
                          layouts(text) = value
                          value
                        case None =>
                          val value =
                            if text.maxWidth.toDouble == Double.PositiveInfinity then
                              ResolvedTextLayout(
                                NativeParagraph.layout(
                                  text.content,
                                  textFaces(text.style, text.content),
                                  unicode,
                                  ParagraphOptions()
                                )
                              )
                            else
                              ResolvedTextLayout(
                                NativeParagraph.wrap(
                                  text.content,
                                  textFaces(text.style, text.content),
                                  unicode,
                                  text.maxWidth.toDouble,
                                  ParagraphOptions()
                                )
                              )
                          if layouts.size >= 256 then
                            layouts.remove(layouts.head._1)
                          layouts(text) = value
                          shaped += 1
                          value
                    }
                check()
                if layout.viewports.exists(v => !payload.viewports.get(v.instance).contains(v.viewport)) then
                  return LayoutFeedback(request, layout)
                val target = batch
                  .get
                  .target(payload.pixelWidth, payload.pixelHeight, 1)
                  .copy(logicalToDevice =
                    Affine.scale(payload.pixelWidth / payload.logicalWidth, payload.pixelHeight / payload.logicalHeight)
                  )
                val base = preparation
                  .prepare(layout.drawing(DevelopmentTheme, textFocus = payload.textFocus), target, control = control)
                  .fold(e => throw new IllegalStateException(e.toString), identity)
                prepared += 1
                gpuBase = Some((baseKey, layout, base))
                (layout, base)
          val overlay = preparation
            .prepare(
              layout.overlay(DevelopmentTheme, payload.focus, payload.keyboardFocus, payload.pressed),
              base.target,
              control = control
            )
            .fold(e => throw new IllegalStateException(e.toString), identity)
          prepared += 1
          val composed = sirin.graphics.desktop.GpuTileCache.compose(base, overlay)
          (layout, composed)
    val unchanged = prepared == 0
    val frame =
      if unchanged then
        gpuTiles.frame(drawing.target.width, drawing.target.height)
      else
        gpuTiles.render(drawing, cancelled, control)
    try
      check()
      gpuDrawing = Some((payload, layout, drawing))
      val after = batch.get.stats
      val uploaded =
        after.meshUploadBytes - before.meshUploadBytes + after.textureUploadBytes - before.textureUploadBytes +
          after.programUploadBytes - before.programUploadBytes
      val capture = captureRoot.resolve(s"frame-${request.generation}.png")
      val capturing = java.lang.Boolean.getBoolean("sirin.capture")
      if capturing then
        FrameWorker.save(frame.readback(), capture)
      RenderedFrame(
        request,
        layout,
        null,
        uploaded,
        if capturing then
          frame.width.toLong * frame.height * 4
        else
          0
        ,
        0,
        System.nanoTime() - began,
        Thread.currentThread().getName,
        gpuTiles.tileCount,
        capture,
        s"prepared=$prepared tilePrepared=${
            if unchanged then
              0
            else
              gpuTiles.lastPrepared
          } compiled=${
            if unchanged then
              0
            else
              gpuTiles.lastCompiled
          } submissions=${after.submissions - before.submissions} cache=${gpuTiles.stats}",
        gpuFrame = Some(frame)
      )
    catch
      case e: Throwable =>
        frame.close()
        throw e
  def stats: String =
    batch match
      case Some(value) if value.isLost =>
        s"shaped=$shaped deviceLost=true"
      case Some(value) =>
        s"shaped=$shaped GPU=${value.stats} CPU=${value.cacheStats} tiles=${gpuTiles.stats}"
      case None =>
        s"shaped=$shaped tileHits=${tiles.hits} tileMisses=${tiles.misses} baseCache=${tiles
            .stats} compositeCache=${composite.stats} resources=${gpu.resourceStats}"
  def close(): Unit =
    try
      batch match
        case Some(value) =>
          try gpuTiles.close()
          finally value.close()
        case None =>
          try {
            tiles.close()
            composite.close()
          } finally
            gpu.close()
    finally
      faces.valuesIterator.flatten.foreach(_.close())
      extraFaces.values.foreach(_.close())
      unicode.close()
      preparation.close()
      layouts.clear()
      masks.clear()
      faces.clear()
      extraFaces.clear()
      cjk.clear()
      previousBase = None
      gpuBase = None
      gpuDrawing = None

object FrameWorker:
  def render(request: FrameRequest, fontRoot: Path, captureRoot: Path)(using cancellation: Cancellation): FrameOutput =
    val session = new RenderSession(fontRoot, captureRoot)
    try session.render(request, () => cancellation.cancelled)
    finally session.close()
  private[desktop] def save(pixels: ImageSnapshot, file: Path): Unit =
    val bytes = pixels.pixelsCopy
    val image =
      new java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val packed =
      Array.tabulate(pixels.width * pixels.height) { p =>
        val i = p * 4
        ((bytes(i + 3) & 255) << 24) | ((bytes(i) & 255) << 16) | ((bytes(i + 1) & 255) << 8) | (bytes(i + 2) & 255)
      }
    image.setRGB(0, 0, pixels.width, pixels.height, packed, 0, pixels.width)
    Files.createDirectories(file.getParent)
    require(javax.imageio.ImageIO.write(image, "png", file.toFile), "PNG writer unavailable")

/** One replaceable pending frame and one replaceable immutable result; no idle polling. This is a backend render
  * service, not an application Work policy.
  */
private[desktop] trait OwnedRenderBackend[A, B] extends AutoCloseable:
  def render(request: A, cancelled: () => Boolean): B
  def stats: String

private[desktop] final class OwnedRenderService[A, B <: AutoCloseable](
    wake: () => Unit,
    sequence: A => Long,
    factory: () => OwnedRenderBackend[A, B],
    stamp: (B, Long, Long) => B,
    finish: () => Unit = () => ()
) extends AutoCloseable:
  private enum Input:
    case Stop
    case Work(request: A, submittedAt: Long)
    case Retire(
        done: java.util.concurrent.CountDownLatch,
        error: java.util.concurrent.atomic.AtomicReference[Throwable]
    )
  private val stopInput = Input.Stop // initialize enum companions before starting the worker
  private val caller = Thread.currentThread()
  private val queue = new java.util.concurrent.ArrayBlockingQueue[Input](1)
  private val result = new java.util.concurrent.atomic.AtomicReference[Option[(Long, Either[Throwable, B])]](None)
  private val latest = new java.util.concurrent.atomic.AtomicLong(0)
  private val stopped = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val fatal = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
  private val closeFailure = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
  private val retired = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val thread = new Thread(() => loop(), "sirin-render-owner")
  thread.setDaemon(true)
  thread.start()
  private def loop(): Unit =
    var session = Option.empty[OwnedRenderBackend[A, B]]
    try
      val setupStarted = System.nanoTime()
      session = Some(factory())
      var setupNanos = System.nanoTime() - setupStarted
      var running = true
      while running do
        queue.take() match
          case Input.Stop =>
            running = false
          case Input.Retire(done, error) =>
            try
              session.foreach(_.close())
            catch
              case e: Throwable =>
                error.set(e)
                throw e
            finally {
              session = None
              done.countDown()
            }
          case Input.Work(request, submittedAt) =>
            if session.isEmpty then
              val began = System.nanoTime()
              session = Some(factory())
              setupNanos = System.nanoTime() - began
            val startedAt = System.nanoTime()
            val value =
              try
                val rendered = session.get.render(request, () => stopped.get() || latest.get() != sequence(request))
                Right(stamp(rendered, startedAt - submittedAt, setupNanos))
              catch
                case e: Throwable =>
                  Left(e)
            val notify = synchronized {
              if !stopped.get() && latest.get() == sequence(request) then
                result.getAndSet(Some((sequence(request), value))).foreach(_._2.foreach(_.close()))
                true
              else {
                value.foreach(_.close())
                false
              }
            }
            if notify then
              wake()
    catch
      case e: Throwable =>
        synchronized {
          fatal.set(e)
          result.getAndSet(Some((latest.get(), Left(e)))).foreach(_._2.foreach(_.close()))
        }
        if !stopped.get() then
          wake()
        else
          closeFailure.set(e)
    finally
      session.foreach { s =>
        try
          println(s"renderSession ${s.stats}")
        catch
          case e: Throwable =>
            closeFailure.set(e)
        finally
          try
            s.close()
          catch
            case e: Throwable =>
              closeFailure.set(e)
      }
      try
        finish()
      catch
        case e: Throwable =>
          closeFailure.set(e)
      retired.set(true)
      try
        wake()
      catch
        case e: Throwable =>
          closeFailure.set(e)
  def quiesce(): Unit =
    require(Thread.currentThread() eq caller, "render retirement caller mismatch")
    val done = new java.util.concurrent.CountDownLatch(1)
    val error = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
    synchronized {
      require(!stopped.get() && thread.isAlive, "render service retired")
      Option(fatal.get()).foreach(throw _)
      latest.set(-1)
      queue.clear()
      result.getAndSet(None).foreach(_._2.foreach(_.close()))
      require(queue.offer(Input.Retire(done, error)))
    }
    require(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "render retirement did not complete")
    Option(error.get()).foreach(throw _)
    Option(fatal.get()).foreach(throw _)
  def submit(request: A): Unit = synchronized {
    require(!stopped.get(), "renderer retired")
    Option(fatal.get()).foreach(throw _)
    require(thread.isAlive, "render owner terminated")
    latest.set(sequence(request))
    queue.clear()
    result.getAndSet(None).foreach(_._2.foreach(_.close()))
    require(queue.offer(Input.Work(request, System.nanoTime())))
  }
  def poll(): Option[(Long, Either[Throwable, B])] = result.getAndSet(None)
  private[desktop] def awaitStopped(millis: Long): Boolean = {
    thread.join(millis)
    !thread.isAlive
  }
  def requestClose(): Unit = synchronized {
    if stopped.compareAndSet(false, true) then {
      queue.clear()
      result.getAndSet(None).foreach(_._2.foreach(_.close()))
      require(queue.offer(stopInput))
    }
  }
  def isRetired: Boolean = retired.get()
  def close(): Unit =
    requestClose()
    thread.join(10000)
    require(!thread.isAlive, "render owner did not retire")
    Option(closeFailure.get()).foreach(throw _)

final class RenderService(
    fontRoot: Path,
    captureRoot: Path,
    wake: () => Unit,
    factory: Option[() => RenderBackend] = None
) extends AutoCloseable:
  private val service =
    new OwnedRenderService[FrameRequest, FrameOutput](
      wake,
      _.generation,
      () => factory.fold[RenderBackend](new RenderSession(fontRoot, captureRoot))(_()),
      (value, queued, setup) =>
        value match
          case frame: RenderedFrame =>
            frame.copy(queueNanos = queued, sessionSetupNanos = setup)
          case other =>
            other
    )
  def submit(request: FrameRequest): Unit = service.submit(request)
  def poll(): Option[(Long, Either[Throwable, FrameOutput])] = service.poll()
  def quiesce(): Unit = service.quiesce()
  private[desktop] def awaitStopped(millis: Long): Boolean = service.awaitStopped(millis)
  def requestClose(): Unit = service.requestClose()
  def isRetired: Boolean = service.isRetired
  def close(): Unit = service.close()
