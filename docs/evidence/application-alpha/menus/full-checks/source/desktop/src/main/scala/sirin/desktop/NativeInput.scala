package sirin.desktop

import sirin.*
import sirin.host.HostInput

object NativeInput:
  def decode(event: NativeWindow.Event): Option[HostInput] =
    val mods=(if (event.mods()&1)!=0 then Mods.Primary else Mods.none) |
      (if (event.mods()&2)!=0 then Mods.Shift else Mods.none) |
      (if (event.mods()&4)!=0 then Mods.Alt else Mods.none) |
      (if (event.mods()&8)!=0 then Mods.Control else Mods.none)
    def key: Option[Key] = event.key() match
      case 1 => Some(Key.Tab)
      case 2 => Some(Key.Enter)
      case 3 => Some(Key.Space)
      case 4 => Some(Key.Escape)
      case 5 => Some(Key.ArrowUp)
      case 6 => Some(Key.ArrowDown)
      case 7 => Some(Key.PageUp)
      case 8 => Some(Key.PageDown)
      case 9 => Some(Key.Home)
      case 10 => Some(Key.End)
      case 11 => Some(Key.ArrowLeft)
      case 12 => Some(Key.ArrowRight)
      case 13 => Some(Key.Backspace)
      case 14 => Some(Key.Delete)
      case k if k>=1000 && k<66536 => Some(Key.Character((k-1000).toChar))
      case _ => None
    event.kind() match
      case 1 => key.map(HostInput.KeyDown(_,mods,event.repeat()))
      case 2 => key.map(HostInput.KeyUp(_,mods))
      case 3 => Some(HostInput.Down(event.x(),event.y(),event.button()==1))
      case 4 => Some(HostInput.Up(event.x(),event.y(),event.button()==1))
      case 5 => Some(HostInput.Move(event.x(),event.y(),event.button()==1))
      case 10 => Some(HostInput.TextInput(event.text()))
      case 11 => Some(HostInput.Composition(event.text(),event.key(),event.mods()))
      case 6 => Some(HostInput.Wheel(event.x(),event.y(),event.deltaY()))
      case 7 => Some(HostInput.Deactivate)
      case _ => None
