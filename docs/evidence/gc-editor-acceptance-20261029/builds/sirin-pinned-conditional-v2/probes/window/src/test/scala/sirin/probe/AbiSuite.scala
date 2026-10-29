package sirin.probe

import scala.scalanative.unsafe.*

import WgpuTypes.*

/** P0.1: every struct Scala builds must match the C compiler's layout exactly. */
class AbiSuite extends munit.FunSuite:

  /** Size and field offsets reported by the shim for layout `which`. */
  private def cLayout(which: Int): List[Long] =
    val out = stackalloc[CSize](32)
    val n   = Shim.sirin_abi_layout(which, out).toInt
    assert(n > 0, s"shim has no layout $which")
    (0 until n).map(i => out(i).toLong).toList

  private def off(base: Ptr[?], field: Ptr[?]): Long = field.toLong - base.toLong

  private def check(name: String, which: Int, size: Long, offsets: List[Long]): Unit =
    test(s"$name layout matches C") {
      assertEquals(size :: offsets, cLayout(which))
    }

  locally {
    val p = stackalloc[ChainedStruct]()
    check("WGPUChainedStruct", 0, sizeOf[ChainedStruct].toLong, List(off(p, p.at1), off(p, p.at2)))
  }
  locally {
    val p = stackalloc[StringView]()
    check("WGPUStringView", 1, sizeOf[StringView].toLong, List(off(p, p.at1), off(p, p.at2)))
  }
  locally {
    val p = stackalloc[SurfaceSourceMetalLayer]()
    check("WGPUSurfaceSourceMetalLayer", 2, sizeOf[SurfaceSourceMetalLayer].toLong,
      List(off(p, p.at1), off(p, p.at2)))
  }
  locally {
    val p = stackalloc[SurfaceDescriptor]()
    check("WGPUSurfaceDescriptor", 3, sizeOf[SurfaceDescriptor].toLong, List(off(p, p.at1), off(p, p.at2)))
  }
  locally {
    val p = stackalloc[SurfaceConfiguration]()
    check("WGPUSurfaceConfiguration", 4, sizeOf[SurfaceConfiguration].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3), off(p, p.at4), off(p, p.at5),
        off(p, p.at6), off(p, p.at7), off(p, p.at8), off(p, p.at9), off(p, p.at10)))
  }
  locally {
    val p = stackalloc[SurfaceTexture]()
    check("WGPUSurfaceTexture", 5, sizeOf[SurfaceTexture].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3)))
  }
  locally {
    val p = stackalloc[Color]()
    check("WGPUColor", 6, sizeOf[Color].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3), off(p, p.at4)))
  }
  locally {
    val p = stackalloc[RenderPassColorAttachment]()
    check("WGPURenderPassColorAttachment", 7, sizeOf[RenderPassColorAttachment].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3), off(p, p.at4), off(p, p.at5),
        off(p, p.at6), off(p, p.at7)))
  }
  locally {
    val p = stackalloc[RenderPassDescriptor]()
    check("WGPURenderPassDescriptor", 8, sizeOf[RenderPassDescriptor].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3), off(p, p.at4), off(p, p.at5),
        off(p, p.at6), off(p, p.at7)))
  }
  locally {
    val p = stackalloc[SurfaceCapabilities]()
    check("WGPUSurfaceCapabilities", 9, sizeOf[SurfaceCapabilities].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3), off(p, p.at4), off(p, p.at5),
        off(p, p.at6), off(p, p.at7), off(p, p.at8)))
  }
  locally {
    val p = stackalloc[RequestAdapterOptions]()
    check("WGPURequestAdapterOptions", 10, sizeOf[RequestAdapterOptions].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3), off(p, p.at4), off(p, p.at5), off(p, p.at6)))
  }
  locally {
    val p = stackalloc[RequestResult]()
    check("sirin_request_result", 11, sizeOf[RequestResult].toLong,
      List(off(p, p.at1), off(p, p.at2), off(p, p.at3), off(p, p.at4)))
  }

  test("a struct passed by value through the shim round-trips intact") {
    val in  = stackalloc[Color]()
    val out = stackalloc[Color]()
    in._1 = 0.25; in._2 = -1.5; in._3 = Double.MaxValue; in._4 = Double.MinPositiveValue
    Shim.sirin_abi_echo_color(in, out)
    assertEquals((out._1, out._2, out._3, out._4), (0.25, -1.5, Double.MaxValue, Double.MinPositiveValue))
  }

  test("SDL constants are read from the headers") {
    val k = SdlConst.load()
    assertEquals(k.quit, 0x100L)
    assertEquals(k.keyDown, 0x300L)
    assertEquals(k.eventSize, 128L)
  }
