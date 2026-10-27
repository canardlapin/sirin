package sirin.probe

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Struct layouts mirrored from webgpu.h (wgpu-native v29.0.1.1). C enums are
  * `int32`, `WGPUFlags` is `uint64`, `WGPUBool` is `uint32`. Every layout here
  * is checked against the C compiler's `sizeof`/`offsetof` by `AbiSuite`.
  */
object WgpuTypes:
  type ChainedStruct          = CStruct2[Ptr[Byte], CInt]
  type StringView             = CStruct2[CString, CSize]
  type SurfaceSourceMetalLayer = CStruct2[ChainedStruct, Ptr[Byte]]
  type SurfaceDescriptor      = CStruct2[Ptr[Byte], StringView]
  type SurfaceConfiguration = CStruct10[
    Ptr[Byte],          // nextInChain
    Ptr[Byte],          // device
    CInt,               // format
    CUnsignedLongLong,  // usage
    CUnsignedInt,       // width
    CUnsignedInt,       // height
    CSize,              // viewFormatCount
    Ptr[CInt],          // viewFormats
    CInt,               // alphaMode
    CInt                // presentMode
  ]
  type SurfaceTexture = CStruct3[Ptr[Byte], Ptr[Byte], CInt]
  type Color          = CStruct4[CDouble, CDouble, CDouble, CDouble]
  type RenderPassColorAttachment = CStruct7[
    Ptr[Byte],     // nextInChain
    Ptr[Byte],     // view
    CUnsignedInt,  // depthSlice
    Ptr[Byte],     // resolveTarget
    CInt,          // loadOp
    CInt,          // storeOp
    Color          // clearValue
  ]
  type RenderPassDescriptor = CStruct7[
    Ptr[Byte],                       // nextInChain
    StringView,                      // label
    CSize,                           // colorAttachmentCount
    Ptr[RenderPassColorAttachment],  // colorAttachments
    Ptr[Byte],                       // depthStencilAttachment
    Ptr[Byte],                       // occlusionQuerySet
    Ptr[Byte]                        // timestampWrites
  ]
  type SurfaceCapabilities = CStruct8[
    Ptr[Byte], CUnsignedLongLong, CSize, Ptr[CInt], CSize, Ptr[CInt], CSize, Ptr[CInt]
  ]
  type RequestAdapterOptions = CStruct6[Ptr[Byte], CInt, CInt, CUnsignedInt, CInt, Ptr[Byte]]

  /** Mirrors `sirin_request_result` in the shim. */
  type RequestResult = CStruct4[CUnsignedInt, CUnsignedInt, Ptr[Byte], CArray[Byte, Nat.Digit3[Nat._5, Nat._1, Nat._2]]]

  final val STypeSurfaceSourceMetalLayer = 0x4
  final val FeatureLevelCore             = 0x2
  final val RequestStatusSuccess         = 0x1
  final val TextureFormatBGRA8Unorm      = 0x1b
  final val TextureFormatBGRA8UnormSrgb  = 0x1c
  final val TextureUsageRenderAttachment = 0x10L
  final val CompositeAlphaModeAuto       = 0x0
  final val PresentModeFifo              = 0x1
  final val LoadOpClear                  = 0x2
  final val StoreOpStore                 = 0x1
  final val DepthSliceUndefined          = -1 // UINT32_MAX
  final val SurfaceTextureSuccessOptimal    = 0x1
  final val SurfaceTextureSuccessSuboptimal = 0x2
  final val SurfaceTextureTimeout           = 0x3
  final val SurfaceTextureOutdated          = 0x4
  final val SurfaceTextureLost              = 0x5
  /** wgpu-native extension (wgpu.h), outside standard webgpu.h: window not visible. */
  final val SurfaceTextureOccluded          = 0x30001

import WgpuTypes.*

@extern
object Wgpu:
  def wgpuCreateInstance(descriptor: Ptr[Byte]): Ptr[Byte] = extern
  def wgpuInstanceCreateSurface(instance: Ptr[Byte], descriptor: Ptr[SurfaceDescriptor]): Ptr[Byte] = extern
  // Callbacks registered with AllowProcessEvents fire inside this call. They are
  // shim-side and only write native memory, so no Scala re-entry occurs.
  def wgpuInstanceProcessEvents(instance: Ptr[Byte]): Unit = extern
  def wgpuSurfaceGetCapabilities(surface: Ptr[Byte], adapter: Ptr[Byte], caps: Ptr[SurfaceCapabilities]): CInt = extern
  def wgpuSurfaceConfigure(surface: Ptr[Byte], config: Ptr[SurfaceConfiguration]): Unit = extern
  // Under FIFO the vsync wait may occur at acquire (VISION §5.4).
  @blocking def wgpuSurfaceGetCurrentTexture(surface: Ptr[Byte], out: Ptr[SurfaceTexture]): Unit = extern
  @blocking def wgpuSurfacePresent(surface: Ptr[Byte]): CInt = extern
  def wgpuTextureCreateView(texture: Ptr[Byte], descriptor: Ptr[Byte]): Ptr[Byte] = extern
  def wgpuDeviceGetQueue(device: Ptr[Byte]): Ptr[Byte] = extern
  def wgpuDeviceCreateCommandEncoder(device: Ptr[Byte], descriptor: Ptr[Byte]): Ptr[Byte] = extern
  def wgpuCommandEncoderBeginRenderPass(encoder: Ptr[Byte], descriptor: Ptr[RenderPassDescriptor]): Ptr[Byte] = extern
  def wgpuRenderPassEncoderEnd(pass: Ptr[Byte]): Unit = extern
  def wgpuCommandEncoderFinish(encoder: Ptr[Byte], descriptor: Ptr[Byte]): Ptr[Byte] = extern
  def wgpuQueueSubmit(queue: Ptr[Byte], count: CSize, commands: Ptr[Ptr[Byte]]): Unit = extern

  def wgpuTextureRelease(p: Ptr[Byte]): Unit           = extern
  def wgpuTextureViewRelease(p: Ptr[Byte]): Unit       = extern
  def wgpuCommandEncoderRelease(p: Ptr[Byte]): Unit    = extern
  def wgpuRenderPassEncoderRelease(p: Ptr[Byte]): Unit = extern
  def wgpuCommandBufferRelease(p: Ptr[Byte]): Unit     = extern
  def wgpuQueueRelease(p: Ptr[Byte]): Unit             = extern
  def wgpuSurfaceUnconfigure(p: Ptr[Byte]): Unit       = extern
  def wgpuSurfaceRelease(p: Ptr[Byte]): Unit           = extern
  def wgpuDeviceRelease(p: Ptr[Byte]): Unit            = extern
  def wgpuAdapterRelease(p: Ptr[Byte]): Unit           = extern
  def wgpuInstanceRelease(p: Ptr[Byte]): Unit          = extern

@extern
object Shim:
  def sirin_wgpu_request_adapter(instance: Ptr[Byte], options: Ptr[RequestAdapterOptions], out: Ptr[RequestResult], futureId: Ptr[CUnsignedLongLong]): Unit = extern
  def sirin_wgpu_request_device(adapter: Ptr[Byte], out: Ptr[RequestResult], futureId: Ptr[CUnsignedLongLong]): Unit = extern
  def sirin_wgpu_surface_capabilities_free(caps: Ptr[SurfaceCapabilities]): Unit = extern
  def sirin_abi_echo_color(in: Ptr[Color], out: Ptr[Color]): Unit = extern
  def sirin_abi_layout(which: CInt, out: Ptr[CSize]): CSize = extern
  def sirin_sdl_constants(out: Ptr[CUnsignedLongLong]): CSize = extern
  def sirin_resident_bytes(): CUnsignedLongLong = extern

@extern
object Sdl:
  def SDL_Init(flags: CUnsignedInt): CBool = extern
  def SDL_Quit(): Unit = extern
  def SDL_GetError(): CString = extern
  def SDL_CreateWindow(title: CString, w: CInt, h: CInt, flags: CUnsignedLongLong): Ptr[Byte] = extern
  def SDL_DestroyWindow(window: Ptr[Byte]): Unit = extern
  def SDL_SetWindowSize(window: Ptr[Byte], w: CInt, h: CInt): CBool = extern
  def SDL_GetWindowSizeInPixels(window: Ptr[Byte], w: Ptr[CInt], h: Ptr[CInt]): CBool = extern
  def SDL_GetWindowDisplayScale(window: Ptr[Byte]): CFloat = extern
  def SDL_Metal_CreateView(window: Ptr[Byte]): Ptr[Byte] = extern
  def SDL_Metal_DestroyView(view: Ptr[Byte]): Unit = extern
  def SDL_Metal_GetLayer(view: Ptr[Byte]): Ptr[Byte] = extern
  def SDL_PollEvent(event: Ptr[Byte]): CBool = extern
  @blocking def SDL_WaitEventTimeout(event: Ptr[Byte], timeoutMs: CInt): CBool = extern

object SdlConst:
  final val InitVideo: CUnsignedInt          = 0x20.toUInt
  final val WindowResizable: Long            = 0x20L
  final val WindowHighPixelDensity: Long     = 0x2000L
  final val WindowMetal: Long                = 0x20000000L

  /** Event-type values and layout facts read from the SDL headers via the shim. */
  final case class Values(
      quit: Long,
      windowExposed: Long,
      windowPixelSizeChanged: Long,
      windowCloseRequested: Long,
      windowDisplayScaleChanged: Long,
      keyDown: Long,
      keyOffset: Long,
      keyEscape: Long,
      eventSize: Long
  )

  def load(): Values =
    val out = stackalloc[CUnsignedLongLong](16)
    val n   = Shim.sirin_sdl_constants(out).toInt
    require(n == 9, s"unexpected SDL constant count $n")
    def v(i: Int) = out(i).toLong
    Values(v(0), v(1), v(2), v(3), v(4), v(5), v(6), v(7), v(8))
