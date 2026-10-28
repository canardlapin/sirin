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

enum Content:
  case Empty
  case Button(instance: Long,label: String,enabled: Boolean,busy: Boolean,primary: Boolean)
  case Reference(reference: Long,child: Node)
  case Label(text: String, style: TextStyle, color: ColorRole)
  case Space(size: Double)
  case Stack(horizontal: Boolean, gap: Double, alignment: Alignment, children: Vector[Node])
  case Styled(hint: Hint, child: Node)
  case Scroll(instance: Long, child: Node)
  case Virtual(instance: Long, first: Int, total: Int, rowHeight: Double, children: Vector[Node])
final case class Node(id: NodeId, content: Content)

final case class Activation(enabled: Boolean,run: UiTxn => Unit)
final case class CapturedTree(tree: Node,bindings: Map[Long,Activation])

/** Capture reads only declared settled inputs; no Signal or native pointer survives. */
object CommittedTree:
  def capture(snapshot: MountedSnapshot,reader: CommittedRead,maxNodes: Int=10000): Node = captureWithBindings(snapshot,reader,maxNodes).tree
  def captureWithBindings(snapshot: MountedSnapshot,reader: CommittedRead,maxNodes: Int=10000): CapturedTree =
    val bindings=scala.collection.mutable.Map.empty[Long,Activation]
    var visited = 0
    var textUnits=0L
    def value[A](signal: Signal[A]): A = reader(signal) match
      case Value.Ok(v) => v
      case Value.Failed(e) => throw e
    def loop(s: MountedSnapshot, id: NodeId, depth: Int): Node =
      visited += 1
      require(visited <= maxNodes && depth <= 128, "host tree budget exceeded")
      def recurse(child: MountedSnapshot, key: Any): Node = loop(child, id.child(key), depth+1)
      s match
        case MountedSnapshot.Referenced(reference,child) =>
          Node(id,Content.Reference(reference,recurse(child,"referenced")))
        case MountedSnapshot.Control(instance,view) => view match
          case b: sirin.Button.Description =>
            val enabled=value(b.binding.enabled)
            val label=value(b.label);textUnits+=label.length;require(textUnits<=100000,"host text budget exceeded")
            bindings(instance)=Activation(enabled,txn => b.binding.invoke()(using txn))
            Node(NodeId(Vector("control",instance)),Content.Button(instance,label,enabled,value(b.binding.running)>0,b.isPrimary))
          case _ => throw new UnsupportedOperationException("unsupported mounted control")
        case MountedSnapshot.Empty => Node(id, Content.Empty)
        case MountedSnapshot.Leaf(view) => view match
          case t: Text.Description =>
            val text=value(t.content);textUnits+=text.length
            require(textUnits<=100000,"host text budget exceeded")
            Node(id, Content.Label(text, t.textStyle, t.colorRole))
          case View.Space(size) => Node(id, Content.Space(size.fold(0.0)(_.toDouble)))
          case _ => throw new UnsupportedOperationException("view is outside the bounded host nucleus")
        case MountedSnapshot.Styled(child, decorations) =>
          if decorations.exists {
            case Decoration.Visible(shown) => !value(shown)
            case _ => false
          } then Node(id,Content.Empty)
          else decorations.foldLeft(recurse(child,"content")) { (node, decoration) =>
            val hint = decoration match
              case Decoration.Padding(x,y) => Some(Hint.Padding(x.toDouble,y.toDouble))
              case Decoration.Width(w) => Some(Hint.Width(w.toDouble))
              case Decoration.Height(h) => Some(Hint.Height(h.toDouble))
              case Decoration.Grow => Some(Hint.Grow)
              case Decoration.Visible(_) => None
              case other => throw new UnsupportedOperationException(s"host decoration is deferred: $other")
            hint.fold(node)(h => Node(id,Content.Styled(h,node)))
          }
        case MountedSnapshot.Group(children,horizontal,gap,alignment) =>
          Node(id, Content.Stack(horizontal,gap.toDouble,alignment,children.zipWithIndex.map((child,index) => recurse(child,index))))
        case MountedSnapshot.Component(identity,_,child) => loop(child,NodeId(Vector("component",identity.instance)),depth+1)
        case MountedSnapshot.Branch(_,instance,child) => recurse(child,s"branch:$instance")
        case MountedSnapshot.Rows(rows) => Node(id, Content.Stack(false,0,Alignment.Stretch,rows.map((key,child) => recurse(child,key))))
        case MountedSnapshot.Outlet(ref,child) => recurse(child,s"outlet:$ref")
        case MountedSnapshot.Reparented(_,instance,child) => loop(child,NodeId(Vector("retained",instance)),depth+1)
        case MountedSnapshot.Scroll(instance,child) => Node(NodeId(Vector("scroll",instance)),Content.Scroll(instance,recurse(child,"content")))
        case MountedSnapshot.VirtualRows(instance,_,first,total,_,_,height,rows) =>
          val children = rows match
            case MountedSnapshot.Rows(entries) => entries.map((key,child) => recurse(child,key))
            case MountedSnapshot.Unavailable(error) => throw error
            case _ => Vector.empty
          Node(id,Content.Virtual(instance,first,total,height.toDouble,children))
        case MountedSnapshot.Unavailable(error) => throw error
    val tree=loop(snapshot,NodeId(Vector("root")),0)
    CapturedTree(tree,bindings.toMap)
