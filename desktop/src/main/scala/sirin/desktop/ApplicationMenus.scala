package sirin.desktop

import sirin.*
import sirin.host.*
import scala.jdk.CollectionConverters.*

/** Main-thread presenter. Only immutable native rows and revision/ID facts cross FFM. */
private[desktop] final class ApplicationMenus(native: NativeApplication):
  private val presentation=new MenuPresentation
  def update(application: CapturedMenus,window: Option[CapturedWindow]): Unit =
    presentation.update(window.map(_.instance),application,window.fold(CapturedMenus.empty)(_.menu),
      window.fold(Set.empty[Chord])(_.content.keys.map(_.chord).toSet)).foreach { next =>
      native.installMenus(next.revision,ApplicationMenus.rows(next.menus).asJava,next.routed.map(ApplicationMenus.key).asJava)
    }
  def action(fact: NativeApplication.MenuAction): Option[Activation]=presentation.action(fact.revision(),fact.id())

private[desktop] object ApplicationMenus:
  def key(chord: Chord): NativeApplication.MenuKey =
    val value=KeyboardRouting.canonical(chord).key match
      case Key.Character(c) => c.toString
      case Key.Enter => "\r"
      case Key.Escape => "\u001b"
      case Key.Tab => "\t"
      case Key.Space => " "
      case Key.Backspace => "\b"
      case Key.Delete => "\u007f"
      case Key.ArrowUp => "\uf700"
      case Key.ArrowDown => "\uf701"
      case Key.ArrowLeft => "\uf702"
      case Key.ArrowRight => "\uf703"
      case Key.Home => "\uf729"
      case Key.End => "\uf72b"
      case Key.PageUp => "\uf72c"
      case Key.PageDown => "\uf72d"
    val mods=(if chord.mods.has(Mods.Primary) then 1 else 0) |
      (if chord.mods.has(Mods.Shift) then 2 else 0) |
      (if chord.mods.has(Mods.Alt) then 4 else 0) |
      (if chord.mods.has(Mods.Control) then 8 else 0)
    new NativeApplication.MenuKey(value,mods)
  private val none=new NativeApplication.MenuKey("",0)
  def rows(menus: CapturedMenus): Vector[NativeApplication.MenuRow] =
    val result=scala.collection.mutable.ArrayBuffer.empty[NativeApplication.MenuRow]
    def visit(nodes: Vector[MenuNode],parent: Int): Unit=nodes.foreach { node =>
      val id=result.size+1
      require(id<=512,"merged native menu structure budget exceeded")
      node match
        case MenuNode.Group(label,entries) =>
          result+=new NativeApplication.MenuRow(parent,id,0,0,label,none,true,false)
          visit(entries,id)
        case MenuNode.Item(action,label,chord,enabled,busy) =>
          result+=new NativeApplication.MenuRow(parent,id,1,action,label,chord.fold(none)(key),enabled,busy)
        case MenuNode.Separator => result+=new NativeApplication.MenuRow(parent,id,2,0,"",none,true,false)
    }
    visit(menus.nodes,0);result.toVector
  def standard(chord: Chord): Option[Int]=KeyboardRouting.canonical(chord) match
    case Chord(Key.Character('q'),mods) if mods==Mods.Primary => Some(1)
    case Chord(Key.Character('h'),mods) if mods==Mods.Primary => Some(2)
    case Chord(Key.Character('h'),mods) if mods==(Mods.Primary|Mods.Alt) => Some(3)
    case _ => None
