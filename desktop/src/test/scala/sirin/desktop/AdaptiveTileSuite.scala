package sirin.desktop

import sirin.graphics.*
import sirin.graphics.desktop.GpuTileCache
import sirin.render.{BatchGpu, BatchLimits, OffscreenGpu, PixelProgram}

class AdaptiveTileSuite extends munit.FunSuite:
  private def scene: Drawing = Drawing.record { canvas =>
    canvas.fill(Path.rectangle(Rect(0, 0, 96, 80)), Paint.Solid(Color(0.1, 0.2, 0.3)))
    canvas.clipped(Path.rectangle(Rect(1.25, 1.5, 93, 76))) { clipped =>
      clipped.layer(0.7) { group =>
        for i <- 0 until 48 do
          group.fill(
            Path.rectangle(Rect(
              (i % 8) * 12 + 0.25,
              (i / 8) * 12 + 0.5,
              6 + (i % 5),
              6 + (i % 7)
            )),
            Paint.Solid(Color((i % 7) / 7.0, 0.6, 0.9, 0.8))
          )
      }
    }
  }

  test("resource-driven subdivision matches independent pixels, reuses completed tiles and keeps fixed inventories") {
    val gpu = BatchGpu.open(BatchLimits(meshEntries = 12))
    val reference = OffscreenGpu.open()
    val cache = new GpuTileCache(gpu)
    try
      for scale <- Vector(1.0, 1.25, 1.5, 2.0) do
        val drawing = scene
        val width = (96 * scale).toInt
        val height = (80 * scale).toInt
        val prepared = DrawingPreparation.prepare(drawing, gpu.target(width, height, scale)).toOption.get
        val expected = reference.render(PixelProgram.compile(
          DrawingPreparation.prepare(drawing, reference.target(width, height, scale)).toOption.get
        ))
        val frame = cache.render(prepared, () => false)
        try
          assert(cache.tileCount > ((width + 127) / 128) * ((height + 127) / 128))
          assert(cache.tileCount <= 256)
          assertEquals(gpu.stats.pinnedEntries, 0L)
          assert(gpu.stats.entries <= 12)
          val actual = frame.readback().pixelsCopy
          val pixels = expected.pixelsCopy
          val worst = actual.indices.map(i => math.abs((actual(i) & 255) - (pixels(i) & 255))).max
          // Same independent per-channel bound used by GpuFrameSuite.
          assert(worst <= 2, s"adaptive tile pixel byte difference=$worst")
          val before = gpu.stats
          val reused = cache.render(prepared, () => false)
          try
            assertEquals(cache.lastCompiled, 0L)
            assertEquals(gpu.stats, before)
            assert(java.util.Arrays.equals(reused.readback().pixelsCopy, actual))
          finally reused.close()
        finally frame.close()
    finally
      cache.close()
      gpu.close()
      reference.close()
  }

  test("a scene over the minimum-tile limit refuses explicitly and a later bounded frame still works") {
    val gpu = BatchGpu.open(BatchLimits(meshEntries = 4))
    val cache = new GpuTileCache(gpu, 32)
    try
      val dense = Drawing.record { canvas =>
        for i <- 0 until 20 do
          canvas.fill(
            Path.rectangle(Rect(0, 0, 15 + i * 0.1, 15 + i * 0.2)),
            Paint.Solid(Color(0.2, 0.4, 0.6))
          )
      }
      val target = gpu.target(32, 32, 1)
      val error = intercept[IllegalArgumentException] {
        cache.render(DrawingPreparation.prepare(dense, target).toOption.get, () => false)
      }
      assert(error.getMessage.contains("GPU tile resource budget at minimum tile size"))
      assertEquals(gpu.stats.pinnedEntries, 0L)
      val simple = Drawing.record(_.fill(Path.rectangle(Rect(1, 1, 10, 10)), Paint.Solid(Color(1, 0, 0))))
      val frame = cache.render(DrawingPreparation.prepare(simple, target).toOption.get, () => false)
      try assert(frame.readback().pixelsCopy.exists(_ != 0))
      finally frame.close()
      assertEquals(gpu.stats.pinnedEntries, 0L)
    finally
      cache.close()
      gpu.close()
  }

  test("cancelling subdivision does not invalidate the previously exported frame") {
    val gpu = BatchGpu.open(BatchLimits(meshEntries = 12))
    val cache = new GpuTileCache(gpu)
    try
      val target = gpu.target(96, 80, 1)
      val simple = Drawing.record(_.fill(Path.rectangle(Rect(1, 1, 10, 10)), Paint.Solid(Color(1, 0, 0))))
      val old = cache.render(DrawingPreparation.prepare(simple, target).toOption.get, () => false)
      try
        val pixels = old.readback().pixelsCopy
        var checks = 0
        var cancelled = false
        try
          cache.render(
            DrawingPreparation.prepare(scene, target).toOption.get,
            () => {
              checks += 1
              checks > 4
            }
          )
        catch case _: InterruptedException => cancelled = true
        assert(cancelled)
        assertEquals(gpu.stats.pinnedEntries, 0L)
        assert(java.util.Arrays.equals(old.readback().pixelsCopy, pixels))
        val next = cache.render(DrawingPreparation.prepare(simple, target).toOption.get, () => false)
        try assert(java.util.Arrays.equals(next.readback().pixelsCopy, pixels))
        finally next.close()
        assertEquals(gpu.stats.pinnedEntries, 0L)
      finally old.close()
    finally
      cache.close()
      gpu.close()
  }

  test("a later tile refusal releases earlier pending programs before returning") {
    val gpu = BatchGpu.open(BatchLimits(meshEntries = 4))
    val cache = new GpuTileCache(gpu, 32)
    try
      val drawing = Drawing.record { canvas =>
        canvas.fill(Path.rectangle(Rect(1, 1, 10, 10)), Paint.Solid(Color(1, 0, 0)))
        for i <- 0 until 20 do
          canvas.fill(
            Path.rectangle(Rect(33, 1, 15 + i * 0.1, 15 + i * 0.2)),
            Paint.Solid(Color(0.2, 0.4, 0.6))
          )
      }
      val prepared = DrawingPreparation.prepare(drawing, gpu.target(64, 32, 1)).toOption.get
      val error = intercept[IllegalArgumentException](cache.render(prepared, () => false))
      assert(error.getMessage.contains("GPU tile resource budget at minimum tile size"))
      assertEquals(gpu.stats.pinnedEntries, 0L)
      assertEquals(gpu.stats.programs, 0L)
    finally
      cache.close()
      gpu.close()
  }
