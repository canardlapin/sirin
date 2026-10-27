package sirin.probe

import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import WgpuTypes.*

/** Minimal wgpu device + surface for one Metal layer. Probe code, not engine API. */
final class Gpu private (
    val instance: Ptr[Byte],
    val surface: Ptr[Byte],
    val adapter: Ptr[Byte],
    val device: Ptr[Byte],
    val queue: Ptr[Byte],
    val format: Int
):
  private var width  = 0
  private var height = 0
  var presents: Long = 0L
  /** Acquire-failure counts by WGPUSurfaceGetCurrentTextureStatus value. */
  val acquireFailures = scala.collection.mutable.TreeMap.empty[Int, Int]

  def configure(w: Int, h: Int): Unit =
    if w > 0 && h > 0 && (w != width || h != height) then
      val cfg = stackalloc[SurfaceConfiguration]()
      cfg._1 = null
      cfg._2 = device
      cfg._3 = format
      cfg._4 = TextureUsageRenderAttachment.toULong
      cfg._5 = w.toUInt
      cfg._6 = h.toUInt
      cfg._7 = 0.toCSize
      cfg._8 = null
      cfg._9 = CompositeAlphaModeAuto
      cfg._10 = PresentModeFifo
      Wgpu.wgpuSurfaceConfigure(surface, cfg)
      width = w
      height = h

  /** Clears the surface and presents. Returns false if the frame was skipped. */
  def clear(r: Double, g: Double, b: Double): Boolean =
    val st = stackalloc[SurfaceTexture]()
    st._1 = null
    st._2 = null
    st._3 = 0
    Wgpu.wgpuSurfaceGetCurrentTexture(surface, st)
    val status = st._3
    if status != SurfaceTextureSuccessOptimal && status != SurfaceTextureSuccessSuboptimal then
      acquireFailures(status) = acquireFailures.getOrElse(status, 0) + 1
      if st._2 != null then Wgpu.wgpuTextureRelease(st._2)
      status match
        case SurfaceTextureOccluded | SurfaceTextureTimeout =>
          () // not visible / not ready: skip and wait for the next expose event
        case SurfaceTextureOutdated | SurfaceTextureLost =>
          val (w, h) = (width, height)
          width = 0
          configure(w, h)
        case other => sys.error(s"surface texture acquisition failed with status 0x${other.toHexString}")
      false
    else
      val texture = st._2
      val view    = Wgpu.wgpuTextureCreateView(texture, null)
      val encoder = Wgpu.wgpuDeviceCreateCommandEncoder(device, null)

      val att = stackalloc[RenderPassColorAttachment]()
      att._1 = null
      att._2 = view
      att._3 = DepthSliceUndefined.toUInt
      att._4 = null
      att._5 = LoadOpClear
      att._6 = StoreOpStore
      att._7._1 = r
      att._7._2 = g
      att._7._3 = b
      att._7._4 = 1.0

      val pass = stackalloc[RenderPassDescriptor]()
      pass._1 = null
      pass._2._1 = null
      pass._2._2 = 0.toCSize
      pass._3 = 1.toCSize
      pass._4 = att
      pass._5 = null
      pass._6 = null
      pass._7 = null

      val rp = Wgpu.wgpuCommandEncoderBeginRenderPass(encoder, pass)
      Wgpu.wgpuRenderPassEncoderEnd(rp)
      Wgpu.wgpuRenderPassEncoderRelease(rp)
      val cmd  = Wgpu.wgpuCommandEncoderFinish(encoder, null)
      val cmds = stackalloc[Ptr[Byte]]()
      !cmds = cmd
      Wgpu.wgpuQueueSubmit(queue, 1.toCSize, cmds)
      Wgpu.wgpuSurfacePresent(surface)
      presents += 1

      Wgpu.wgpuCommandBufferRelease(cmd)
      Wgpu.wgpuCommandEncoderRelease(encoder)
      Wgpu.wgpuTextureViewRelease(view)
      Wgpu.wgpuTextureRelease(texture)
      true

  def close(): Unit =
    Wgpu.wgpuSurfaceUnconfigure(surface)
    Wgpu.wgpuQueueRelease(queue)
    Wgpu.wgpuDeviceRelease(device)
    Wgpu.wgpuAdapterRelease(adapter)
    Wgpu.wgpuSurfaceRelease(surface)
    Wgpu.wgpuInstanceRelease(instance)

object Gpu:
  private val MaxPolls = 10000

  /** Drives `wgpuInstanceProcessEvents` until the shim records a result. */
  private def await(instance: Ptr[Byte], result: Ptr[RequestResult], what: String): Ptr[Byte] =
    var polls = 0
    while result._1 == 0.toUInt && polls < MaxPolls do
      Wgpu.wgpuInstanceProcessEvents(instance)
      polls += 1
    if result._1 == 0.toUInt then sys.error(s"$what request did not complete after $MaxPolls polls")
    if result._2.toInt != RequestStatusSuccess then
      sys.error(s"$what request failed (status ${result._2}): ${fromCString(result._4.at(0))}")
    result._3

  def forMetalLayer(layer: Ptr[Byte]): Gpu =
    val instance = Wgpu.wgpuCreateInstance(null)
    if instance == null then sys.error("wgpuCreateInstance returned null")

    val source = stackalloc[SurfaceSourceMetalLayer]()
    source._1._1 = null
    source._1._2 = STypeSurfaceSourceMetalLayer
    source._2 = layer
    val sdesc = stackalloc[SurfaceDescriptor]()
    sdesc._1 = source.asInstanceOf[Ptr[Byte]]
    sdesc._2._1 = null
    sdesc._2._2 = 0.toCSize
    val surface = Wgpu.wgpuInstanceCreateSurface(instance, sdesc)
    if surface == null then sys.error("wgpuInstanceCreateSurface returned null")

    // Request results live in native memory: the shim's callbacks write them.
    val result = stdlib.calloc(1, sizeOf[RequestResult]).asInstanceOf[Ptr[RequestResult]]
    val future = stackalloc[CUnsignedLongLong]()
    try
      val opts = stackalloc[RequestAdapterOptions]()
      opts._1 = null
      opts._2 = FeatureLevelCore
      opts._3 = 0
      opts._4 = 0.toUInt
      opts._5 = 0
      opts._6 = surface
      Shim.sirin_wgpu_request_adapter(instance, opts, result, future)
      val adapter = await(instance, result, "adapter")

      result._1 = 0.toUInt
      Shim.sirin_wgpu_request_device(adapter, result, future)
      val device = await(instance, result, "device")

      val caps = stackalloc[SurfaceCapabilities]()
      Wgpu.wgpuSurfaceGetCapabilities(surface, adapter, caps)
      val formatPtr = caps._4
      val formats   = (0 until caps._3.toInt).map(i => formatPtr(i))
      Shim.sirin_wgpu_surface_capabilities_free(caps)
      val format =
        if formats.contains(TextureFormatBGRA8UnormSrgb) then TextureFormatBGRA8UnormSrgb
        else formats.headOption.getOrElse(sys.error("surface reports no formats"))

      new Gpu(instance, surface, adapter, device, Wgpu.wgpuDeviceGetQueue(device), format)
    finally stdlib.free(result.asInstanceOf[Ptr[Byte]])
