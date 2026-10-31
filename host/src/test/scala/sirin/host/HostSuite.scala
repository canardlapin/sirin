package sirin.host

import sirin.*
import sirin.engine.*
import org.scalacheck.{Gen,Prop}

class HostSuite extends munit.ScalaCheckSuite:
  private def node(key: String,content: Content): Node = Node(NodeId(Vector(key)),content)
  private def sized(key: String,w: Double,h: Double): Node = node(key,Content.Styled(Hint.Width(w),node(key+"h",Content.Styled(Hint.Height(h),node(key+"s",Content.Space(0))))))
  private def layout(root: Node,w: Double=100,h: Double=100): LayoutResult =
    sirin.host.Layout(root,w,h)(_ => fail("spacer oracle must not request text"))
  private def ok[A](result: CommitResult[A]): A = result match
    case CommitResult.Committed(value) => value
    case other => fail(s"$other")
  private def captured(r: HeadlessRuntime,m: MountedView): Node =
    r.observe(m.snapshot)(_ => ())
    r.withCommitted { reader => reader(m.snapshot) match
      case Value.Ok(snapshot) => CommittedTree.capture(snapshot,reader)
      case Value.Failed(error) => throw error
    }
  test("Row and Column Center/End/Stretch use their allocated cross extent") {
    for horizontal <- Vector(false,true); alignment <- Vector(Alignment.Start,Alignment.Center,Alignment.End,Alignment.Stretch) do
      val root=node("root",Content.Stack(horizontal,0,alignment,Vector(node("child",Content.Space(20)))))
      val result=layout(root)
      val child=result.root.children.head
      val extent=if horizontal then child.bounds.height else child.bounds.width
      val expectedExtent=if alignment==Alignment.Stretch then 100.0 else 20.0
      assertEquals(extent,expectedExtent)
      val expectedOffset=alignment match
        case Alignment.Center => (100-extent)/2
        case Alignment.End => 100-extent
        case _ => 0.0
      assertEquals(if horizontal then child.bounds.y else child.bounds.x,expectedOffset)
      assertEquals(child.clip.width,child.bounds.width)
      assertEquals(child.clip.height,child.bounds.height)
  }
  test("nested naturally sized aligned stacks retain visible children") {
    val nested=node("row",Content.Stack(true,0,Alignment.Center,Vector(sized("small",20,10),sized("large",20,40))))
    val result=layout(node("column",Content.Stack(false,0,Alignment.Start,Vector(nested))))
    val row=result.root.children.head
    assertEquals(row.bounds.height,40.0)
    assertEquals(row.children.head.bounds.y,15.0)
    assertEquals(row.children.head.clip.height,10.0)
  }
  property("fixed and growing children agree with independent space-allocation arithmetic") {
    Prop.forAll(Gen.choose(1,100),Gen.choose(0,50),Gen.choose(1,5)) { (fixed,gap,count) =>
      val children=Vector(sized("fixed",fixed,10)) ++ Vector.tabulate(count)(i => node(s"grow$i",Content.Styled(Hint.Grow,node(s"space$i",Content.Space(0)))))
      val width=500.0
      val result=layout(node("row",Content.Stack(true,gap,Alignment.Start,children)),width,100)
      val share=math.max(0.0,(width-fixed-gap*count)/count)
      assertEqualsDouble(result.root.children(1).bounds.x,fixed+gap,1e-9)
      result.root.children.drop(1).foreach(c => assertEqualsDouble(c.bounds.width,share,1e-9))
      true
    }
  }
  test("generation gate rejects stale, restored and retired results; unchanged empty feedback remains presentable") {
    val coordinator=new FrameCoordinator
    val payload=FramePayload(node("empty",Content.Empty),100,80,200,160)
    val first=coordinator.request(payload).get
    assert(coordinator.accepts(first))
    assertEquals(coordinator.request(payload),None)
    // Zero-height feedback changes tracking, not the captured empty tree.
    assert(coordinator.accepts(first))
    val changed=coordinator.request(payload.copy(logicalWidth=110)).get
    assert(!coordinator.accepts(first));assert(coordinator.accepts(changed))
    val restored=coordinator.request(payload).get
    assert(!coordinator.accepts(first));assert(coordinator.accepts(restored))
    coordinator.retire();assert(!coordinator.accepts(restored))
  }
  test("committed capture follows live text and visibility without retaining Signal values") {
    val r=new HeadlessRuntime
    try
      val owner=r.scope("tree");val title=owner.cell("before");val shown=owner.cell(true)
      val definition=Component[Unit]("content")((_,_) => Text(title).style(TextStyle.Heading).visible(shown))
      val m=r.mount(owner,definition(()))
      def text(node: Node): TextRequest = node.content match
        case Content.Custom(frame) if frame.name=="text" => frame.textRequests(Size(Lp(80),Lp(20))).head
        case other => fail(s"expected a text element: $other")
      assertEquals(text(captured(r,m)),TextRequest("before",TextStyle.Heading,Lp(80)))
      ok(r.transact { title.set("after") })
      assertEquals(text(captured(r,m)),TextRequest("after",TextStyle.Heading,Lp(80)))
      ok(r.transact { shown.set(false) })
      assertEquals(captured(r,m).content,Content.Empty)
    finally r.close()
  }
