package sirin.desktop

import sirin.*
import sirin.host.*

/** A window-owned address book. Retired IDs are never reused; only current keys remain in memory. */
private[desktop] final class NativeSemanticTree:
  private var serial = 0L
  private var ids = Map.empty[(NodeId, PartId), Long]
  private[desktop] var nodes = Vector.empty[NativeSemanticNode]

  def capture(layout: LayoutResult, focused: Option[NodeId]): Vector[NativeSemanticNode] =
    var nextIds = Map.empty[(NodeId, PartId), Long]
    val result = Vector.newBuilder[NativeSemanticNode]
    def id(key: (NodeId, PartId)): Long =
      require(!nextIds.contains(key), "duplicate native semantic part")
      val value = ids.getOrElse(key, {
        serial = Math.addExact(serial, 1L)
        require(serial < (1L << 48), "native semantic identity budget")
        serial
      })
      nextIds += key -> value
      value
    def rect(b: Box): Rect = Rect(Point(b.x.lp, b.y.lp), Size(b.width.lp, b.height.lp))
    def visit(p: Placed, parent: Long, modal: Boolean): Unit =
      if p.clip.width > 0 && p.clip.height > 0 then
        val size = Size(p.bounds.width.lp, p.bounds.height.lp)
        val (parts, instance, focusable, enabled) = p.node.content match
          case Content.Custom(frame) =>
            (frame.semantics(size, layout.text), Some(frame.instance), frame.focusable,
              frame.activation.forall(_.enabled))
          case Content.Styled(Hint.Semantic(semantics), _) =>
            (Vector(SemanticNode(PartId(0), semantics, rect(Box(0, 0, size.width.toDouble, size.height.toDouble)),
              Set.empty)), None, false, true)
          case Content.Button(instance, label, enabled, _, _) =>
            (Vector(SemanticNode(PartId(0), Semantics(Role.Button, name = Some(label)),
              rect(Box(0, 0, size.width.toDouble, size.height.toDouble)), Set(SemanticAction.Activate))),
              Some(instance), true, enabled)
          case _ =>
            (Vector.empty, None, false, true)
        var childParent = parent
        parts.zipWithIndex.foreach { (part, index) =>
          val b = part.bounds
          val area = Box(p.bounds.x + b.origin.x.toDouble, p.bounds.y + b.origin.y.toDouble,
            b.size.width.toDouble, b.size.height.toDouble).intersect(p.clip)
          if area.width > 0 && area.height > 0 then
            val identifier = id(p.node.id -> part.part)
            val focus = focusable && index == 0
            val node = NativeSemanticNode(identifier, parent, part.copy(bounds = rect(area)),
              instance, Point(p.bounds.x.lp, p.bounds.y.lp), focus,
              focus && focused.contains(p.node.id), enabled, modal && part.semantics.role == Role.Dialog)
            result += node
            if parts.size == 1 && instance.isEmpty then childParent = identifier
        }
        p.children.foreach(visit(_, childParent, modal))
    val modalIndex = layout.layers.lastIndexWhere(_.frame.modal)
    if layout.layers.isEmpty then visit(layout.root, 0, false)
    else
      if modalIndex < 0 then visit(layout.root.children.head, 0, false)
      layout.layers.drop(math.max(0, modalIndex)).foreach(layer => visit(layer.placed, 0, layer.frame.modal))
    val next = result.result()
    require(next.size <= 1024, "native accessibility node budget")
    ids = nextIds
    nodes = next
    next

private[desktop] final case class NativeSemanticNode(
    id: Long,
    parent: Long,
    part: SemanticNode,
    instance: Option[Long],
    origin: Point,
    focusable: Boolean,
    focused: Boolean,
    enabled: Boolean,
    modal: Boolean
):
  def kind: Int = part.text.fold(part.semantics.role match
    case Role.Button => 2
    case Role.Checkbox => 3
    case Role.Dialog => 4
    case Role.Label => 5
    case Role.Group => 6
    case Role.Status => 7
    case Role.Alert => 8
    case Role.List => 9
    case Role.ListItem => 10
    case Role.Form => 11
    case Role.Menu => 12
    case Role.MenuItem => 13
    case Role.Toolbar => 14
    case Role.Canvas => 15
    case Role.GraphicNode => 16
    case Role.TextInput => 5
  )(text => if text.multiline then 1 else 0)
  def flags: Int =
    val s = part.semantics
    (if focusable then 1 else 0) |
      (if part.actions(SemanticAction.Activate) then 2 else 0) |
      (if enabled then 0 else 4) | (if focused then 8 else 0) | (if modal then 16 else 0) |
      (if s.invalid then 32 else 0) | (if s.busy then 64 else 0) |
      s.selected.fold(0)(value => 256 | (if value then 128 else 0)) |
      (s.live match
        case Live.Off => 0
        case Live.Polite => 512
        case Live.Assertive => 1024)
