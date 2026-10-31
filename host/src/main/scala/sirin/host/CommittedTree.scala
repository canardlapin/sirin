package sirin.host

import sirin.*
import sirin.engine.*

/** Host identities contain immutable structural keys, never backend handles. */
final case class NodeId(path: Vector[Any]):
  def child(key: Any): NodeId = NodeId(path :+ key)

enum Hint:
  case Padding(x: Double, y: Double)
  case Width(value: Double)
  case Height(value: Double)
  case Grow

  /** Presented translation of drawing, hit and semantic geometry; layout is unaffected. */
  case Offset(x: Double, y: Double)

  /** Isolated group opacity, paint only. */
  case Opacity(alpha: Double)

enum Content:
  case Empty
  case Custom(frame: ElementFrame)
  case CustomLayout(frame: LayoutFrame, children: Vector[(Slot, Node)])
  case Button(instance: Long, label: String, enabled: Boolean, busy: Boolean, primary: Boolean)
  case Reference(reference: Long, child: Node)
  case Label(text: String, style: TextStyle, color: ColorRole)
  case Space(size: Double)
  case Stack(horizontal: Boolean, gap: Double, alignment: Alignment, children: Vector[Node])
  case Styled(hint: Hint, child: Node)
  case Scroll(instance: Long, child: Node)
  case Virtual(instance: Long, first: Int, total: Int, rowHeight: Double, children: Vector[Node])
final case class Node(id: NodeId, content: Content)

final case class Activation(enabled: Boolean, run: UiTxn => Unit)
final case class CapturedTree(
    tree: Node,
    bindings: Map[Long, Activation],
    keys: Vector[KeyBinding] = Vector.empty,
    elements: Map[Long, ElementBinding] = Map.empty
)

/** One budget spans every window and its structure, rather than resetting per peer. */
private[host] final class CaptureBudget(maxNodes: Int, maxTextUnits: Int):
  require(maxNodes > 0 && maxTextUnits >= 0, "invalid host capture budget")
  private var nodes = 0L
  private var textUnits = 0L
  def visit(depth: Int): Unit =
    nodes += 1
    require(nodes <= maxNodes && depth <= 128, "host tree budget exceeded")
  def text(length: Int): Unit =
    textUnits += length
    require(textUnits <= maxTextUnits, "host text budget exceeded")

/** Capture reads settled inputs; worker nodes carry phase facades, never UI bindings or native pointers. */
object CommittedTree:
  def capture(snapshot: MountedSnapshot, reader: CommittedRead, maxNodes: Int = 10000): Node =
    captureWithBindings(snapshot, reader, maxNodes).tree
  def captureWithBindings(snapshot: MountedSnapshot, reader: CommittedRead, maxNodes: Int = 10000): CapturedTree =
    captureWithBudget(snapshot, reader, new CaptureBudget(maxNodes, 100000), 0)
  private[host] def captureWithBudget(
      snapshot: MountedSnapshot,
      reader: CommittedRead,
      budget: CaptureBudget,
      initialDepth: Int
  ): CapturedTree =
    val bindings = scala.collection.mutable.Map.empty[Long, Activation]
    val keys = scala.collection.mutable.ArrayBuffer.empty[KeyBinding]
    val elements = scala.collection.mutable.Map.empty[Long, ElementBinding]
    def value[A](signal: Signal[A]): A =
      reader(signal) match
        case Value.Ok(v) =>
          v
        case Value.Failed(e) =>
          throw e
    def loop(s: MountedSnapshot, id: NodeId, depth: Int): Node =
      budget.visit(depth)
      def recurse(child: MountedSnapshot, key: Any): Node = loop(child, id.child(key), depth + 1)
      s match
        case MountedSnapshot.Referenced(reference, child) =>
          Node(id, Content.Reference(reference, recurse(child, "referenced")))
        case MountedSnapshot.Custom(frame, binding) =>
          require(!elements.contains(frame.instance), "duplicate mounted element")
          elements(frame.instance) = binding
          frame
            .activation
            .foreach { action =>
              bindings(frame.instance) = Activation(
                action.enabled,
                txn => {
                  binding.action(action.part, SemanticAction.Activate)(using txn)
                  ()
                }
              )
            }
          Node(NodeId(Vector("element", frame.instance)), Content.Custom(frame.presented))
        case MountedSnapshot.CustomLayout(frame, children) =>
          Node(
            NodeId(Vector("layout", frame.instance)),
            Content.CustomLayout(frame.presented, children.map((slot, child) => slot -> recurse(child, slot.name)))
          )
        case MountedSnapshot.Control(instance, view) =>
          view match
            case b: sirin.Button.Description =>
              val enabled = value(b.binding.enabled)
              val label = value(b.label)
              budget.text(label.length)
              bindings(instance) = Activation(enabled, txn => b.binding.invoke()(using txn))
              Node(
                NodeId(Vector("control", instance)),
                Content.Button(instance, label, enabled, value(b.binding.running) > 0, b.isPrimary)
              )
            case _ =>
              throw new UnsupportedOperationException("unsupported mounted control")
        case MountedSnapshot.Window(_, _, _, _, _) =>
          throw new IllegalArgumentException("capture each window's content separately")
        case MountedSnapshot.Empty =>
          Node(id, Content.Empty)
        case MountedSnapshot.Leaf(view) =>
          view match
            case t: Text.Description =>
              val text = value(t.content)
              budget.text(text.length)
              Node(id, Content.Label(text, t.textStyle, t.colorRole))
            case View.Space(size) =>
              Node(id, Content.Space(size.fold(0.0)(_.toDouble)))
            case _ =>
              throw new UnsupportedOperationException("view is outside the bounded host nucleus")
        case MountedSnapshot.Styled(child, decorations) =>
          if decorations.exists {
              case Decoration.Visible(shown) =>
                !value(shown)
              case _ =>
                false
            }
          then
            Node(id, Content.Empty)
          else
            val captured =
              decorations.foldLeft(recurse(child, "content")) { (node, decoration) =>
                val hint =
                  decoration match
                    case Decoration.Padding(x, y) =>
                      Some(Hint.Padding(x.toDouble, y.toDouble))
                    case Decoration.Width(w) =>
                      Some(Hint.Width(w.toDouble))
                    case Decoration.Height(h) =>
                      Some(Hint.Height(h.toDouble))
                    case Decoration.Grow =>
                      Some(Hint.Grow)
                    case Decoration.Offset(presentation) =>
                      val at = presentation.current
                      val (x, y) = (at.x.toDouble, at.y.toDouble)
                      require(x.isFinite && y.isFinite, "invalid presented offset")
                      Option.when(x != 0 || y != 0)(Hint.Offset(x, y))
                    case Decoration.Opacity(presentation) =>
                      val alpha = presentation.current
                      require(!alpha.isNaN, "invalid presented opacity")
                      Option.when(alpha < 1)(Hint.Opacity(math.max(0, alpha)))
                    case Decoration.Visible(_) | _: Decoration.KeyHandler | _: Decoration.Shortcut =>
                      None
                    case other =>
                      throw new UnsupportedOperationException(s"host decoration is deferred: $other")
                hint.fold(node)(h => Node(id, Content.Styled(h, node)))
              }
            decorations.foreach {
              case Decoration.KeyHandler(chord, run) =>
                keys += KeyBinding(captured.id, chord, Activation(true, run))
              case shortcut @ Decoration.Shortcut(chord, _) =>
                val binding = shortcut.shortcutBinding.get
                val enabled =
                  reader(binding.enabled) match
                    case Value.Ok(value) =>
                      value
                    case _ =>
                      false
                keys += KeyBinding(captured.id, chord, Activation(enabled, txn => binding.invoke()(using txn)))
              case _ =>
                ()
            }
            require(keys.size <= 256, "window keyboard binding budget exceeded")
            captured
        case MountedSnapshot.Group(children, horizontal, gap, alignment) =>
          Node(
            id,
            Content.Stack(
              horizontal,
              gap.toDouble,
              alignment,
              children.zipWithIndex.map((child, index) => recurse(child, index))
            )
          )
        case MountedSnapshot.Component(identity, _, child) =>
          loop(child, NodeId(Vector("component", identity.instance)), depth + 1)
        case MountedSnapshot.Branch(_, instance, child) =>
          recurse(child, s"branch:$instance")
        case MountedSnapshot.Rows(rows) =>
          Node(id, Content.Stack(false, 0, Alignment.Stretch, rows.map((key, child) => recurse(child, key))))
        case MountedSnapshot.Outlet(ref, child) =>
          recurse(child, s"outlet:$ref")
        case MountedSnapshot.Reparented(_, instance, child) =>
          loop(child, NodeId(Vector("retained", instance)), depth + 1)
        case MountedSnapshot.Scroll(instance, child) =>
          Node(NodeId(Vector("scroll", instance)), Content.Scroll(instance, recurse(child, "content")))
        case MountedSnapshot.VirtualRows(instance, _, first, total, _, _, height, rows) =>
          val children =
            rows match
              case MountedSnapshot.Rows(entries) =>
                entries.map((key, child) => recurse(child, key))
              case MountedSnapshot.Unavailable(error) =>
                throw error
              case _ =>
                Vector.empty
          Node(id, Content.Virtual(instance, first, total, height.toDouble, children))
        case MountedSnapshot.Unavailable(error) =>
          throw error
    val tree = loop(snapshot, NodeId(Vector("root")), initialDepth)
    CapturedTree(tree, bindings.toMap, keys.toVector, elements.toMap)
