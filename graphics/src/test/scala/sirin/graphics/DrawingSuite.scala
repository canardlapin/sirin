package sirin.graphics

class DrawingSuite extends munit.FunSuite:
  private val shape = Path.rectangle(Rect(0, 0, 10, 10))
  private val red = Paint.Solid(Color(1, 0, 0, 0.5))
  private val blue = Paint.Solid(Color(0, 0, 1, 0.5))

  test("translucent painter order is preserved and reusable values are shared") {
    val drawing = Drawing.record { c =>
      c.fill(shape, red)
      c.fill(shape, blue, FillRule.EvenOdd)
      c.stroke(shape, red, StrokeStyle(width = 0.5))
    }
    assertEquals(drawing.commands, Vector(
      DrawCommand.Fill(shape, red, FillRule.NonZero),
      DrawCommand.Fill(shape, blue, FillRule.EvenOdd),
      DrawCommand.Stroke(shape, red, StrokeStyle(width = 0.5))
    ))
    val DrawCommand.Fill(recordedPath, recordedPaint, _) = drawing.commands.head: @unchecked
    assert(recordedPath eq shape)
    assert(recordedPaint eq red)
  }

  test("transforms, clip establishment and isolated layer boundaries remain ordered") {
    val move = Affine.translation(10, 20)
    val scale = Affine.scale(2, 3)
    val drawing = Drawing.record { root =>
      root.transformed(move) { translated =>
        translated.clipped(shape, FillRule.EvenOdd) { clipped =>
          clipped.transformed(scale) { scaled =>
            scaled.layer(0.5) { layer =>
              layer.fill(shape, red)
              layer.fill(shape, blue)
            }
          }
        }
      }
      root.fill(shape, blue)
    }
    assertEquals(drawing.commands, Vector(
      DrawCommand.BeginTransform(move), DrawCommand.BeginClip(shape, FillRule.EvenOdd),
      DrawCommand.BeginTransform(scale), DrawCommand.BeginLayer(0.5),
      DrawCommand.Fill(shape, red, FillRule.NonZero), DrawCommand.Fill(shape, blue, FillRule.NonZero),
      DrawCommand.EndLayer, DrawCommand.EndTransform, DrawCommand.EndClip, DrawCommand.EndTransform,
      DrawCommand.Fill(shape, blue, FillRule.NonZero)
    ))
  }

  test("caught scope failure rolls back nested commands without losing preceding or later draws") {
    val drawing = Drawing.record { root =>
      root.fill(shape, red)
      intercept[RuntimeException] {
        root.transformed(Affine.translation(1, 2)) { c =>
          c.fill(shape, blue)
          c.clipped(shape) { clipped =>
            clipped.layer(0.5) { layer =>
              layer.fill(shape, red)
              throw new RuntimeException("failed drawing")
            }
          }
        }
      }
      root.fill(shape, blue)
    }
    assertEquals(drawing.commands, Vector(
      DrawCommand.Fill(shape, red, FillRule.NonZero), DrawCommand.Fill(shape, blue, FillRule.NonZero)
    ))
  }

  test("recovering inside a scope preserves the enclosing clip and layer") {
    val drawing = Drawing.record { root =>
      root.layer(0.5) { c =>
        c.clipped(shape) { clip =>
          intercept[RuntimeException] {
            clip.transformed(Affine.scale(2, 2)) { _ => throw new RuntimeException("recover") }
          }
          clip.fill(shape, red)
        }
      }
    }
    assertEquals(drawing.commands, Vector(
      DrawCommand.BeginLayer(0.5), DrawCommand.BeginClip(shape, FillRule.NonZero),
      DrawCommand.Fill(shape, red, FillRule.NonZero), DrawCommand.EndClip, DrawCommand.EndLayer
    ))
  }

  test("root and child capabilities expire at their respective lexical boundaries") {
    var child: Option[Canvas2D] = None
    var root: Option[Canvas2D] = None
    val drawing = Drawing.record { c =>
      root = Some(c)
      c.layer(0.5) { layer => child = Some(layer); layer.fill(shape, red) }
      intercept[IllegalArgumentException](child.get.fill(shape, blue))
    }
    val original = drawing.commands
    intercept[IllegalArgumentException](root.get.fill(shape, blue))
    intercept[IllegalArgumentException](child.get.layer(1)(_ => ()))
    assertEquals(drawing.commands, original)
  }

  test("failed root recording closes every escaped capability") {
    var root: Option[Canvas2D] = None
    var child: Option[Canvas2D] = None
    intercept[RuntimeException] {
      Drawing.record { c =>
        root = Some(c)
        c.layer(0.5) { layer =>
          child = Some(layer)
          layer.fill(shape, red)
          throw new RuntimeException("abort")
        }
      }
    }
    intercept[IllegalArgumentException](root.get.fill(shape, blue))
    intercept[IllegalArgumentException](child.get.fill(shape, blue))
  }

  test("drawing composition retains the recording instead of cloning its commands") {
    val child = Drawing.record(_.fill(shape, red))
    val parent = Drawing.record { c =>
      c.draw(child)
      c.transformed(Affine.translation(10, 10))(_.draw(child))
    }
    assertEquals(parent.commands, Vector(
      DrawCommand.Draw(child), DrawCommand.BeginTransform(Affine.translation(10, 10)),
      DrawCommand.Draw(child), DrawCommand.EndTransform
    ))
    assertEquals(child.commands.size, 1)
    assertEquals(Drawing.record(_ => ()).commands, Drawing.empty.commands)
  }

  test("instances retain per-mark order and constant paint columns") {
    val transforms = Vector(Affine.translation(2, 2), Affine.translation(1, 1))
    val batch = InstanceBatch(transforms, InstanceValues.PerInstance(Vector(red, blue)))
    val constant = batch.copy(paints = InstanceValues.Constant(red))
    val drawing = Drawing.record { c =>
      c.fill(shape, blue)
      c.instances(shape, batch, FillRule.EvenOdd)
      c.instances(shape, constant)
      c.fill(shape, red)
    }
    assertEquals(drawing.commands, Vector(
      DrawCommand.Fill(shape, blue, FillRule.NonZero),
      DrawCommand.FillInstances(shape, batch, FillRule.EvenOdd),
      DrawCommand.FillInstances(shape, constant, FillRule.NonZero),
      DrawCommand.Fill(shape, red, FillRule.NonZero)
    ))
    intercept[IllegalArgumentException](batch.copy(paints = InstanceValues.PerInstance(Vector(red))))
  }

  test("invalid group opacity is rejected before a scope can emit commands") {
    val drawing = Drawing.record { c =>
      for alpha <- Vector(-0.1, 1.1, Double.NaN, Double.PositiveInfinity) do
        intercept[IllegalArgumentException](c.layer(alpha)(_.fill(shape, red)))
      c.layer(0)(_.fill(shape, red))
      c.layer(1)(_.fill(shape, blue))
    }
    assertEquals(drawing.commands, Vector(
      DrawCommand.BeginLayer(0), DrawCommand.Fill(shape, red, FillRule.NonZero), DrawCommand.EndLayer,
      DrawCommand.BeginLayer(1), DrawCommand.Fill(shape, blue, FillRule.NonZero), DrawCommand.EndLayer
    ))
  }

  test("standalone public authoring example compiles and records without UI services") {
    val drawing = examples.VectorSpecimen.record()
    val content = drawing.commands.collectFirst { case DrawCommand.Draw(child) => child }.get
    val marks = content.commands.collectFirst { case DrawCommand.FillInstances(_, batch, _) => batch }.get
    assertEquals(marks.transforms.size, 3)
    assert(marks.paints.isInstanceOf[InstanceValues.Constant[?]])
  }
