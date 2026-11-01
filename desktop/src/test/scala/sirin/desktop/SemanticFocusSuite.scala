package sirin.desktop

import scala.annotation.unused
import sirin.*
import sirin.engine.*
import sirin.host.*

class SemanticFocusSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"expected commit: $other")

  private def part(id: Long, x: Int): SemanticNode =
    SemanticNode(PartId(id), Semantics(Role.GraphicNode, name = Some(s"Part $id"), selected = Some(false)),
      Rect(Point(x.lp, 0.lp), Size(80.lp, 35.lp)), Set(SemanticAction.Activate))

  private final class Fixture extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val owner = runtime.scope("semantic-focus")
    val ref = owner.ref()
    val ancestor = owner.ref()
    val other = owner.ref()
    val popupRef = owner.ref()
    val open = owner.cell(false)
    val parts = owner.cell(Vector(part(1, 0), part(2, 100)))
    val events = owner.cell(Vector.empty[FocusEvent])
    val refuseLost = owner.cell(false)
    val enabled = owner.cell(true)
    val visible = owner.cell(true)
    val definition = new Element.Simple[(Vector[SemanticNode], Boolean)]:
      def paint(@unused state: (Vector[SemanticNode], Boolean), @unused size: Size,
          @unused canvas: Canvas)(using @unused committed: Committed): Unit = ()
      override def activation(state: (Vector[SemanticNode], Boolean))(using
          @unused committed: Committed): Option[ElementActivation] = Some(ElementActivation(PartId(1), state._2))
      override def semantics(state: (Vector[SemanticNode], Boolean), @unused size: Size)(using
          @unused committed: Committed): Seq[SemanticNode] = state._1
      override def focus(event: FocusEvent)(using UiTxn): Unit =
        UiTxn.require(event != FocusEvent.Lost || !refuseLost.get, "keep focus")
        events.update(_ :+ event)
    val plain = new Element.Simple[Unit]:
      def paint(@unused state: Unit, @unused size: Size, @unused canvas: Canvas)(using
          @unused committed: Committed): Unit = ()
    val view = Column(
      Show(visible)(Element.simple("parts", parts.zip(enabled), definition, focusable = true).height(40.lp).ref(ref)),
      Element.simple("other", Signal.const(()), plain, focusable = true).height(40.lp).ref(other),
      Popup(open, anchor = ref, modal = true, initialFocus = Some(popupRef),
        restoreFocus = FocusRestore.Previous)(
        Element.simple("popup", Signal.const(()), plain, focusable = true)
          .width(100.lp).height(40.lp).ref(popupRef))
    ).ref(ancestor)
    val mounted = runtime.mount(owner, view)
    owner.observe(mounted.snapshot)(_ => ())
    val controller = new EditorController(runtime, _ => fail("no text"), () => "", _ => (), () => ())
    val addressBook = new NativeSemanticTree
    var revision = 0L
    def value[A](signal: Signal[A]): A = runtime.read(signal) match
      case Value.Ok(value) => value
      case Value.Failed(error) => throw error
    def frame(): Vector[NativeSemanticNode] =
      val captured = runtime.withCommitted(reader => reader(mounted.snapshot) match
        case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot, reader)
        case Value.Failed(error) => throw error
      )
      controller.update(captured)
      revision += 1
      val layout = sirin.host.Layout(captured.tree, 200, 100)(_ => fail("no text"))
      controller.install(revision, layout)
      addressBook.capture(layout, controller.interaction.state.focused, runtime.referenceIdentities,
        controller.interaction.state.part)
    def request(reference: NodeRef, part: Option[PartId]): Boolean =
      ok(runtime.transact { part.fold(reference.focus())(reference.focusPart) })
      runtime.takeFocusRequests().exists(controller.focusRequest)
    def close(): Unit = runtime.close()

  test("public and native focus preserve the exact part without activating or selecting it") {
    val fixture = new Fixture
    try
      import fixture.*
      val nodes = frame()
      assert(nodes.forall(_.focusable))
      val second = nodes.find(_.part.part == PartId(2)).get
      assert(request(ref, Some(PartId(2))))
      assertEquals(value(ref.focusedPart), Some(PartId(2)))
      assertEquals(value(ancestor.focusedPart), None)
      assertEquals(value(ancestor.containsFocus), true)
      assertEquals(frame().filter(_.focused).map(_.id), Vector(second.id))
      assert(frame().forall(_.part.semantics.selected.contains(false)))
      assert(!controller.accessibilityFocus(second.instance.get, PartId(1), revision - 1))
      assert(!controller.accessibilityFocus(second.instance.get, PartId(99), revision))
      assert(controller.accessibilityFocus(second.instance.get, PartId(1), revision))
      assertEquals(value(ref.focusedPart), Some(PartId(1)))
      assertEquals(value(events), Vector(FocusEvent.Gained), "part changes do not leave and re-enter the element")
      assertEquals(frame().filter(_.focused).map(_.part.part), Vector(PartId(1)))
      assert(request(ref, None))
      assertEquals(value(ref.focusedPart), None)
    finally fixture.close()
  }

  test("repeated queued focus requests preserve the final public intent") {
    val fixture = new Fixture
    try
      import fixture.*
      frame()
      ok(runtime.transact {
        ref.focusPart(PartId(2))
        ref.focusPart(PartId(1))
        ref.focusPart(PartId(2))
      })
      val requests = runtime.takeFocusRequests()
      assertEquals(requests.map(_.part), Vector(Some(PartId(2)), Some(PartId(1)), Some(PartId(2))))
      DesktopApplicationPlatform.pendingFocus(requests).foreach(request => assert(controller.focusRequest(request)))
      assertEquals(value(ref.focusedPart), Some(PartId(2)))
    finally fixture.close()
  }

  test("part removal, deactivation and retirement clear published focus without selecting a replacement") {
    val fixture = new Fixture
    try
      import fixture.*
      val second = frame().find(_.part.part == PartId(2)).get
      assert(request(ref, Some(PartId(2))))
      controller.nativeFocus(false)
      assertEquals(value(ref.focusedPart), None)
      assertEquals(controller.interaction.state.part, Some(PartId(2)))
      controller.nativeFocus(true)
      assertEquals(value(ref.focusedPart), Some(PartId(2)))
      ok(runtime.transact { parts.set(Vector(part(1, 0))) })
      frame()
      assertEquals(value(ref.focusedPart), None)
      assertEquals(value(ref.hasFocus), true)
      assert(!request(ref, Some(PartId(2))))
      assert(!controller.accessibilityFocus(second.instance.get, PartId(2), revision))
      assert(request(ref, Some(PartId(1))))
      ok(runtime.transact { visible.set(false) })
      frame()
      assertEquals(value(ref.focusedPart), None)
      assertEquals(value(ref.hasFocus), false)
      assert(!controller.accessibilityFocus(second.instance.get, PartId(1), revision))
    finally fixture.close()
  }

  test("disabled parts reject native focus and ordinary element focus-loss veto retains the previous part") {
    val fixture = new Fixture
    try
      import fixture.*
      val node = frame().head
      assert(request(ref, Some(PartId(2))))
      ok(runtime.transact { refuseLost.set(true) })
      assert(!request(other, None))
      assertEquals(value(ref.focusedPart), Some(PartId(2)))
      ok(runtime.transact { enabled.set(false) })
      frame()
      assertEquals(value(ref.focusedPart), Some(PartId(2)))
      assert(!controller.accessibilityFocus(node.instance.get, PartId(1), revision))
      assert(request(ref, Some(PartId(1))), "programmatic focus keeps existing disabled-focus policy")
    finally fixture.close()
  }

  test("modal restoration retains the previous part and falls back to the element when that part disappears") {
    val fixture = new Fixture
    try
      import fixture.*
      val instance = frame().head.instance.get
      assert(request(ref, Some(PartId(2))))
      ok(runtime.transact { open.set(true) })
      frame()
      assertEquals(value(ref.focusedPart), None)
      assertEquals(value(popupRef.hasFocus), true)
      assert(!request(ref, Some(PartId(2))))
      assert(!controller.accessibilityFocus(instance, PartId(2), revision))
      ok(runtime.transact { open.set(false) })
      frame()
      assertEquals(value(ref.focusedPart), Some(PartId(2)))
      ok(runtime.transact { open.set(true) })
      frame()
      ok(runtime.transact { parts.set(Vector(part(1, 0))) })
      frame()
      ok(runtime.transact { open.set(false) })
      frame()
      assertEquals(value(ref.hasFocus), true)
      assertEquals(value(ref.focusedPart), None)
    finally fixture.close()
  }

  test("clipped semantic parts cannot receive focus and the first exposed part is the native default") {
    val fixture = new Fixture
    try
      import fixture.*
      frame()
      ok(runtime.transact { parts.set(Vector(part(1, 300), part(2, 0))) })
      val nodes = frame()
      assertEquals(nodes.map(_.part.part), Vector(PartId(2)))
      assert(!request(ref, Some(PartId(1))))
      assert(!controller.accessibilityFocus(nodes.head.instance.get, PartId(1), revision))
      assert(request(ref, None))
      assertEquals(frame().filter(_.focused).map(_.part.part), Vector(PartId(2)))
      assertEquals(value(ref.focusedPart), None)
    finally fixture.close()
  }
