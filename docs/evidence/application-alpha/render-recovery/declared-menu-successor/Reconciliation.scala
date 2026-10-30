package sirin.engine

import sirin.*

enum MountedSnapshot:
  case Empty
  case Referenced(reference: Long,child: MountedSnapshot)
  case Control(instance: Long,view: View)
  case Styled(child: MountedSnapshot, decorations: Vector[Decoration])
  case Scroll(instance: Long, child: MountedSnapshot)
  case Window(instance: Long, title: Value[String], menu: MenuBar, child: MountedSnapshot,renderStatus: WindowRenderStatus=WindowRenderStatus.empty)
  case Outlet(reference: Long, child: MountedSnapshot)
  case Reparented(key: GlobalKey, instance: Long, child: MountedSnapshot)
  case VirtualRows(instance: Long, revision: Revision, first: Int, total: Int, leading: Lp, trailing: Lp, rowHeight: Lp, rows: MountedSnapshot)
  /** Opaque leaf description; no painting or input routing is performed. */
  case Leaf(view: View)
  case Group(children: Vector[MountedSnapshot], horizontal: Boolean = false, gap: Lp = Lp.zero, alignment: Alignment = Alignment.Start)
  case Component(identity: MountedIdentity, props: Any, child: MountedSnapshot)
  case Rows(entries: Vector[(Any, MountedSnapshot)])
  case Branch(key: Any, instance: Long, child: MountedSnapshot)
  case Unavailable(error: Throwable)

object MountedSnapshot:
  private[engine] val equality: Equality[MountedSnapshot] = (a, b) => (a, b) match
    case (Empty, Empty) => true
    case (Referenced(i,x),Referenced(j,y)) => i==j && equality.same(x,y)
    case (Control(i,x),Control(j,y)) => i==j && x==y
    case (Styled(x, a), Styled(y, b)) => a == b && equality.same(x, y)
    case (Window(i, t, m, x, r), Window(j, u, n, y, s)) => i == j && t == u && m == n && r==s && equality.same(x, y)
    case (Scroll(i, x), Scroll(j, y)) => i == j && equality.same(x, y)
    case (Outlet(i, x), Outlet(j, y)) => i == j && equality.same(x, y)
    case (Reparented(k, i, x), Reparented(l, j, y)) => k == l && i == j && equality.same(x, y)
    case (VirtualRows(i, r, f, t, l, e, a, x), VirtualRows(j, s, g, u, m, h, b, y)) =>
      i == j && r == s && f == g && t == u && l == m && e == h && a == b && equality.same(x, y)
    case (Leaf(x), Leaf(y)) => x == y
    case (Group(x, h, g, a), Group(y, i, j, b)) => h == i && g == j && a == b && x.size == y.size && x.zip(y).forall((p, q) => equality.same(p, q))
    case (Component(i, x, c), Component(j, y, d)) =>
      i == j && i.definition.asInstanceOf[sirin.Component.Token[Any]].equality.same(x, y) && equality.same(c, d)
    case (Rows(x), Rows(y)) => x.size == y.size && x.zip(y).forall((p, q) => p._1 == q._1 && equality.same(p._2, q._2))
    case (Branch(k, i, x), Branch(l, j, y)) => i == j && k == l && equality.same(x, y)
    case (Unavailable(x), Unavailable(y)) => x eq y
    case _ => false

final case class MountedIdentity(owner: Long, key: Any, definition: AnyRef, instance: Long)

/** A headless structural root. All public mutations join a UiTxn. */
final class MountedView private[engine](val runtime: HeadlessRuntime)(val owner: runtime.OwnedScope, initial: View,applicationQuit: Option[UiTxn => CloseDecision]):
  private var desired = initial
  private val output = owner.cell[MountedSnapshot](MountedSnapshot.Empty)(using MountedSnapshot.equality)
  val snapshot: Signal[MountedSnapshot] = Inputs.from(output)
  private val holder = new Holder(owner, 0, Some(_ => desired))
  def update(view: View)(using txn: UiTxn): Unit = runtime.capability(txn) {
    if !runtime.scopeAvailable(owner) then throw KernelError.DisposedScope
    val before = desired
    runtime.recordUndo(() => desired = before)
    desired = view
    runtime.invalidateReads()
  }
  private var defaultViewport = Option.empty[Cell[RowViewport]]
  private var virtuals = Vector.empty[VirtualSlot]
  private var windows = Vector.empty[WindowSlot]

  def requestApplicationQuit()(using txn: UiTxn): CloseDecision = runtime.capability(txn) {
    runtime.ensureScope(owner)
    applicationQuit.fold(CloseDecision.Allow)(_(txn))
  }
  /** A render outcome targets a live mounted instance, never a reusable key. */
  def reportWindowRenderFailure(instance: Long,failure: WindowRenderFailure)(using txn: UiTxn): Unit = runtime.capability(txn) {
    runtime.ensureScope(owner)
    windows.find(window => window.scope.id==instance && runtime.scopeAvailable(window.scope)) match
      case Some(window) => window.reportRenderFailure(failure)
      case None => UiTxn.refuse("window is no longer mounted")
  }
  /** A close fact targets this mounted instance, never a reusable source key. */
  def requestWindowClose(instance: Long)(using txn: UiTxn): Unit = runtime.capability(txn) {
    runtime.ensureScope(owner)
    windows.find(window => window.scope.id == instance && runtime.scopeAvailable(window.scope)) match
      case Some(window) => window.requestClose()
      case None => UiTxn.refuse("window is no longer mounted")
  }
  private def hostViewport: Cell[RowViewport] = defaultViewport.getOrElse {
    val cell = owner.cell(RowViewport.empty)
    change(defaultViewport)(defaultViewport = _)(Some(cell))
    cell
  }
  /** Headless convenience: the default for virtual nodes without an override. */
  def setViewport(viewport: RowViewport)(using txn: UiTxn): Unit = runtime.capability(txn) {
    runtime.ensureScope(owner)
    hostViewport.set(viewport)
  }
  /** Production hosts supply each virtual node's viewport by its mounted ID. */
  def setViewport(instance: Long, viewport: RowViewport)(using txn: UiTxn): Unit = runtime.capability(txn) {
    runtime.ensureScope(owner)
    virtuals.find(v => v.scope.id == instance && runtime.scopeAvailable(v.scope)) match
      case Some(slot) => slot.viewport.set(Some(viewport))
      case None => UiTxn.refuse("virtual node is no longer mounted")
  }
  def dispose()(using UiTxn): Unit = owner.dispose()
  def isDisposed: Boolean = owner.isDisposed

  private def plain(view: View): View = view match
    case View.Decorated(inner, _) => plain(inner)
    case View.StackDecoration(inner, _) => plain(inner)
    case View.TextDecoration(inner, _) => plain(inner)
    case View.ButtonDecoration(inner, _) => plain(inner)
    case View.FieldDecoration(inner, _) => plain(inner)
    case other => other
  private def keyOf(view: View, default: Any): Any = view match
    case View.Decorated(_, Decoration.Keyed(key)) => key
    case View.Decorated(inner, _) => keyOf(inner, default)
    case View.FieldDecoration(_, Decoration.Keyed(key)) => key
    case View.FieldDecoration(inner, _) => keyOf(inner, default)
    case View.ButtonDecoration(_, Decoration.Keyed(key)) => key
    case View.ButtonDecoration(inner, _) => keyOf(inner, default)
    case View.TextDecoration(_, Decoration.Keyed(key)) => key
    case View.TextDecoration(inner, _) => keyOf(inner, default)
    case View.StackDecoration(_, Decoration.Keyed(key)) => key
    case View.StackDecoration(inner, _) => keyOf(inner, default)
    case _ => default
  private def styles(view: View): Vector[Decoration] = view match
    case View.Decorated(inner, decoration) => styles(inner) :+ decoration
    case View.StackDecoration(inner, decoration) => styles(inner) :+ decoration
    case View.TextDecoration(inner, decoration) => styles(inner) :+ decoration
    case View.ButtonDecoration(inner, decoration) => styles(inner) :+ decoration
    case View.FieldDecoration(inner, decoration) => styles(inner) :+ decoration
    case _ => Vector.empty
  private def directDependencies(view: View): Vector[Signal[?]] =
    styles(view).flatMap {
      case Decoration.Visible(signal) => Vector(signal)
      case shortcut: Decoration.Shortcut => shortcut.shortcutBinding.toVector.flatMap(b => Vector(b.enabled,b.running))
      case _ => Vector.empty
    } ++ leafDependencies(view)
  private def leafDependencies(view: View): Vector[Signal[?]] = plain(view) match
    case p: Button.Description => Vector(p.label,p.binding.enabled,p.binding.running)
    case p: Text.Description => Vector(p.content)
    case p: TextField.Description => Vector(p.value,p.problem)
    case p: sirin.Window.Description => Vector(p.title) ++ MenuBindings.dependencies(p.menu) ++ directDependencies(p.content)
    case p: Windows.MenuDescription => MenuBindings.dependencies(p.menu)
    case p: View.ScrollContent => directDependencies(p.content)
    case p: Component.Placement[?] => Vector(p.props)
    case p: ForEach.Description[?, ?] => Vector(p.items)
    case p: Switch.Plain[?] => Vector(p.value)
    case p: Switch.Keyed[?, ?] => Vector(p.value)
    case p: View.Stack => p.children.flatMap(directDependencies)
    case p: RetainedPlacement => Vector(p.outlet)
    case p: DockOutlet.Description =>
      val ref = runtime.docking.reference(p.ref)
      if ref.alive then Vector(ref.content) else Vector.empty
    case p: VirtualRows.Description[?, ?] => Vector(p.source.revision)
    case _ => Vector.empty
  private[engine] def dependencies: Vector[Signal[?]] = directDependencies(desired) ++ holder.dependencies
  private[engine] def reconcile(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
    var changed = holder.sync(desired, read)
    val kept = virtuals.filter(v => runtime.scopeAvailable(v.scope))
    if kept.size != virtuals.size then change(virtuals)(virtuals = _)(kept)
    val liveWindows = windows.filter(window => runtime.scopeAvailable(window.scope))
    if liveWindows.size != windows.size then change(windows)(windows = _)(liveWindows)
    val next = holder.snapshot
    val current = read(output)
    if current.forall(v => !runtime.lawful(v match
      case Value.Ok(old) => MountedSnapshot.equality.same(old.asInstanceOf[MountedSnapshot], next)
      case _ => false)) then
      output.set(next)
      changed = true
    changed

  private def references(view: View): Vector[NodeRef] = view match
    case View.Decorated(inner, Decoration.Ref(ref)) => references(inner) :+ ref
    case View.Decorated(inner, _) => references(inner)
    case View.FieldDecoration(inner, Decoration.Ref(ref)) => references(inner) :+ ref
    case View.FieldDecoration(inner, _) => references(inner)
    case View.ButtonDecoration(inner, Decoration.Ref(ref)) => references(inner) :+ ref
    case View.ButtonDecoration(inner, _) => references(inner)
    case View.TextDecoration(inner, Decoration.Ref(ref)) => references(inner) :+ ref
    case View.TextDecoration(inner, _) => references(inner)
    case View.StackDecoration(inner, Decoration.Ref(ref)) => references(inner) :+ ref
    case View.StackDecoration(inner, _) => references(inner)
    case _ => Vector.empty

  private def sameKey(a: Any, b: Any): Boolean = runtime.lawful(a == b)
  private def sameDeps(a: Option[AnyRef], b: Option[AnyRef]): Boolean = (a, b) match
    case (None, None) => true
    case (Some(x), Some(y)) => x eq y
    case _ => false
  private def duplicateKeys(keys: Vector[Any]): Unit = runtime.lawful {
    if keys.distinct.size != keys.size then throw KernelError.DuplicateKey
  }
  private def change[A](old: A)(restore: A => Unit)(next: A): Unit =
    runtime.recordUndo(() => restore(old))
    restore(next)

  private final class Holder(val parent: runtime.OwnedScope, val position: Any, provider: Option[Signal.Reader => View] = None):
    private var slot: Option[Slot] = None
    private var key: Any = position
    private var lastView: View = View.empty
    private def requested(reader: Signal.Reader): View =
      val view = provider.fold(lastView)(_(reader))
      if slot.exists(s => !sameKey(keyOf(view, position), key) || !s.accepts(plain(view))) then throw KernelError.DisposedScope
      view
    def dependencies: Vector[Signal[?]] = directDependencies(lastView) ++ slot.toVector.flatMap(_.dependencies)
    def snapshot: MountedSnapshot =
      val value = slot.fold[MountedSnapshot](MountedSnapshot.Empty)(_.snapshot)
      val decorations = styles(lastView).filter {
        case Decoration.Keyed(_) | Decoration.Ref(_) => false
        case _ => true
      }
      val styled=if decorations.isEmpty then value else MountedSnapshot.Styled(value, decorations)
      references(lastView).foldLeft(styled)((child,ref) => MountedSnapshot.Referenced(runtime.docking.reference(ref).id,child))
    def retire()(using UiTxn): Unit = slot.foreach(_.scope.dispose())
    def sync(view: View, read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
      if !lastView.eq(view) then change(lastView)(v => lastView = v)(view)
      val nextKey = keyOf(view, position)
      val next = plain(view)
      var changed = false
      if slot.forall(s => !sameKey(key, nextKey) || !s.accepts(next)) then
        retire()
        change(key)(v => key = v)(nextKey)
        val created: Slot = next match
          case p: Component.Placement[?] => new ComponentSlot(parent, nextKey, p.asInstanceOf[Component.Placement[Any]], reader => requested(reader))
          case p: ForEach.Description[?, ?] => new RowsSlot(parent, p.asInstanceOf[ForEach.Description[Any, Any]], reader => requested(reader))
          case p: Switch.Plain[?] => new SwitchSlot(parent, p.asInstanceOf[Switch.Plain[Any]], None, reader => requested(reader))
          case p: Switch.Keyed[?, ?] => new SwitchSlot(parent, null, Some(p.asInstanceOf[Switch.Keyed[Any, Any]]), reader => requested(reader))
          case p: sirin.Window.Description => new WindowSlot(parent, p)
          case p: View.ScrollContent => new ScrollSlot(parent, p)
          case p: View.Stack => new StackSlot(parent, p, reader => requested(reader))
          case p: DockOutlet.Description => new OutletSlot(parent, p)
          case p: RetainedPlacement => new PortalSlot(parent, p)
          case p: VirtualRows.Description[?, ?] => new VirtualSlot(parent, p.asInstanceOf[VirtualRows.Description[Any, Any]], reader => requested(reader))
          case other => new LeafSlot(parent, other)
        change(slot)(v => slot = v)(Some(created))
        changed = true
      else if slot.get.refresh(next) then changed = true
      if slot.get.step(read) then changed = true
      references(view).foreach(runtime.docking.attach(_, slot.get.scope, false))
      plain(view) match
        case outlet: DockOutlet.Description => runtime.docking.attach(outlet.ref, slot.get.scope, true)
        case _ => ()
      changed

  private trait Slot:
    val scope: runtime.OwnedScope
    def accepts(view: View): Boolean
    def refresh(view: View): Boolean
    def dependencies: Vector[Signal[?]]
    def snapshot: MountedSnapshot
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean

  private final class LeafSlot(parent: runtime.OwnedScope, initial: View) extends Slot:
    val scope = parent.child("leaf")
    private var view = initial
    def accepts(next: View): Boolean =
      if view.isInstanceOf[Button.Description] != next.isInstanceOf[Button.Description] ||
          view.isInstanceOf[TextField.Description] != next.isInstanceOf[TextField.Description] then false
      else if ((view,next) match
        case (a: TextField.Description,b: TextField.Description) => !(a.value eq b.value)
        case _ => false) then false
      else next match
        case _: sirin.Window.Description | _: View.ScrollContent => false
        case _: Component.Placement[?] | _: ForEach.Description[?, ?] | _: Switch.Plain[?] | _: Switch.Keyed[?, ?] | _: View.Stack | _: DockOutlet.Description | _: RetainedPlacement | _: VirtualRows.Description[?, ?] => false
        case _ => true
    def refresh(next: View): Boolean =
      if view.eq(next) then false
      else { change(view)(v => view = v)(next); true }
    def dependencies: Vector[Signal[?]] = Vector.empty
    def snapshot: MountedSnapshot = view match
      case _: Button.Description | _: TextField.Description => MountedSnapshot.Control(scope.id,view)
      case _ => if view eq View.Empty then MountedSnapshot.Empty else MountedSnapshot.Leaf(view)
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean = false

  private final class WindowSlot(parent: runtime.OwnedScope, initial: sirin.Window.Description) extends Slot:
    val scope = parent.child("window-content")
    private var description = initial
    private var title: Value[String] = Value.Ok("")
    private val child = new Holder(scope, 0)
    private val recovery = new Holder(scope,"render-recovery")
    private val status=scope.cell(WindowRenderStatus.empty)
    private val closeKeptOpen=scope.cell(false)
    private val inputReviewed=scope.cell(false)
    private val retryRef=scope.ref()
    private var settledStatus=WindowRenderStatus.empty
    private val recoveryContent=scope.cell[View](initial.recoveryContent)
    private val retry=scope.action[Unit]("window.render.retry","Retry rendering") { _ =>
      status.set(status.get.copy(current=None));closeKeptOpen.set(false);inputReviewed.set(true)
    }
    private val closeView=scope.action[Unit]("window.render.close","Acknowledge input and close") { _ =>
      inputReviewed.set(true);requestClose()
    }
    private val retryEntry=MenuItem(retry)
    private val closeEntry=MenuItem(closeView)
    private val recoveryMenu=MenuBar(Menu("View",retryEntry,closeEntry))
    private val recoveryView: View=Scroll(Column(
      Text("This view could not render.").style(TextStyle.Heading),
      Text("Your document state is retained."),
      Text(status.map(_.current.fold("")(failure => s"Failure ${failure.generation}. ${failure.unappliedInputs} queued inputs were not applied."))),
      Text(status.map(_.current.filter(_.cancelledComposition).fold("")(_ => "In-progress text composition was cancelled."))),
      Text(closeKeptOpen.map(kept => if kept then "The view stayed open. Review pending decisions and unapplied input before closing." else "")),
      Row(Button(retry).ref(retryRef),Button(closeView)).spacing(8.lp),
      Switch(recoveryContent)(identity)
    ).spacing(10.lp).padding(16.lp))
    change(windows)(windows = _)(windows :+ this)
    def accepts(view: View): Boolean = view.isInstanceOf[sirin.Window.Description]
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else { change(description)(description = _)(view.asInstanceOf[sirin.Window.Description]); true }
    def dependencies: Vector[Signal[?]] = Vector(description.title,status,closeKeptOpen) ++ MenuBindings.dependencies(description.menu) ++ MenuBindings.dependencies(recoveryMenu) ++ child.dependencies ++ recovery.dependencies
    def snapshot: MountedSnapshot =
      val menu=if settledStatus.current.isEmpty then description.menu
        else
          val existing=description.menu.menus
          val extended=if existing.exists(_.title=="View") then existing.map { m =>
            if m.title=="View" then Menu(m.title,(m.entries++Seq(retryEntry,closeEntry))*) else m
          } else existing:+Menu("View",retryEntry,closeEntry)
          MenuBar(extended*)
      MountedSnapshot.Window(scope.id,title,menu,if settledStatus.current.nonEmpty then recovery.snapshot else child.snapshot,settledStatus)
    def reportRenderFailure(failure: WindowRenderFailure)(using UiTxn): Unit =
      UiTxn.require(status.get.recent.lastOption.forall(_.generation<failure.generation),"render failure generation is stale")
      status.set(WindowRenderStatus(Some(failure),(status.get.recent:+failure).takeRight(8)))
      closeKeptOpen.set(false);inputReviewed.set(false);retryRef.focus()
    def requestClose()(using txn: UiTxn): Unit =
      val unreviewed=status.get.current.exists(f => f.unappliedInputs>0 || f.cancelledComposition) && !inputReviewed.get
      if unreviewed then closeKeptOpen.set(true)
      else
        val dismiss = description.prepareDismiss match
          case Some(prepare) => prepare(txn)
          case None => UiTxn.refuse("window is not owned by Windows")
        val decision = description.close.fold(CloseDecision.Allow)(_(txn))
        if runtime.scopeAvailable(scope) then
          if decision == CloseDecision.Allow then dismiss(txn)
          else if status.get.current.nonEmpty then closeKeptOpen.set(true)
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
      var changed = child.sync(description.content, read)
      read(status) match
        case Some(Value.Ok(value: WindowRenderStatus)) =>
          if settledStatus!=value then {change(settledStatus)(settledStatus = _)(value);changed=true}
        case Some(Value.Failed(error)) => throw error
        case _ => changed=true
      val content=if settledStatus.current.exists(_.recoveryFailed) then View.empty else description.recoveryContent
      if !(recoveryContent.get eq content) then {recoveryContent.set(content);changed=true}
      if recovery.sync(recoveryView,read) then changed=true
      read(description.title) match
        case None => changed = true
        case Some(value) =>
          val next = value.asInstanceOf[Value[String]]
          if title != next then
            change(title)(title = _)(next)
            changed = true
      changed

  private final class ScrollSlot(parent: runtime.OwnedScope, initial: View.ScrollContent) extends Slot:
    val scope = parent.child("scroll")
    private var description = initial
    private val child = new Holder(scope, 0)
    def accepts(view: View): Boolean = view.isInstanceOf[View.ScrollContent]
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else { change(description)(description = _)(view.asInstanceOf[View.ScrollContent]); true }
    def dependencies: Vector[Signal[?]] = directDependencies(description.content) ++ child.dependencies
    def snapshot: MountedSnapshot = MountedSnapshot.Scroll(scope.id, child.snapshot)
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean = child.sync(description.content, read)

  private final class OutletSlot(parent: runtime.OwnedScope, initial: DockOutlet.Description) extends Slot:
    val scope = parent.child("outlet")
    private var description = initial
    private var content: MountedSnapshot = MountedSnapshot.Empty
    def accepts(view: View): Boolean = view.isInstanceOf[DockOutlet.Description]
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else { change(description)(description = _)(view.asInstanceOf[DockOutlet.Description]); true }
    def dependencies: Vector[Signal[?]] =
      val ref = runtime.docking.reference(description.ref)
      if ref.alive then Vector(ref.content) else Vector.empty
    def snapshot: MountedSnapshot =
      val ref = runtime.docking.reference(description.ref)
      if ref.alive then MountedSnapshot.Outlet(ref.id, content) else MountedSnapshot.Empty
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
      val ref = runtime.docking.reference(description.ref)
      val next = if !ref.alive then MountedSnapshot.Empty else read(ref.content) match
        case Some(Value.Ok(value)) => value.asInstanceOf[MountedSnapshot]
        case Some(Value.Failed(error)) => MountedSnapshot.Unavailable(error)
        case None => return true
      if runtime.lawful(MountedSnapshot.equality.same(content, next)) then false
      else { change(content)(content = _)(next); true }

  private final class PortalSlot(parent: runtime.OwnedScope, initial: RetainedPlacement) extends Slot:
    val scope = parent.child("retained-placement")
    private var description = initial
    private var failure = Option.empty[Throwable]
    def accepts(view: View): Boolean = view.isInstanceOf[RetainedPlacement]
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else { change(description)(description = _)(view.asInstanceOf[RetainedPlacement]); true }
    def dependencies: Vector[Signal[?]] = if runtime.docking.panel(description.panel).alive then Vector(description.outlet) else Vector.empty
    def snapshot: MountedSnapshot = if !runtime.docking.panel(description.panel).alive then MountedSnapshot.Empty
      else failure.fold[MountedSnapshot](MountedSnapshot.Empty)(MountedSnapshot.Unavailable(_))
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
      val panel = runtime.docking.panel(description.panel)
      if !panel.alive then false
      else read(description.outlet) match
        case None => true
        case Some(value) =>
          val next = value match
            case Value.Failed(error) => Some(error)
            case Value.Ok(ref) => runtime.docking.place(panel, scope, ref.asInstanceOf[NodeRef]); None
          if failure == next then false
          else { change(failure)(failure = _)(next); true }

  private final class ComponentSlot(parent: runtime.OwnedScope, key: Any, initial: Component.Placement[Any], requested: Signal.Reader => View) extends Slot:
    val scope = parent.child(initial.template.name)
    private var description = initial
    private val equality = initial.template.equality
    private final case class Instance(scope: runtime.OwnedScope, input: Cell[Any], child: Holder, view: View, value: Any)
    private var instance: Option[Instance] = None
    private var failure: Option[Throwable] = None
    def accepts(view: View): Boolean = view match
      case p: Component.Placement[?] => (description.template.token eq p.template.token) && sameDeps(description.template.dependencies, p.template.dependencies)
      case _ => false
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else { change(description)(v => description = v)(view.asInstanceOf[Component.Placement[Any]]); true }
    def dependencies: Vector[Signal[?]] = Vector(description.props) ++ instance.toVector.flatMap(_.child.dependencies)
    def snapshot: MountedSnapshot = failure match
      case Some(error) => MountedSnapshot.Unavailable(error)
      case None => instance.fold[MountedSnapshot](MountedSnapshot.Empty)(i =>
        MountedSnapshot.Component(MountedIdentity(parent.id, key, description.template.token, i.scope.id), i.value, i.child.snapshot))
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean = read(description.props) match
      case None => true
      case Some(Value.Failed(error)) =>
        if failure.exists(_ eq error) then false
        else
          // The component boundary remains to observe recovery. Its content
          // lifetime is retired so a failure never retains a working subtree.
          instance.foreach(_.scope.dispose())
          change(instance)(v => instance = v)(None)
          change(failure)(v => failure = v)(Some(error))
          true
      case Some(Value.Ok(value)) =>
        var changed = false
        if failure.nonEmpty then { change(failure)(v => failure = v)(None); changed = true }
        if instance.isEmpty then
          val instanceScope = scope.child("instance")
          val input = instanceScope.cell(value)(using equality)
          val facade = Inputs.from(input, reader => plain(requested(reader)) match
            case p: Component.Placement[?] if accepts(p) && sameKey(keyOf(requested(reader), key), key) => p.props.asInstanceOf[Signal[Any]]
            case _ => throw KernelError.DisposedScope)
          val view = runtime.build(instanceScope)(description.template.build(instanceScope, facade))
          val content = new Holder(instanceScope, 0, Some(reader => {
            val current = plain(requested(reader)).asInstanceOf[Component.Placement[Any]]
            reader(current.props) match
              case Value.Failed(error) => throw new Signal.InputFailure(error)
              case _ => ()
            view
          }))
          // Keep the returned description, never re-invoke the builder on a
          // later stabilization pass or same-key prop update.
          val i = Instance(instanceScope, input, content, view, value)
          change(instance)(v => instance = v)(Some(i))
          changed = true
        else if !runtime.lawful(equality.same(instance.get.value, value)) then
          val i = instance.get
          i.input.set(value)
          change(instance)(v => instance = v)(Some(i.copy(value = value)))
          return true
        val i = instance.get
        if i.child.sync(i.view, read) then changed = true
        changed

  private final class RowsSlot(parent: runtime.OwnedScope, initial: ForEach.Description[Any, Any], requested: Signal.Reader => View) extends Slot:
    val scope = parent.child("foreach")
    private var description = initial
    private val equality = initial.equality
    private final case class Row(key: Any, scope: runtime.OwnedScope, input: Cell[Any], child: Holder, view: View, value: Any)
    private var rows = Vector.empty[Row]
    private var failure: Option[Throwable] = None
    def accepts(view: View): Boolean = view match
      case p: ForEach.Description[?, ?] => p.schema == description.schema
      case _ => false
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else { change(description)(v => description = v)(view.asInstanceOf[ForEach.Description[Any, Any]]); true }
    def dependencies: Vector[Signal[?]] = Vector(description.items) ++ rows.flatMap(_.child.dependencies)
    def snapshot: MountedSnapshot = failure.fold[MountedSnapshot](MountedSnapshot.Rows(rows.map(r => r.key -> r.child.snapshot)))(MountedSnapshot.Unavailable(_))
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean = read(description.items) match
      case None => true
      case Some(Value.Failed(error)) =>
        if failure.exists(_ eq error) then false
        else
          rows.foreach(_.scope.dispose())
          change(rows)(v => rows = v)(Vector.empty)
          change(failure)(v => failure = v)(Some(error))
          true
      case Some(Value.Ok(value)) =>
        val items = value.asInstanceOf[Seq[Any]].toVector
        val keyed = runtime.lawful(items.map(a => (description.key(a), a)))
        duplicateKeys(keyed.map(_._1))
        val previous = runtime.lawful(rows.map(r => r.key -> r).toMap)
        val keys = runtime.lawful(keyed.map(_._1).toSet)
        rows.filterNot(r => runtime.lawful(keys(r.key))).foreach(_.scope.dispose())
        var changed = failure.nonEmpty || rows.size != keyed.size || !runtime.lawful(rows.map(_.key) == keyed.map(_._1))
        if failure.nonEmpty then change(failure)(v => failure = v)(None)
        var delivered = false
        val next = keyed.map { (key, value) => runtime.lawful(previous.get(key)) match
          case Some(row) =>
            if runtime.lawful(equality.same(row.value, value)) then row
            else
              row.input.set(value)
              delivered = true
              changed = true
              row.copy(value = value)
          case None =>
            changed = true
            val rowScope = scope.child("row")
            val input = rowScope.cell(value)(using equality)
            def lookup(reader: Signal.Reader): Any = plain(requested(reader)) match
              case p: ForEach.Description[?, ?] if accepts(p) =>
                val latest = p.asInstanceOf[ForEach.Description[Any, Any]]
                reader(latest.items) match
                  case Value.Failed(error) => throw new Signal.InputFailure(error)
                  case Value.Ok(values) =>
                    val keyed = values.map(v => latest.key(v) -> v).toVector
                    duplicateKeys(keyed.map(_._1))
                    keyed.find((k, _) => sameKey(k, key)).map(_._2).getOrElse(throw KernelError.DisposedScope)
              case _ => throw KernelError.DisposedScope
            val facade = Inputs.from(input, reader => Signal.const(lookup(reader)))
            val view = runtime.build(rowScope)(description.row(facade))
            Row(key, rowScope, input, new Holder(rowScope, 0, Some(reader => { lookup(reader); view })), view, value)
        }
        if changed then change(rows)(v => rows = v)(next)
        if !delivered then rows.foreach(row => if row.child.sync(row.view, read) then changed = true)
        changed

  private final class VirtualSlot(parent: runtime.OwnedScope, initial: VirtualRows.Description[Any, Any], requested: Signal.Reader => View) extends Slot:
    val scope = parent.child("virtual-rows")
    val viewport = scope.cell(Option.empty[RowViewport])
    private val effectiveViewport = viewport.zip(hostViewport).map((local, fallback) => local.getOrElse(fallback))
    private var description = initial
    private def windowFor(value: VirtualRows.Description[Any, Any]): Signal[CollectionWindow[Any, Any]] =
      val bounds = effectiveViewport.map(v => RowViewport.bounds(v, value.rowHeight, value.overscan))
      runtime.lawful(value.source.window(bounds))
    private var window = windowFor(initial)
    private def rowsFor(value: VirtualRows.Description[Any, Any], source: Signal[CollectionWindow[Any, Any]]): ForEach.Description[Any, Any] =
      ForEach.Description[Any, (Any, Any)](source.map(_.entries.toSeq)(using (a, b) =>
        a.size == b.size && a.zip(b).forall((x, y) => x._1 == y._1 && value.equality.same(x._2, y._2))), _._1,
        inputs => value.row(Inputs.from(inputs.map(_._2)(using value.equality))),
        (a, b) => a._1 == b._1 && value.equality.same(a._2, b._2), value.schema).asInstanceOf[ForEach.Description[Any, Any]]
    private var rowsDescription = rowsFor(initial, window)
    private val rows = new RowsSlot(scope, rowsDescription, reader => plain(requested(reader)) match
      case latest: VirtualRows.Description[?, ?] if accepts(latest) =>
        val typed = latest.asInstanceOf[VirtualRows.Description[Any, Any]]
        if typed.eq(description) then rowsDescription else rowsFor(typed, windowFor(typed))
      case _ => throw KernelError.DisposedScope)
    private var metadata = Option.empty[CollectionWindow[Any, Any]]
    private var failure = Option.empty[Throwable]
    change(virtuals)(virtuals = _)(virtuals :+ this)
    def accepts(view: View): Boolean = view match
      case value: VirtualRows.Description[?, ?] => value.schema == description.schema
      case _ => false
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else
        val next = view.asInstanceOf[VirtualRows.Description[Any, Any]]
        change(description)(description = _)(next)
        change(window)(window = _)(windowFor(next))
        change(rowsDescription)(rowsDescription = _)(rowsFor(next, window))
        true
    def dependencies: Vector[Signal[?]] = Vector(window, description.source.revision, effectiveViewport) ++ rows.dependencies
    def snapshot: MountedSnapshot = failure.fold[MountedSnapshot](metadata.fold[MountedSnapshot](MountedSnapshot.Empty) { value =>
      MountedSnapshot.VirtualRows(scope.id, value.revision, value.first, value.total,
        Lp(value.first.toDouble * description.rowHeight.toDouble),
        Lp((value.total - value.first - value.entries.size).toDouble * description.rowHeight.toDouble), description.rowHeight, rows.snapshot)
    })(MountedSnapshot.Unavailable(_))
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
      var changed = rows.refresh(rowsDescription)
      read(window) match
        case None => return true
        case Some(Value.Failed(error)) =>
          if !failure.exists(_ eq error) then { change(failure)(failure = _)(Some(error)); changed = true }
        case Some(Value.Ok(raw)) =>
          val value = raw.asInstanceOf[CollectionWindow[Any, Any]]
          (read(description.source.revision), read(effectiveViewport)) match
            case (Some(Value.Ok(revision)), Some(Value.Ok(view))) =>
              val bounds = RowViewport.bounds(view.asInstanceOf[RowViewport], description.rowHeight, description.overscan)
              val expectedFirst = bounds.from.min(value.total)
              val expectedSize = bounds.until.min(value.total) - expectedFirst
              if value.total < 0 || value.first != expectedFirst || value.entries.size != expectedSize || value.revision != revision then throw KernelError.StaleWindow
              require((value.total.toDouble * description.rowHeight.toDouble).isFinite, "virtual extent overflow")
            case (None, _) | (_, None) => return true
            case _ => throw KernelError.StaleWindow
          if failure.nonEmpty then { change(failure)(failure = _)(None); changed = true }
          if !metadata.exists(m => m.revision == value.revision && m.first == value.first && m.total == value.total && m.entries.size == value.entries.size) then
            change(metadata)(metadata = _)(Some(value))
            changed = true
      if rows.step(read) then changed = true
      changed

  private final class SwitchSlot(parent: runtime.OwnedScope, initialPlain: Switch.Plain[Any], initialKeyed: Option[Switch.Keyed[Any, Any]], requested: Signal.Reader => View) extends Slot:
    val scope = parent.child("switch")
    private var plainDescription = initialPlain
    private var keyedDescription = initialKeyed
    private val equality = initialKeyed.fold(initialPlain.equality)(_.equality)
    private final case class Branch(key: Any, scope: runtime.OwnedScope, input: Cell[Any], child: Holder, view: View, value: Any)
    private var branch: Option[Branch] = None
    private var failure: Option[Throwable] = None
    private def source: Signal[Any] = keyedDescription.fold(plainDescription.value)(_.value)
    def accepts(view: View): Boolean = (keyedDescription, view) match
      case (Some(a), b: Switch.Keyed[?, ?]) => a.schema == b.schema
      case (None, b: Switch.Plain[?]) => plainDescription.schema == b.schema
      case _ => false
    def refresh(view: View): Boolean = view match
      case p: Switch.Plain[?] if !plainDescription.eq(p) => change(plainDescription)(v => plainDescription = v)(p.asInstanceOf[Switch.Plain[Any]]); true
      case p: Switch.Keyed[?, ?] if !keyedDescription.get.eq(p) => change(keyedDescription)(v => keyedDescription = v)(Some(p.asInstanceOf[Switch.Keyed[Any, Any]])); true
      case _ => false
    def dependencies: Vector[Signal[?]] = Vector(source) ++ branch.toVector.flatMap(_.child.dependencies)
    def snapshot: MountedSnapshot = failure.fold[MountedSnapshot](branch.fold[MountedSnapshot](MountedSnapshot.Empty)(b => MountedSnapshot.Branch(b.key, b.scope.id, b.child.snapshot)))(MountedSnapshot.Unavailable(_))
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean = read(source) match
      case None => true
      case Some(Value.Failed(error)) =>
        if failure.exists(_ eq error) then false
        else
          branch.foreach(_.scope.dispose())
          change(branch)(v => branch = v)(None)
          change(failure)(v => failure = v)(Some(error))
          true
      case Some(Value.Ok(value)) =>
        val key = keyedDescription.fold(value)(p => runtime.lawful(p.key(value)))
        val retain = branch.exists(b => if keyedDescription.nonEmpty then sameKey(b.key, key) else runtime.lawful(equality.same(b.value, value)))
        var changed = !retain || failure.nonEmpty
        if failure.nonEmpty then change(failure)(v => failure = v)(None)
        if !retain then
          branch.foreach(_.scope.dispose())
          val branchScope = scope.child("branch")
          val input = branchScope.cell(value)(using equality)
          def current(reader: Signal.Reader): Any = plain(requested(reader)) match
            case p: Switch.Keyed[?, ?] if accepts(p) =>
              val latest = p.asInstanceOf[Switch.Keyed[Any, Any]]
              reader(latest.value) match
                case Value.Failed(error) => throw new Signal.InputFailure(error)
                case Value.Ok(v) => if sameKey(latest.key(v), key) then v else throw KernelError.DisposedScope
            case p: Switch.Plain[?] if accepts(p) =>
              reader(p.value) match
                case Value.Failed(error) => throw new Signal.InputFailure(error)
                case Value.Ok(v) => if runtime.lawful(equality.same(value, v)) then v else throw KernelError.DisposedScope
            case _ => throw KernelError.DisposedScope
          val facade = Inputs.from(input, reader => Signal.const(current(reader)))
          val view = runtime.build(branchScope)(keyedDescription.fold(plainDescription.branch(value))(p => p.branch(facade)))
          val child = new Holder(branchScope, 0, Some(reader => { current(reader); view }))
          change(branch)(v => branch = v)(Some(Branch(key, branchScope, input, child, view, value)))
        else if !runtime.lawful(equality.same(branch.get.value, value)) then
          val b = branch.get
          b.input.set(value)
          change(branch)(v => branch = v)(Some(b.copy(value = value)))
          return true
        val b = branch.get
        if b.child.sync(b.view, read) then changed = true
        changed

  private final class StackSlot(parent: runtime.OwnedScope, initial: View.Stack, requested: Signal.Reader => View) extends Slot:
    val scope = parent.child("static-children")
    private var description = initial
    private var children = Vector.empty[(Any, Holder)]
    def accepts(view: View): Boolean = view match
      case p: View.Stack => p.horizontal == description.horizontal
      case _ => false
    def refresh(view: View): Boolean =
      if description.eq(view) then false
      else { change(description)(v => description = v)(view.asInstanceOf[View.Stack]); true }
    def dependencies: Vector[Signal[?]] = description.children.flatMap(directDependencies) ++ children.flatMap(_._2.dependencies)
    def snapshot: MountedSnapshot = MountedSnapshot.Group(children.map(_._2.snapshot), description.horizontal, description.gap.getOrElse(Lp.zero), description.alignment.getOrElse(Alignment.Start))
    def step(read: Signal[?] => Option[Value[Any]])(using UiTxn): Boolean =
      val keyed = description.children.zipWithIndex.map((view, index) => keyOf(view, index) -> view)
      duplicateKeys(keyed.map(_._1))
      val previous = runtime.lawful(children.toMap)
      val keys = runtime.lawful(keyed.map(_._1).toSet)
      children.filterNot((key, _) => runtime.lawful(keys(key))).foreach(_._2.retire())
      var changed = !runtime.lawful(children.map(_._1) == keyed.map(_._1))
      val next = keyed.map { (key, view) =>
        val child = runtime.lawful(previous.get(key)).getOrElse(new Holder(scope, key, Some(reader => {
          val latest = plain(requested(reader)).asInstanceOf[View.Stack]
          val entries = latest.children.zipWithIndex.map((v, i) => keyOf(v, i) -> v)
          duplicateKeys(entries.map(_._1))
          entries.find((k, _) => sameKey(k, key)).map(_._2).getOrElse(throw KernelError.DisposedScope)
        })))
        if child.sync(view, read) then changed = true
        (key, child)
      }
      if changed then change(children)(v => children = v)(next)
      changed
