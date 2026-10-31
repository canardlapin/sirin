package sirin.host

import sirin.*
import sirin.engine.*

/** Row and Column run as a public `Layout`; geometry is checked on the placed host tree. */
class StackLayoutSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A =
    result match
      case CommitResult.Committed(value) =>
        value
      case other =>
        fail(s"expected commit: $other")
  private def placed(view: View, width: Double = 100, height: Double = 60): Placed =
    val r = new HeadlessRuntime
    try
      val m = r.mount(r.scope("stack"), view)
      r.read(m.snapshot)
      val tree = r.withCommitted(reader =>
        reader(m.snapshot) match
          case Value.Ok(s) =>
            CommittedTree.capture(s, reader)
          case Value.Failed(error) =>
            throw error
      )
      assert(tree.content.isInstanceOf[Content.CustomLayout], s"${tree.content}")
      sirin.host.Layout(tree, width, height)(_ => fail("no text declared")).root
    finally r.close()
  private def boxes(view: View, width: Double = 100, height: Double = 60): Vector[Box] =
    placed(view, width, height).children.map(_.bounds)

  test("collapsed children take neither space nor gap, and the stack sizes to what remains") {
    val shown = Signal.const(false)
    val row = Row(Spacer(20.lp), Spacer(20.lp).visible(shown), Spacer(20.lp)).spacing(10.lp)
    assertEquals(boxes(row).map(_.x), Vector(0.0, 30.0))
    val column = Column(Row(Spacer(10.lp), Spacer(10.lp).visible(shown)).spacing(5.lp), Spacer(20.lp))
    assertEquals(boxes(column).map(b => (b.y, b.width, b.height)), Vector((0.0, 10.0, 10.0), (10.0, 20.0, 20.0)))
  }

  test("grow children share the space their siblings leave") {
    val row = Row(Spacer(20.lp), Spacer().grow, Spacer(10.lp), Spacer().grow)
    assertEquals(boxes(row).map(b => (b.x, b.width)), Vector((0.0, 20.0), (20.0, 35.0), (55.0, 10.0), (65.0, 35.0)))
    val column = Column(Spacer(10.lp), Spacer().grow)
    assertEquals(boxes(column).map(_.height), Vector(10.0, 50.0))
  }

  test("cross-axis alignment places start, center, end and stretch, and baseline stays refused") {
    def at(alignment: Alignment) = Row(Spacer(10.lp), Spacer(30.lp)).align(alignment)
    val nested = (a: Alignment) => Column(at(a), Spacer(40.lp))
    assertEquals(placed(nested(Alignment.Start)).children.head.children.map(_.bounds.y), Vector(0.0, 0.0))
    assertEquals(placed(nested(Alignment.Center)).children.head.children.map(_.bounds.y), Vector(10.0, 0.0))
    assertEquals(placed(nested(Alignment.End)).children.head.children.map(_.bounds.y), Vector(20.0, 0.0))
    val stretched = Column(Row(Spacer(10.lp)).align(Alignment.Stretch).height(24.lp))
    val child = placed(stretched).children.head.children.head.children.head
    assertEquals(child.bounds.height, 24.0)
    intercept[IllegalArgumentException](placed(Row(Spacer(10.lp)).align(Alignment.Baseline)))
  }

  test("layout children keep identity by key across slots, by slot otherwise, and refuse duplicate keys") {
    val r = new HeadlessRuntime
    try
      val owner = r.scope("layout")
      val policy =
        new LayoutPolicy[Unit]:
          def layout(state: Unit, c: Constraints, children: LayoutChildren)(using Committed): Size =
            (0 until children.count).foreach { i =>
              children.measure(i, c.loosen)
              children.place(i, Point.origin)
            }
            c.smallest
      var builds = 0
      val piece = Component[String]("piece") { (_, _) =>
        builds += 1
        Spacer(10.lp)
      }
      def scene(first: (String, Boolean), second: (String, Boolean)): View =
        def child(name: String, keyed: Boolean): View =
          if keyed then
            piece(name).keyed(name)
          else
            piece(name)
        Layout.named("pair", Signal.const(()), policy)(
          Slot("left") -> child(first._1, first._2),
          Slot("right") -> child(second._1, second._2)
        )
      val m = r.mount(owner, scene("a" -> true, "b" -> true))
      r.observe(m.snapshot)(_ => ())
      assertEquals(builds, 2)
      ok(
        r.transact {
          m.update(scene("b" -> true, "a" -> true))
        }
      )
      assertEquals(builds, 2, "keyed children move between slots without rebuilding")
      ok(
        r.transact {
          m.update(scene("x" -> false, "a" -> true))
        }
      )
      assertEquals(builds, 3)
      ok(
        r.transact {
          m.update(scene("y" -> false, "a" -> true))
        }
      )
      assertEquals(builds, 3, "an unkeyed child keeps its slot's identity")
      val duplicate =
        r.transact {
          m.update(scene("a" -> true, "a" -> true))
        }
      assert(!duplicate.isInstanceOf[CommitResult.Committed[?]], s"$duplicate")
    finally r.close()
  }
