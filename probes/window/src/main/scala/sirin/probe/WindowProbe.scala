package sirin.probe

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Stage 0 probe P0.2: SDL3 window + wgpu clear, idle parking, resize, shutdown.
  *
  * Modes:
  *   - `interactive`         redraw on expose/resize until Escape or close
  *   - `idle <seconds>`      park the loop and count wakeups and presents
  *   - `resize <cycles>`     programmatic resize + one frame per cycle, RSS every 1000
  */
object WindowProbe:
  // Calm neutral background, in linear space for an sRGB surface.
  private val Background = (0.87, 0.87, 0.85)

  final class Window(val handle: Ptr[Byte], val view: Ptr[Byte], val gpu: Gpu):
    def pixelSize(): (Int, Int) =
      val w = stackalloc[CInt]()
      val h = stackalloc[CInt]()
      Sdl.SDL_GetWindowSizeInPixels(handle, w, h)
      (!w, !h)

    def draw(): Boolean =
      val (w, h) = pixelSize()
      gpu.configure(w, h)
      gpu.clear(Background._1, Background._2, Background._3)

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("interactive")
    val n    = args.lift(1).map(_.toInt)

    if !Sdl.SDL_Init(SdlConst.InitVideo) then fail("SDL_Init")
    val k = SdlConst.load()
    val flags =
      SdlConst.WindowResizable | SdlConst.WindowHighPixelDensity | SdlConst.WindowMetal
    val handle = Zone.acquire { implicit z =>
      Sdl.SDL_CreateWindow(toCString("Sirin probe"), 800, 520, flags.toULong)
    }
    if handle == null then fail("SDL_CreateWindow")
    val view = Sdl.SDL_Metal_CreateView(handle)
    if view == null then fail("SDL_Metal_CreateView")
    val win = Window(handle, view, Gpu.forMetalLayer(Sdl.SDL_Metal_GetLayer(view)))

    println(s"display scale: ${Sdl.SDL_GetWindowDisplayScale(handle)}")
    println(s"pixel size: ${win.pixelSize()}")
    println(s"surface format: 0x${win.gpu.format.toHexString}")

    val event = stackalloc[Byte](k.eventSize.toInt)
    def eventType: Long = (!event.asInstanceOf[Ptr[CUnsignedInt]]).toLong

    awaitExposed(k, event, () => eventType)

    mode match
      case "interactive" => interactive(win, k, event, () => eventType)
      case "idle"        => idle(win, k, event, () => eventType, n.getOrElse(60))
      case "resize"      => resize(win, event, n.getOrElse(1000))
      case other         => sys.error(s"unknown mode: $other")

    val presents = win.gpu.presents
    win.gpu.close()
    Sdl.SDL_Metal_DestroyView(view)
    Sdl.SDL_DestroyWindow(handle)
    Sdl.SDL_Quit()
    println(s"acquire failures by status: ${win.gpu.acquireFailures.mkString(", ")}")
    println(s"clean shutdown; presents=$presents")

  /** Waits (bounded) until the window server has shown the window. */
  private def awaitExposed(k: SdlConst.Values, event: Ptr[Byte], tpe: () => Long): Unit =
    val deadline = System.nanoTime() + 3000000000L
    var exposed  = false
    while !exposed && System.nanoTime() < deadline do
      if Sdl.SDL_WaitEventTimeout(event, 100) && tpe() == k.windowExposed then exposed = true
    println(s"window exposed: $exposed")

  private def isRedraw(t: Long, k: SdlConst.Values) =
    t == k.windowExposed || t == k.windowPixelSizeChanged || t == k.windowDisplayScaleChanged

  private def isQuit(t: Long, event: Ptr[Byte], k: SdlConst.Values) =
    t == k.quit || t == k.windowCloseRequested ||
      (t == k.keyDown && (!(event + k.keyOffset).asInstanceOf[Ptr[CUnsignedInt]]).toLong == k.keyEscape)

  private def interactive(win: Window, k: SdlConst.Values, event: Ptr[Byte], tpe: () => Long): Unit =
    win.draw()
    var running = true
    while running do
      // Parks the loop until the OS delivers something: no polling, no timer.
      if Sdl.SDL_WaitEventTimeout(event, -1) then
        val t = tpe()
        if isQuit(t, event, k) then running = false
        else if isRedraw(t, k) then win.draw()

  private def idle(win: Window, k: SdlConst.Values, event: Ptr[Byte], tpe: () => Long, seconds: Int): Unit =
    // Let startup events (shown, exposed, focus) settle before measuring.
    win.draw()
    while Sdl.SDL_WaitEventTimeout(event, 500) do
      if isRedraw(tpe(), k) then win.draw()
    val presentsBefore = win.gpu.presents
    val deadline       = System.nanoTime() + seconds.toLong * 1000000000L
    var wakeups        = 0
    var events         = 0
    var remainingMs    = seconds * 1000
    while remainingMs > 0 do
      wakeups += 1
      if Sdl.SDL_WaitEventTimeout(event, remainingMs) then
        events += 1
        if isRedraw(tpe(), k) then win.draw()
      remainingMs = ((deadline - System.nanoTime()) / 1000000L).toInt
    val presents = win.gpu.presents - presentsBefore
    println(s"idle: seconds=$seconds loopWakeups=$wakeups events=$events presents=$presents")

  private def resize(win: Window, event: Ptr[Byte], cycles: Int): Unit =
    val sizes   = Array((800, 520), (640, 400), (1024, 700), (500, 320))
    val startMb = rssMb()
    println(f"rss start: $startMb%.1f MB")
    var skipped = 0
    var i       = 0
    while i < cycles do
      val (w, h) = sizes(i % sizes.length)
      Sdl.SDL_SetWindowSize(win.handle, w, h)
      while Sdl.SDL_PollEvent(event) do ()
      if !win.draw() then skipped += 1
      i += 1
      if i % 1000 == 0 then println(f"rss after $i%d cycles: ${rssMb()}%.1f MB")
    println(f"resize: cycles=$cycles skippedFrames=$skipped rssDelta=${rssMb() - startMb}%.1f MB")

  private def rssMb(): Double = Shim.sirin_resident_bytes().toDouble / (1024.0 * 1024.0)

  private def fail(what: String): Nothing =
    sys.error(s"$what failed: ${fromCString(Sdl.SDL_GetError())}")
