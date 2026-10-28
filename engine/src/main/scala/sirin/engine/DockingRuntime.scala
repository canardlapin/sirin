package sirin.engine

import sirin.*
import scala.collection.mutable

final case class RefAttachment(reference: Long, node: Long, outlet: Boolean)
private[engine] final class RuntimeNodeRef(val runtime: HeadlessRuntime)(val owner: runtime.OwnedScope, val id: Long) extends NodeRef:
  val content: Cell[MountedSnapshot] = owner.cell[MountedSnapshot](MountedSnapshot.Empty)(using MountedSnapshot.equality)
  var registered = true
  def alive: Boolean = registered && runtime.scopeAvailable(owner)
  val focused=owner.cell(false)
  val containing=owner.cell(false)
  val ring=owner.cell(false)
  private def checked[A](body: => A): A = { if !registered then throw KernelError.DisposedScope;runtime.ensureScope(owner);body }
  def focus()(using txn: UiTxn): Unit = runtime.capability(txn)(checked(runtime.docking.requestFocus(this)))
  def hasFocus: Signal[Boolean] = checked(Inputs.from(focused))
  def containsFocus: Signal[Boolean] = checked(Inputs.from(containing))
  def part(id: PartId): SemanticTarget = checked(SemanticTarget(this, Some(id)))
  def focusedPart: Signal[Option[PartId]] = checked(Signal.const(None))
  def focusPart(id: PartId)(using txn: UiTxn): Unit = runtime.capability(txn)(checked(throw KernelError.Deferred("semantic part focus")))
  def focusRing: Signal[Boolean] = checked(Inputs.from(ring))

private[engine] final class RuntimePanel(val runtime: HeadlessRuntime)(val owner: runtime.OwnedScope,
    val key: GlobalKey, val content: MountedView) extends Reparentable:
  var registered = true
  def alive: Boolean = registered && runtime.scopeAvailable(owner) && runtime.scopeAvailable(content.owner.asInstanceOf[runtime.OwnedScope])
  def at(outlet: Signal[NodeRef]): View = RetainedPlacement(this, outlet)

/** Candidate claims are rebuilt per pass; only commit exposes attachments. */
private[engine] final class DockingRuntime(val runtime: HeadlessRuntime):
  private final case class Attachment(ref: RuntimeNodeRef, scope: runtime.OwnedScope, outlet: Boolean)
  private final case class Request(panel: RuntimePanel, scope: runtime.OwnedScope, target: NodeRef)
  private val refs = mutable.ArrayBuffer.empty[RuntimeNodeRef]
  private val panels = mutable.ArrayBuffer.empty[RuntimePanel]
  private val claims = mutable.ArrayBuffer.empty[Attachment]
  private val requests = mutable.ArrayBuffer.empty[Request]
  private var published = Vector.empty[RefAttachment]
  private var focusRequests=Vector.empty[Long]
  def requestFocus(ref: RuntimeNodeRef): Unit =
    val before=focusRequests;runtime.recordUndo(() => focusRequests=before)
    require(focusRequests.size<256,"focus intent capacity")
    focusRequests :+= ref.id
  def takeFocusRequests(): Vector[Long] =
    val attached=published.map(_.reference).toSet
    val result=focusRequests.filter(id => attached(id) && refs.exists(r => r.alive && r.id==id))
    focusRequests.filterNot(result.toSet).foreach(id => runtime.note(new IllegalStateException(s"unattached focus intent: $id")))
    focusRequests=Vector.empty;result
  def publishFocus(references: Set[Long],ancestors: Set[Long],keyboard: Boolean)(using UiTxn): Unit =
    val attached=published.map(_.reference).toSet
    refs.filter(_.alive).foreach { r =>
      val focused=references(r.id) && attached(r.id)
      r.focused.set(focused);r.containing.set((focused || ancestors(r.id)) && attached(r.id));r.ring.set(focused && keyboard)
    }
  def dependencies: Vector[Signal[?]] = panels.filter(_.alive).map(_.content.snapshot).toVector
  def attachments: Vector[RefAttachment] = published
  private[engine] def candidateCount: Int = claims.size + requests.size
  def createRef(owner: HeadlessRuntime#OwnedScope): NodeRef =
    val ref = new RuntimeNodeRef(runtime)(owner.asInstanceOf[runtime.OwnedScope], runtime.nextInvocationId())
    refs += ref
    runtime.recordUndo(() => { ref.registered = false; refs -= ref })
    ref
  def createPanel(owner: HeadlessRuntime#OwnedScope, key: GlobalKey, content: View): Reparentable =
    if panels.exists(p => p.alive && p.key == key) then throw KernelError.DuplicateGlobalKey
    val owned = owner.asInstanceOf[runtime.OwnedScope]
    val mounted = runtime.mount(owned, content)
    val panel = new RuntimePanel(runtime)(owned, key, mounted)
    panels += panel
    runtime.recordUndo(() => { panel.registered = false; panels -= panel })
    panel
  def reference(value: NodeRef): RuntimeNodeRef = value match
    case ref: RuntimeNodeRef if ref.runtime eq runtime =>
      if !ref.registered then throw KernelError.DisposedScope
      ref
    case _ => throw KernelError.ForeignNodeRef
  def panel(value: Reparentable): RuntimePanel = value match
    case panel: RuntimePanel if panel.runtime eq runtime =>
      if !panel.registered then throw KernelError.DisposedScope
      panel
    case _ => throw KernelError.ForeignNodeRef
  def beginPass(): Unit = { claims.clear(); requests.clear() }
  def attach(value: NodeRef, scope: HeadlessRuntime#OwnedScope, outlet: Boolean): Unit =
    val ref = reference(value)
    if ref.alive then claims += Attachment(ref, scope.asInstanceOf[runtime.OwnedScope], outlet)
  def place(value: Reparentable, scope: HeadlessRuntime#OwnedScope, target: NodeRef): Unit =
    val retained = panel(value)
    reference(target)
    if retained.alive then requests += Request(retained, scope.asInstanceOf[runtime.OwnedScope], target)
  private def under(scope: HeadlessRuntime#OwnedScope, ancestor: HeadlessRuntime#OwnedScope): Boolean =
    (scope eq ancestor) || scope.parent.exists(under(_, ancestor))
  private def depth(scope: HeadlessRuntime#OwnedScope): Int = scope.parent.fold(0)(p => depth(p) + 1)
  def prepare(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
    val liveClaims = claims.filter(c => c.ref.alive && runtime.scopeAvailable(c.scope)).toVector
    val grouped = liveClaims.groupBy(_.ref)
    if grouped.values.exists(_.map(_.scope.id).distinct.size > 1) then throw KernelError.DuplicateReference
    val outlets = grouped.collect { case (ref, entries) if entries.exists(_.outlet) => ref -> entries.head.scope }
    val liveRequests = requests.filter(r => r.panel.alive && runtime.scopeAvailable(r.scope)).toVector
    if liveRequests.groupBy(_.panel).values.exists(_.size > 1) then throw KernelError.DuplicatePlacement
    val assigned = liveRequests.flatMap { request =>
      val ref = reference(request.target)
      Option.when(ref.alive && outlets.contains(ref))(ref -> request.panel)
    }
    if assigned.groupBy(_._1).values.exists(_.size > 1) then throw KernelError.DuplicatePlacement
    val livePanels = panels.filter(_.alive).toVector
    val edges = assigned.flatMap { (ref, panel) =>
      livePanels.filter(p => under(outlets(ref), p.content.owner)).sortBy(p => -depth(p.content.owner)).headOption.map(panel -> _)
    }.toMap
    def visit(panel: RuntimePanel, path: Set[RuntimePanel]): Unit =
      if path(panel) then throw KernelError.CycleDetected
      edges.get(panel).foreach(visit(_, path + panel))
    edges.keys.foreach(visit(_, Set.empty))
    val placements = assigned.toMap
    var changed = false
    refs.filter(_.alive).foreach { ref =>
      val desired = placements.get(ref) match
        case None => Some(MountedSnapshot.Empty)
        case Some(panel) => read(panel.content.snapshot) match
          case Some(Value.Ok(value)) => Some(MountedSnapshot.Reparented(panel.key, panel.content.owner.id, value.asInstanceOf[MountedSnapshot]))
          case Some(Value.Failed(error)) => Some(MountedSnapshot.Unavailable(error))
          case None => None
      desired match
        case None => changed = true
        case Some(next) =>
          if !read(ref.content).exists {
            case Value.Ok(previous) => runtime.lawful(MountedSnapshot.equality.same(previous.asInstanceOf[MountedSnapshot], next))
            case _ => false
          } then { ref.content.set(next); changed = true }
    }
    refs.filter(_.alive).filterNot(grouped.contains).foreach { ref =>
      if ref.focused.get || ref.containing.get || ref.ring.get then
        ref.focused.set(false);ref.containing.set(false);ref.ring.set(false);changed=true
    }
    changed
  def commit(): Unit =
    published = claims.filter(c => c.ref.alive && runtime.scopeAvailable(c.scope)).map(c => RefAttachment(c.ref.id, c.scope.id, c.outlet)).distinct.toVector
    refs.filterInPlace(_.alive)
    panels.filterInPlace(_.alive)
    claims.clear(); requests.clear()
  def clear(): Unit = { focusRequests=Vector.empty; published = Vector.empty; refs.clear(); panels.clear(); claims.clear(); requests.clear() }
