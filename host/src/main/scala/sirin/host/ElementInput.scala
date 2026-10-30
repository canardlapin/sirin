package sirin.host

import sirin.*
import sirin.engine.*

/** Owner-side dispatch against successfully presented, still-current geometry. */
final class ElementInput(runtime: HeadlessRuntime,interaction: Interaction):
  private var bindings=Map.empty[Long,ElementBinding]
  private var capture=Option.empty[(Long,PointerButton)]
  private var shown=Map.empty[Long,Placed]
  def captured: Option[Long]=capture.map(_._1)
  def update(value: Map[Long,ElementBinding]): Unit =
    bindings=value;capture=capture.filter((id,_) => bindings.contains(id))
  def install(layout: LayoutResult): Unit =
    shown=layout.elements.map(p => p.node.content.asInstanceOf[Content.Custom].frame.instance -> p).toMap
    capture=capture.filter((id,_) => shown.contains(id) && bindings.contains(id))
  def invalidate(): Unit = {capture=None;shown=Map.empty}
  private def target(x: Double,y: Double): Option[Long] = interaction.presented.targets.reverseIterator.find(_.hit(x,y)).flatMap(_.kind match
    case TargetKind.Custom(frame) => Some(frame.instance)
    case _ => None)
  def accepts(input: HostInput): Boolean = input match
    case HostInput.Down(x,y,_) => capture.nonEmpty || target(x,y).exists(bindings.contains)
    case HostInput.Move(x,y,_) => capture.nonEmpty || target(x,y).exists(bindings.contains)
    case HostInput.Up(x,y,_) => capture.nonEmpty || target(x,y).exists(bindings.contains)
    case HostInput.Deactivate => capture.nonEmpty
    case _ => false
  def pointer(input: HostInput,mods: Mods=Mods.none,clicks: Int=1): Option[CommitResult[PointerResponse]] =
    val event=input match
      case HostInput.Down(x,y,primary) => Some((PointerPhase.Down,x,y,if primary then PointerButton.Primary else PointerButton.Secondary))
      case HostInput.Move(x,y,_) => Some((PointerPhase.Move,x,y,capture.fold(PointerButton.Primary)(_._2)))
      case HostInput.Up(x,y,primary) => Some((PointerPhase.Up,x,y,if primary then PointerButton.Primary else PointerButton.Secondary))
      case HostInput.Deactivate => capture.map((_,button) => (PointerPhase.Cancel,0.0,0.0,button))
      case _ => None
    event.flatMap { (phase,x,y,button) =>
      val recipient=capture.map(_._1).orElse(target(x,y))
      recipient.filter(id => shown.contains(id) && bindings.contains(id)).map { id =>
        val p=shown(id);val frame=p.node.content.asInstanceOf[Content.Custom].frame
        val local=Point((x-p.bounds.x).lp,(y-p.bounds.y).lp)
        val hit=if phase==PointerPhase.Cancel || !p.clip.contains(x,y) then Hit.Miss else frame.hit(Size(p.bounds.width.lp,p.bounds.height.lp),local)
        val result=runtime.transact {bindings(id).pointer(PointerEvent(phase,local,button,mods,clicks,hit,capture.exists(_._1==id)))}
        result match
          case CommitResult.Committed(PointerResponse.Capture) => capture=Some((id,button))
          case CommitResult.Committed(PointerResponse.Release) => capture=None
          case _ => ()
        if phase==PointerPhase.Up || phase==PointerPhase.Cancel then capture=None
        result
      }
    }
  def wheel(x: Double,y: Double,delta: Double,mods: Mods): Option[CommitResult[Boolean]] =
    require(Vector(x,y,delta).forall(_.isFinite),"invalid wheel event")
    target(x,y).filter(id => shown.contains(id) && bindings.contains(id)).map { id =>
      val p=shown(id);val frame=p.node.content.asInstanceOf[Content.Custom].frame
      val local=Point((x-p.bounds.x).lp,(y-p.bounds.y).lp)
      runtime.transact {bindings(id).wheel(WheelEvent(local,Point(0.lp,delta.lp),mods,frame.hit(Size(p.bounds.width.lp,p.bounds.height.lp),local)))}
    }
  def key(event: KeyEvent): Option[CommitResult[Boolean]] = interaction.focused.flatMap(_.kind match
    case TargetKind.Custom(frame) if shown.contains(frame.instance) => bindings.get(frame.instance).map(binding => runtime.transact {binding.key(event)})
    case _ => None)
  def action(instance: Long,part: PartId,action: SemanticAction): Option[CommitResult[Boolean]] =
    shown.get(instance).flatMap { p =>
      val frame=p.node.content.asInstanceOf[Content.Custom].frame
      Option.when(frame.semantics(Size(p.bounds.width.lp,p.bounds.height.lp)).exists(n => n.part==part && n.actions(action)))(())
        .flatMap(_ => bindings.get(instance).map(binding => runtime.transact {binding.action(part,action)}))
    }
