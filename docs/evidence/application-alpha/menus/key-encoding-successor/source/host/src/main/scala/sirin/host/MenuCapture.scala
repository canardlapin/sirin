package sirin.host

import sirin.*
import sirin.engine.CommittedRead

enum MenuNode:
  case Group(label: String,entries: Vector[MenuNode])
  case Item(id: Int,label: String,chord: Option[Chord],enabled: Boolean,busy: Boolean)
  case Separator

final case class MenuAction(source: MenuEntry,activation: Activation,failure: Option[Throwable])
final case class CapturedMenus(nodes: Vector[MenuNode],actions: Map[Int,MenuAction]):
  def sameBindings(other: CapturedMenus): Boolean =
    actions.keySet==other.actions.keySet && actions.forall { (id,action) => action.source eq other.actions(id).source }
  def shortcut(chord: Chord): Option[Activation] =
    def find(nodes: Vector[MenuNode]): Option[Int] = nodes.iterator.map {
      case MenuNode.Group(_,entries) => find(entries)
      case MenuNode.Item(id,_,Some(key),_,_) if KeyboardRouting.canonical(key)==KeyboardRouting.canonical(chord) => Some(id)
      case _ => None
    }.collectFirst {case Some(id) => id}
    find(nodes).flatMap(actions.get).map(_.activation)

object CapturedMenus:
  val empty: CapturedMenus=CapturedMenus(Vector.empty,Map.empty)

object MenuCapture:
  private[host] def capture(bar: MenuBar,reader: CommittedRead,budget: CaptureBudget,
      depth: Int,idBase: Int): CapturedMenus =
    val actions=scala.collection.mutable.Map.empty[Int,MenuAction]
    var serial=0
    def node(entry: MenuEntry,level: Int): MenuNode =
      serial+=1
      require(serial<=512 && level<=32,"menu structure budget exceeded")
      budget.visit(depth+level)
      entry match
        case MenuEntry.Submenu(menu) =>
          budget.text(menu.title.length)
          MenuNode.Group(menu.title,menu.entries.toVector.map(node(_,level+1)))
        case item @ MenuEntry.Item(command,chord) =>
          val id=idBase+serial
          budget.text(command.label.length)
          val binding=item.binding.get
          val enabled=reader(binding.enabled)
          val busy=reader(binding.running)
          val failure=Vector(enabled,busy).collectFirst {case Value.Failed(error) => error}
          val active=enabled match
            case Value.Ok(value) => value
            case _ => false
          val running=busy match
            case Value.Ok(count) => count>0
            case _ => false
          actions(id)=MenuAction(item,Activation(active,txn => binding.invoke()(using txn)),failure)
          MenuNode.Item(id,command.label,chord,active,running)
        case MenuEntry.Separator => MenuNode.Separator
    require(bar.menus.map(_.title).distinct.size==bar.menus.size,"duplicate top-level menu title")
    val nodes=bar.menus.toVector.map(menu => node(MenuEntry.Submenu(menu),0))
    CapturedMenus(nodes,actions.toMap)

  def combine(application: CapturedMenus,window: CapturedMenus): CapturedMenus =
    val merged=scala.collection.mutable.ArrayBuffer.from(application.nodes)
    window.nodes.foreach {
      case MenuNode.Group(label,entries) =>
        val index=merged.indexWhere {
          case MenuNode.Group(title,_) => title==label
          case _ => false
        }
        if index<0 then merged+=MenuNode.Group(label,entries)
        else merged(index) match
          case MenuNode.Group(_,existing) => merged(index)=MenuNode.Group(label,existing++entries)
          case _ => throw new IllegalStateException("invalid menu group")
      case other => merged+=other
    }
    require(application.actions.keySet.intersect(window.actions.keySet).isEmpty,"menu action namespaces overlap")
    CapturedMenus(merged.toVector,application.actions++window.actions)


final case class MenuRevision(revision: Long,window: Option[Long],menus: CapturedMenus,routed: Set[Chord])

/** Owner-confined presentation identity; no callback is rebound to a reused row. */
final class MenuPresentation:
  private var current=Option.empty[MenuRevision]
  def snapshot: Option[MenuRevision]=current
  def update(window: Option[Long],application: CapturedMenus,local: CapturedMenus,
      chords: Set[Chord]): Option[MenuRevision] =
    val menus=MenuCapture.combine(application,local)
    val routed=if window.isEmpty then Set.empty[Chord]
      else chords.map(KeyboardRouting.canonical)++MenuPresentation.chords(menus.nodes)
    val changed=current.forall(old => old.window!=window || old.menus.nodes!=menus.nodes ||
      !old.menus.sameBindings(menus) || old.routed!=routed)
    if !changed then None
    else
      val next=MenuRevision(Math.addExact(current.fold(0L)(_.revision),1L),window,menus,routed)
      current=Some(next);Some(next)
  def action(revision: Long,id: Int): Option[Activation] =
    current.filter(_.revision==revision).flatMap(_.menus.actions.get(id)).map(_.activation)

object MenuPresentation:
  def chords(nodes: Vector[MenuNode]): Set[Chord] = nodes.flatMap {
    case MenuNode.Group(_,entries) => chords(entries)
    case MenuNode.Item(_,_,chord,_,_) => chord.map(KeyboardRouting.canonical).toSet
    case _ => Set.empty[Chord]
  }.toSet
