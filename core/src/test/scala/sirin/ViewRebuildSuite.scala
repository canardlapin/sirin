package sirin

class ViewRebuildSuite extends munit.FunSuite:
  final case class Custom(label: String, decorations: Vector[Decoration] = Vector.empty) extends View
  given ViewRebuild[Custom] = (view, decoration) => view.copy(decorations = view.decorations :+ decoration)

  test("custom final views reconstruct immutably without casts") {
    val original = Custom("content")
    val decorated: Custom = original.padding(4.lp).width(20.lp)
    assertEquals(original.decorations, Vector.empty)
    assertEquals(decorated.decorations, Vector(Decoration.Padding(4.lp, 4.lp), Decoration.Width(20.lp)))
    assertEquals(decorated.label, original.label)
  }
