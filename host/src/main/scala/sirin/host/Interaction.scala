package sirin.host

import sirin.*

enum HostInput:
  case KeyDown(key: Key,mods: Mods,repeat: Boolean=false)
  case KeyUp(key: Key,mods: Mods)
  case Down(x: Double,y: Double,primary: Boolean=true)
  case Up(x: Double,y: Double,primary: Boolean=true)
  case Wheel(x: Double,y: Double,delta: Double)
  case Move(x: Double,y: Double,primary: Boolean)
  case TextInput(value: String)
  case Composition(value: String,start: Int,length: Int)
  case Deactivate

enum TargetKind:
  case Custom(frame: sirin.engine.ElementFrame)
  case Button(instance: Long)
  case Editor(instance: Long)
  case Scroll(instance: Long,maximum: Double)
final case class Target(id: NodeId,kind: TargetKind,bounds: Box,clip: Box,enabled: Boolean,
    directRefs: Vector[Long],ancestorRefs: Vector[Long],ancestors: Vector[NodeId]):
  def focusable: Boolean = kind match
    case TargetKind.Custom(frame) => frame.focusable
    case _ => true
  def hit(x: Double,y: Double): Boolean =
    val inside=x>=clip.x && y>=clip.y && x<clip.x+clip.width && y<clip.y+clip.height
    inside && (kind match
      case TargetKind.Custom(frame) => frame.hit(Size(bounds.width.lp,bounds.height.lp),Point((x-bounds.x).lp,(y-bounds.y).lp))!=Hit.Miss
      case _ => true)
final case class InteractionSnapshot(epoch: Long,targets: Vector[Target]):
  require(targets.size<=10000 && targets.map(_.id).distinct.size==targets.size,"interaction identity/budget")
  def target(id: NodeId): Option[Target] = targets.find(_.id==id)
object InteractionSnapshot:
  def apply(epoch: Long,layout: LayoutResult): InteractionSnapshot =
    val targets=Vector.newBuilder[Target]
    def visit(p: Placed,direct: Vector[Long],ancestors: Vector[Long],parents: Vector[NodeId]): Unit =
      if p.clip.width>0 && p.clip.height>0 then p.node.content match
        case Content.Reference(ref,_) => p.children.foreach(visit(_,direct:+ref,ancestors,parents:+p.node.id))
        case Content.Styled(_,_) => p.children.foreach(visit(_,direct,ancestors,parents:+p.node.id))
        case Content.Custom(frame) =>
          targets+=Target(p.node.id,TargetKind.Custom(frame),p.bounds,p.clip,true,direct,ancestors,parents)
        case Content.Button(instance,_,enabled,_,_) =>
          targets+=Target(p.node.id,TargetKind.Button(instance),p.bounds,p.clip,enabled,direct,ancestors,parents)
        case Content.Editable(instance,_,_,_,_,_) =>
          targets+=Target(p.node.id,TargetKind.Editor(instance),p.bounds,p.clip,true,direct,ancestors,parents)
        case Content.Scroll(instance,_) =>
          val maximum=math.max(0,p.children.headOption.fold(0.0)(_.bounds.height)-p.bounds.height)
          targets+=Target(p.node.id,TargetKind.Scroll(instance,maximum),p.bounds,p.clip,true,direct,ancestors,parents)
          p.children.foreach(visit(_,Vector.empty,ancestors++direct,parents:+p.node.id))
        case _ => p.children.foreach(visit(_,Vector.empty,ancestors++direct,parents:+p.node.id))
    visit(layout.root,Vector.empty,Vector.empty,Vector.empty)
    InteractionSnapshot(epoch,targets.result())
enum PressOrigin:
  case Pointer, Space
final case class Press(target: NodeId,epoch: Long,origin: PressOrigin)
final case class FocusState(focused: Option[NodeId]=None,keyboard: Boolean=false,
    pressed: Option[Press]=None,scrolls: Map[Long,Double]=Map.empty)
final case class InputDecision(activate: Option[Long]=None,changed: Boolean=false)

/** Owner-only state over the last successfully presented complete geometry. */
final class Interaction:
  private var snapshot=InteractionSnapshot(0,Vector.empty)
  var state=FocusState()
  def rollbackPublication(before: FocusState): Unit = {state=before.copy(pressed=state.pressed)}
  def presented: InteractionSnapshot = snapshot
  def install(value: InteractionSnapshot,scrolls: Map[Long,Double]): Unit =
    val next=if value.targets==snapshot.targets then value.copy(epoch=snapshot.epoch) else value
    val pressed=state.pressed.filter(_.epoch==next.epoch)
    snapshot=next
    state=state.copy(focused=state.focused.filter(id => next.target(id).nonEmpty),pressed=pressed,scrolls=scrolls)
  def focused: Option[Target] = state.focused.flatMap(snapshot.target)
  def focusRef(reference: Long): Boolean = snapshot.targets.find(t => t.focusable && t.directRefs.contains(reference)) match
    case None => false
    case Some(target) => state=state.copy(focused=Some(target.id),keyboard=true,pressed=None);true
  def focusReferences: (Set[Long],Set[Long],Boolean) = focused.fold((Set.empty[Long],Set.empty[Long],state.keyboard))(t => (t.directRefs.toSet,(t.directRefs++t.ancestorRefs).toSet,state.keyboard))
  private def activation(target: Option[Target]): Option[Long] = target.filter(_.enabled).flatMap(_.kind match
    case TargetKind.Button(id) => Some(id)
    case _ => None)
  private def hit(x: Double,y: Double): Option[Target] = snapshot.targets.reverseIterator.find(_.hit(x,y))
  private def scroll(target: Target,delta: Double,absolute: Boolean=false): Unit = target.kind match
    case TargetKind.Scroll(instance,maximum) =>
      val next=if absolute then delta else state.scrolls.getOrElse(instance,0.0)+delta
      state=state.copy(scrolls=state.scrolls.updated(instance,math.max(0,math.min(maximum,next))))
    case _ => ()
  def handle(input: HostInput): InputDecision =
    val before=state
    var activate=Option.empty[Long]
    input match
      case HostInput.Deactivate => state=state.copy(pressed=None)
      case HostInput.KeyDown(Key.Tab,mods,_) if mods.isEmpty || mods==Mods.Shift =>
        val all=snapshot.targets.filter(_.focusable)
        if all.nonEmpty then
          val index=all.indexWhere(t => state.focused.contains(t.id))
          val backwards=mods.has(Mods.Shift)
          val start=if index<0 then (if backwards then 0 else -1) else index
          val next=(1 to all.size).iterator.map { offset =>
            val n=if backwards then start-offset else start+offset
            all(((n%all.size)+all.size)%all.size)
          }.find(_.enabled)
          next.foreach(t => state=state.copy(focused=Some(t.id),keyboard=true,pressed=None))
      case HostInput.KeyDown(Key.Enter,mods,false) if mods.isEmpty =>
        state=state.copy(keyboard=true);activate=activation(focused)
      case HostInput.KeyDown(Key.Space,mods,false) if mods.isEmpty =>
        state=state.copy(keyboard=true,pressed=focused.filter(_.enabled).map(t => Press(t.id,snapshot.epoch,PressOrigin.Space)))
      case HostInput.KeyUp(Key.Space,mods) =>
        if mods.isEmpty && state.pressed.exists(p => p.origin==PressOrigin.Space && state.focused.contains(p.target) && p.epoch==snapshot.epoch) then activate=activation(focused)
        state=state.copy(pressed=None)
      case HostInput.KeyDown(key,mods,_) if mods.isEmpty =>
        focused.filter(_.kind.isInstanceOf[TargetKind.Scroll]).foreach { target =>
          key match
            case Key.ArrowDown => scroll(target,40)
            case Key.ArrowUp => scroll(target,-40)
            case Key.PageDown => scroll(target,target.bounds.height*0.9)
            case Key.PageUp => scroll(target,-target.bounds.height*0.9)
            case Key.Home => scroll(target,0,true)
            case Key.End => scroll(target,Double.PositiveInfinity,true)
            case _ => ()
        }
      case HostInput.Down(x,y,true) =>
        val target=hit(x,y)
        state=state.copy(focused=target.filter(_.focusable).map(_.id),keyboard=false,pressed=target.filter(_.enabled).map(t => Press(t.id,snapshot.epoch,PressOrigin.Pointer)))
      case HostInput.Up(x,y,true) =>
        val target=hit(x,y)
        if state.pressed.exists(p => p.origin==PressOrigin.Pointer && p.epoch==snapshot.epoch && target.exists(_.id==p.target)) then activate=activation(target)
        state=state.copy(pressed=None)
      case HostInput.Wheel(x,y,delta) if delta.isFinite =>
        snapshot.targets.reverseIterator.find(t => t.hit(x,y) && t.kind.isInstanceOf[TargetKind.Scroll]).foreach(scroll(_,-delta*40))
      case _ => ()
    InputDecision(activate,state!=before)
