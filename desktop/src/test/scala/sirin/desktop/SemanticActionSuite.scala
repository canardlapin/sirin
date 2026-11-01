package sirin.desktop

import scala.annotation.unused
import sirin.*
import sirin.engine.*
import sirin.host.*

class SemanticActionSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"expected commit: $other")

  test("additional action declarations preserve names, distinguish Dismiss and bound native storage") {
    val actions = Set(SemanticAction.Increment, SemanticAction.Decrement, SemanticAction.Dismiss,
      SemanticAction.Custom("Dismiss"), SemanticAction.Custom("Renommer é"), SemanticAction.Activate)
    val declarations = NativeSemanticAction.declarations(actions)
    assertEquals(declarations, Vector(5 -> "", 6 -> "", 7 -> "", 8 -> "Dismiss", 8 -> "Renommer é"))
    assertEquals(declarations.flatMap((kind, name) => NativeSemanticAction.decode(kind, name)).toSet,
      actions - SemanticAction.Activate)
    assertEquals(NativeSemanticAction.decode(7, "Dismiss"), None)
    assertEquals(NativeSemanticAction.decode(8, ""), None)
    assertEquals(NativeSemanticAction.decode(99, "Unknown"), None)
    intercept[IllegalArgumentException](NativeSemanticAction.encode(SemanticAction.Custom("a\u0000b")))
    intercept[IllegalArgumentException](NativeSemanticAction.encode(SemanticAction.Custom("\ud800")))
    intercept[IllegalArgumentException](NativeSemanticAction.encode(SemanticAction.Custom("é" * 129)))
    assertEquals(NativeSemanticAction.encode(SemanticAction.Custom("é" * 128)), Some(8 -> ("é" * 128)))
    intercept[IllegalArgumentException] {
      NativeSemanticAction.declarations((1 to 33).map(i => SemanticAction.Custom(s"Action $i")).toSet)
    }
  }

  test("additional assistive actions address the declared part and reject stale, disabled and retired targets") {
    val runtime = new HeadlessRuntime
    try
      val owner = runtime.scope("semantic-actions")
      val visible = owner.cell(true)
      val enabled = owner.cell(true)
      val received = owner.cell(Vector.empty[(Long, SemanticAction)])
      val named = SemanticAction.Custom("Renommer é")
      val customDismiss = SemanticAction.Custom("Dismiss")
      val refuse = SemanticAction.Custom("Refuse")
      val definition = new Element.Simple[Boolean]:
        def paint(@unused state: Boolean, @unused size: Size, @unused canvas: Canvas)(using
            @unused committed: Committed
        ): Unit = ()
        override def activation(state: Boolean)(using @unused committed: Committed): Option[ElementActivation] =
          Some(ElementActivation(PartId(1), state))
        override def semantics(@unused state: Boolean, @unused size: Size)(using
            @unused committed: Committed
        ): Seq[SemanticNode] = Vector(
          SemanticNode(PartId(1), Semantics(Role.GraphicNode, name = Some("First")),
            Rect(Point.origin, Size(100.lp, 40.lp)), Set(SemanticAction.Increment, customDismiss)),
          SemanticNode(PartId(2), Semantics(Role.GraphicNode, name = Some("Second")),
            Rect(Point(100.lp, 0.lp), Size(100.lp, 40.lp)), Set(SemanticAction.Decrement,
              SemanticAction.Dismiss, named, refuse))
        )
        override def action(part: PartId, action: SemanticAction)(using UiTxn): Boolean =
          received.update(_ :+ (part.value -> action))
          UiTxn.require(action != refuse, "refused action")
          true
      val mounted = runtime.mount(owner, Show(visible)(Element.simple("parts", enabled, definition)))
      owner.observe(mounted.snapshot)(_ => ())
      val controller = new EditorController(runtime, _ => fail("no text input"), () => "", _ => (), () => ())
      val addressBook = new NativeSemanticTree
      var revision = 0L
      def frame(): Vector[NativeSemanticNode] =
        val tree = runtime.withCommitted(reader => reader(mounted.snapshot) match
          case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot, reader)
          case Value.Failed(error) => throw error
        )
        controller.update(tree)
        revision += 1
        val layout = sirin.host.Layout(tree.tree, 200, 40)(_ => fail("no text declared"))
        controller.install(revision, layout)
        addressBook.capture(layout, None)
      val nodes = frame()
      assertEquals(nodes.size, 2)
      val first = nodes.find(_.part.part == PartId(1)).get
      val second = nodes.find(_.part.part == PartId(2)).get
      assertEquals(first.instance, second.instance)
      assertNotEquals(first.id, second.id)
      val instance = first.instance.get
      assert(!controller.accessibilityAction(instance, PartId(1), SemanticAction.Increment, revision - 1))
      assert(!controller.accessibilityAction(instance, PartId(1), named, revision))
      assert(!controller.accessibilityAction(instance, PartId(99), SemanticAction.Dismiss, revision))
      for node <- nodes; (kind, name) <- NativeSemanticAction.declarations(node.part.actions) do
        val action = NativeSemanticAction.decode(kind, name).get
        val accepted = controller.accessibilityAction(instance, node.part.part, action, revision)
        assertEquals(accepted, action != refuse)
      val handled = ok(runtime.transact(received.get))
      assertEquals(handled.toSet, Set(1L -> SemanticAction.Increment, 1L -> customDismiss,
        2L -> SemanticAction.Decrement, 2L -> SemanticAction.Dismiss, 2L -> named))
      assertEquals(handled.size, 5, "refused action rolls back its writes")
      ok(runtime.transact(enabled.set(false)))
      val disabled = frame()
      assert(disabled.forall(!_.enabled))
      assert(!controller.accessibilityAction(instance, PartId(1), SemanticAction.Increment, revision))
      ok(runtime.transact(visible.set(false)))
      assertEquals(frame(), Vector.empty)
      assert(!controller.accessibilityAction(instance, PartId(2), named, revision))
      assertEquals(ok(runtime.transact(received.get)), handled)
    finally runtime.close()
  }
