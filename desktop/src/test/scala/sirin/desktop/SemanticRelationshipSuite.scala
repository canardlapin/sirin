package sirin.desktop

import scala.annotation.unused
import sirin.*
import sirin.engine.*
import sirin.host.*

class SemanticRelationshipSuite extends munit.FunSuite:
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"expected commit: $other")

  private val definition = new Element.Simple[Vector[SemanticNode]]:
    def paint(@unused state: Vector[SemanticNode], @unused size: Size, @unused canvas: Canvas)(using
        @unused committed: Committed
    ): Unit = ()
    override def semantics(state: Vector[SemanticNode], @unused size: Size)(using
        @unused committed: Committed
    ): Seq[SemanticNode] = state

  private def part(id: Long, semantics: Semantics, x: Int = 0): SemanticNode =
    SemanticNode(PartId(id), semantics, Rect(Point(x.lp, 0.lp), Size(100.lp, 30.lp)), Set.empty)

  private def block(semantics: Signal[Semantics]): View =
    Element.simple("semantic-block", semantics.map(s => Vector(part(0, s))), definition).height(36.lp)

  private def label(name: String): View = block(Signal.const(Semantics(Role.Label, name = Some(name))))

  private final class Fixture(build: Scope => View) extends AutoCloseable:
    val runtime = new HeadlessRuntime
    val owner = runtime.scope("relationships")
    val mounted = runtime.mount(owner, build(owner))
    owner.observe(mounted.snapshot)(_ => ())
    val tree = new NativeSemanticTree
    def frame(height: Int = 200): Vector[NativeSemanticNode] =
      val captured = runtime.withCommitted(reader => reader(mounted.snapshot) match
        case Value.Ok(snapshot) => CommittedTree.captureWithBindings(snapshot, reader)
        case Value.Failed(error) => throw error
      )
      val layout = sirin.host.Layout(captured.tree, 300, height)(_ => fail("no text declared"))
      tree.capture(layout, None, runtime.referenceIdentities)
    def close(): Unit = runtime.close()

  private def named(nodes: Vector[NativeSemanticNode], name: String): NativeSemanticNode =
    nodes.find(_.part.semantics.name.contains(name)).getOrElse(fail(s"missing $name"))

  test("forward relationships use live identities and omit hidden, clipped, foreign and other-window targets") {
    var labelRef: NodeRef = null
    var show: Cell[Boolean] = null
    var source: Cell[Semantics] = null
    val fixture = new Fixture(scope => {
      labelRef = scope.ref()
      val help = scope.ref()
      show = scope.cell(true)
      source = scope.cell(Semantics(Role.Button, name = Some("Source"),
        labelledBy = Some(SemanticTarget(labelRef)), describedBy = Some(SemanticTarget(help)),
        positionInSet = Some(2 -> 100)))
      Column(block(source), Show(show)(label("Name").ref(labelRef)), label("Help").ref(help))
    })
    val foreign = new HeadlessRuntime
    try
      val first = fixture.frame()
      val initial = named(first, "Source")
      val oldLabel = named(first, "Name").id
      assertEquals(initial.labelledBy, oldLabel)
      assertEquals(initial.describedBy, named(first, "Help").id)
      assertEquals(initial.relationshipFacts.toVector.takeRight(2), Vector(2L, 100L))
      assertEquals(named(fixture.frame(36), "Source").labelledBy, 0L)
      ok(fixture.runtime.transact(show.set(false)))
      assertEquals(named(fixture.frame(), "Source").labelledBy, 0L)
      ok(fixture.runtime.transact(show.set(true)))
      val restored = fixture.frame()
      assertNotEquals(named(restored, "Name").id, oldLabel)
      assertEquals(named(restored, "Source").labelledBy, named(restored, "Name").id)
      assertEquals(named(restored, "Source").id, initial.id)
      val foreignOwner = foreign.scope("relationships")
      val foreignRef = foreignOwner.ref()
      val other = foreign.mount(foreignOwner, Spacer(10.lp).ref(foreignRef))
      foreignOwner.observe(other.snapshot)(_ => ())
      assertEquals(foreign.referenceIdentities.head._2,
        fixture.runtime.referenceIdentities.find(_._1 eq labelRef).get._2)
      ok(fixture.runtime.transact(source.update(_.copy(labelledBy = Some(SemanticTarget(foreignRef))))))
      assertEquals(named(fixture.frame(), "Source").labelledBy, 0L, "equal IDs from another runtime cannot match")
      val otherWindow = fixture.owner.ref()
      val separate = fixture.runtime.mount(fixture.owner, label("Other window").ref(otherWindow))
      fixture.owner.observe(separate.snapshot)(_ => ())
      ok(fixture.runtime.transact(source.update(_.copy(labelledBy = Some(SemanticTarget(otherWindow)),
        positionInSet = Some(0 -> 10)))))
      val outside = named(fixture.frame(), "Source")
      assertEquals(outside.labelledBy, 0L)
      assertEquals(outside.relationshipFacts.toVector.takeRight(2), Vector(0L, 0L))
    finally
      fixture.close()
      foreign.close()
  }

  test("active descendants require ancestry and explicit parts resolve uniquely within the referenced view") {
    var container: Cell[Semantics] = null
    var source: Cell[Semantics] = null
    var sibling: NodeRef = null
    var ambiguous: NodeRef = null
    val fixture = new Fixture(scope => {
      val parts = scope.ref()
      sibling = scope.ref()
      ambiguous = scope.ref()
      container = scope.cell(Semantics(Role.List, name = Some("List"),
        activeDescendant = Some(SemanticTarget(parts, Some(PartId(20))))))
      source = scope.cell(Semantics(Role.Button, name = Some("Source"),
        labelledBy = Some(SemanticTarget(ambiguous, Some(PartId(0))))))
      val items = Vector(part(10, Semantics(Role.ListItem, name = Some("First"), positionInSet = Some(1 -> 500))),
        part(20, Semantics(Role.ListItem, name = Some("Second"), positionInSet = Some(400 -> 500)), 100))
      Column(
        Element.simple("parts", Signal.const(items), definition).height(36.lp).ref(parts).semantics(container),
        block(source),
        Row(label("A").width(100.lp), label("B").width(100.lp)).ref(ambiguous),
        label("Sibling").ref(sibling)
      )
    })
    try
      val first = fixture.frame()
      assertEquals(named(first, "List").activeDescendant, named(first, "Second").id)
      assertEquals(named(first, "Second").relationshipFacts.toVector.takeRight(2), Vector(400L, 500L))
      assertEquals(named(first, "Source").labelledBy, 0L)
      ok(fixture.runtime.transact {
        source.update(_.copy(labelledBy = Some(SemanticTarget(ambiguous))))
        container.update(_.copy(activeDescendant = Some(SemanticTarget(sibling))))
      })
      val changed = fixture.frame()
      assertEquals(named(changed, "Source").labelledBy, named(changed, "A").id)
      assertEquals(named(changed, "List").activeDescendant, 0L, "a sibling is not a descendant")
    finally fixture.close()
  }

  test("modal publication does not retain relationships to excluded background controls") {
    val fixture = new Fixture(scope => {
      val background = scope.ref()
      val anchor = scope.ref()
      val open = scope.cell(true)
      Column(label("Background").ref(background), label("Anchor").ref(anchor),
        Popup(open, anchor, modal = true)(
          block(Signal.const(Semantics(Role.Button, name = Some("Inside"),
            labelledBy = Some(SemanticTarget(background)))))
            .semantics(Semantics(Role.Dialog, name = Some("Dialog")))
        ))
    })
    try
      val nodes = fixture.frame()
      assert(!nodes.exists(_.part.semantics.name.contains("Background")))
      assertEquals(named(nodes, "Inside").labelledBy, 0L)
      assert(named(nodes, "Dialog").modal)
    finally fixture.close()
  }
