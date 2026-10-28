package sirin.graphics

class DrawingResourcesSuite extends munit.FunSuite:
  private def image(red: Int): ImageSnapshot =
    ImageSnapshot.copyRgba8(1, 1, Array(red.toByte, 0.toByte, 0.toByte, 255.toByte))

  test("transitive snapshots are retained once in first-encounter order") {
    val a = image(10)
    val b = image(20)
    val c = image(30)
    val child = Drawing.record { canvas =>
      canvas.image(b.full, Rect(0, 0, 10, 10))
      canvas.image(a.full, Rect(0, 0, 20, 20))
    }
    val root = Drawing.record { canvas =>
      canvas.image(a.full, Rect(0, 0, 1, 1))
      canvas.draw(child)
      canvas.draw(child)
      canvas.image(c.full, Rect(0, 0, 1, 1))
    }
    val resources = DrawingResources.inspect(root).toOption.get
    assertEquals(resources.images, Vector(a, b, c))
    assertEquals(resources.uniqueDrawings, 2)
    assertEquals(resources.inspectedCommands, 6L)
    assertEquals(resources.imageBytes, 12L)
  }

  test("equal pixel contents in distinct snapshots do not alias cache identities") {
    val a = image(10)
    val b = image(10)
    val root = Drawing.record { c =>
      c.image(a.full, Rect(0, 0, 1, 1))
      c.image(b.full, Rect(0, 0, 1, 1))
    }
    val resources = DrawingResources.inspect(root).toOption.get
    assertEquals(resources.images, Vector(a, b))
    assertEquals(resources.imageBytes, 8L)
  }

  test("inspection is iterative and visits a deeply shared recording graph once") {
    val source = image(10)
    var root = Drawing.record(_.image(source.full, Rect(0, 0, 1, 1)))
    var depth = 0
    while depth < 10000 do
      val previous = root
      root = Drawing.record { c => c.draw(previous); c.draw(previous) }
      depth += 1
    val resources = DrawingResources.inspect(root, InspectionLimits(10001, 20001)).toOption.get
    assertEquals(resources.uniqueDrawings, 10001)
    assertEquals(resources.inspectedCommands, 20001L)
    assertEquals(resources.images, Vector(source))
    assertEquals(resources.imageBytes, 4L)
  }

  test("budgets are inclusive and return errors instead of partial dependency lists") {
    val source = image(10)
    val child = Drawing.record(_.image(source.full, Rect(0, 0, 1, 1)))
    val root = Drawing.record { c => c.draw(child); c.draw(child) }
    assert(DrawingResources.inspect(root, InspectionLimits(2, 3)).isRight)
    assertEquals(DrawingResources.inspect(root, InspectionLimits(1, 3)).left.toOption,
      Some(InspectionLimit.Drawings(1)))
    assertEquals(DrawingResources.inspect(root, InspectionLimits(2, 2)).left.toOption,
      Some(InspectionLimit.Commands(2)))
    assert(DrawingResources.inspect(Drawing.empty, InspectionLimits(1, 0)).isRight)
    assertEquals(DrawingResources.inspect(child, InspectionLimits(1, 0)).left.toOption,
      Some(InspectionLimit.Commands(0)))
    intercept[IllegalArgumentException](InspectionLimits(0, 1))
    intercept[IllegalArgumentException](InspectionLimits(1, -1))
  }

  test("inventory declares retained resources even for zero-opacity and empty destinations") {
    val source = image(10)
    val drawing = Drawing.record(_.image(source.full, Rect(0, 0, 0, 0), opacity = 0))
    assertEquals(DrawingResources.inspect(drawing).toOption.get.images, Vector(source))
  }

  test("public image-viewer specimen shares one snapshot across crops and placements") {
    val drawing = examples.ImageSpecimen.record()
    val resources = DrawingResources.inspect(drawing).toOption.get
    assertEquals(resources.images.size, 1)
    assertEquals(resources.imageBytes, 16L)
    assertEquals(resources.uniqueDrawings, 2)
  }
