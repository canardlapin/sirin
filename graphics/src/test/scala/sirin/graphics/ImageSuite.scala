package sirin.graphics

class ImageSuite extends munit.FunSuite:
  private def bytes(values: Int*): Array[Byte] = values.map(_.toByte).toArray

  test("snapshot retains straight sRGB channels and isolates input and output mutation") {
    val source = bytes(255, 0, 0, 0, 0, 0, 255, 128)
    val image = ImageSnapshot.copyRgba8(2, 1, source)
    source(0) = 0
    assertEquals(image.pixel(0, 0), Color(1, 0, 0, 0))
    assertEquals(image.pixel(1, 0), Color.rgba8(0, 0, 255, 128))
    val exported = image.pixelsCopy
    exported(6) = 0
    assertEquals(image.pixel(1, 0), Color.rgba8(0, 0, 255, 128))
    assertEquals(image.byteSize, 8)
    assertEquals(image.format, ImageFormat.Rgba8SrgbStraight)
  }

  test("row import omits padding, tolerates an unpadded final row and copies in row order") {
    val source = bytes(1, 2, 3, 4, 5, 6, 7, 8, 99, 99, 99, 99, 9, 10, 11, 12, 13, 14, 15, 16)
    val image = ImageSnapshot.copyRgba8Rows(2, 2, 12, source)
    assertEquals(image.pixelsCopy.toVector, (1 to 16).map(_.toByte).toVector)
    assertEquals(image.pixel(0, 1), Color.rgba8(9, 10, 11, 12))
    assertEquals(image.byteSize, 16)
    val padded = ImageSnapshot.copyRgba8Rows(2, 2, 12, source ++ bytes(99, 99, 99, 99))
    assertEquals(padded.pixelsCopy.toVector, image.pixelsCopy.toVector)
  }

  test("exports respect destination bounds and never lend internal storage") {
    val image = ImageSnapshot.copyRgba8(1, 1, bytes(1, 2, 3, 4))
    val destination = Array.fill[Byte](8)(99)
    image.copyPixelsTo(destination, 2)
    assertEquals(destination.toVector, bytes(99, 99, 1, 2, 3, 4, 99, 99).toVector)
    intercept[IllegalArgumentException](image.copyPixelsTo(destination, -1))
    intercept[IllegalArgumentException](image.copyPixelsTo(destination, Int.MaxValue))
    intercept[IllegalArgumentException](image.copyPixelsTo(new Array[Byte](3)))
    intercept[IllegalArgumentException](image.pixel(-1, 0))
    intercept[IllegalArgumentException](image.pixel(1, 0))
    intercept[IllegalArgumentException](image.pixel(0, 1))
  }

  test("invalid dimensions, overflow, source lengths and row strides fail before allocation") {
    val empty = Array.emptyByteArray
    for (width, height) <- Vector((0, 1), (1, 0), (-1, 1), (Int.MaxValue, Int.MaxValue),
        (Int.MaxValue, 1), (1, Int.MaxValue)) do
      intercept[IllegalArgumentException](ImageSnapshot.copyRgba8(width, height, empty))
    intercept[IllegalArgumentException](ImageSnapshot.copyRgba8(1, 1, new Array[Byte](3)))
    intercept[IllegalArgumentException](ImageSnapshot.copyRgba8(1, 1, new Array[Byte](5)))
    intercept[IllegalArgumentException](ImageSnapshot.copyRgba8Rows(1, 1, 3, new Array[Byte](4)))
    intercept[IllegalArgumentException](ImageSnapshot.copyRgba8Rows(1, 2, Int.MaxValue, new Array[Byte](8)))
  }

  test("fractional source crops are retained and invalid regions are rejected") {
    val image = ImageSnapshot.copyRgba8(2, 1, bytes(255, 0, 0, 255, 0, 0, 255, 255))
    assertEquals(image.full.source, Rect(0, 0, 2, 1))
    val crop = ImageRegion(image, Rect(0.25, 0, 1.5, 1))
    assertEquals(crop.source.x, 0.25)
    intercept[IllegalArgumentException](crop.copy(source = Rect(-0.25, 0, 1, 1)))
    intercept[IllegalArgumentException](crop.copy(source = Rect(0, 0, 0, 1)))
    intercept[IllegalArgumentException](crop.copy(source = Rect(0, 0, 3, 1)))
  }

  test("image recording preserves sampling, destination, opacity and enclosing scope order") {
    val image = ImageSnapshot.copyRgba8(1, 1, bytes(255, 0, 0, 128))
    val destination = Rect(0.5, 1.25, 30, 40)
    val move = Affine.translation(3, 7)
    val drawing = Drawing.record { c =>
      c.layer(0.3) { layer =>
        layer.transformed(move)(_.image(image.full, destination, Sampling.Nearest, 0.5))
      }
    }
    assertEquals(drawing.commands, Vector(
      DrawCommand.BeginLayer(0.3), DrawCommand.BeginTransform(move),
      DrawCommand.Image(image.full, destination, Sampling.Nearest, 0.5),
      DrawCommand.EndTransform, DrawCommand.EndLayer
    ))
    for opacity <- Vector(-0.1, 1.1, Double.NaN, Double.PositiveInfinity) do
      intercept[IllegalArgumentException](Drawing.record(_.image(image.full, destination, opacity = opacity)))
  }

  test("replacing pixels publishes a new snapshot while old recordings retain original pixels") {
    val source = bytes(255, 0, 0, 255)
    val before = ImageSnapshot.copyRgba8(1, 1, source)
    val drawing = Drawing.record(_.image(before.full, Rect(0, 0, 10, 10)))
    source(0) = 0
    source(1) = 127
    val after = ImageSnapshot.copyRgba8(1, 1, source)
    val recorded = drawing.commands.collectFirst { case DrawCommand.Image(region, _, _, _) => region.image }.get
    assert(recorded eq before)
    assertEquals(recorded.pixel(0, 0), Color.rgba8(255, 0, 0))
    assertEquals(after.pixel(0, 0), Color.rgba8(0, 127, 0))
  }

  test("images from rolled-back scopes do not survive in resource declarations") {
    val image = ImageSnapshot.copyRgba8(1, 1, bytes(1, 2, 3, 4))
    val drawing = Drawing.record { c =>
      intercept[RuntimeException] {
        c.layer(0.5) { layer =>
          layer.image(image.full, Rect(0, 0, 1, 1))
          throw new RuntimeException("rollback")
        }
      }
    }
    assertEquals(DrawingResources.inspect(drawing).toOption.get.images, Vector.empty)
  }
