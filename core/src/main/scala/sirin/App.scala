// Provisional A-0 skeleton (VISION §4.1): public shape only, not implemented.
package sirin

enum MenuEntry:
  case Item(command: Command[Unit, ?], chord: Option[Chord])
  case Separator
  case Submenu(menu: Menu)

object MenuItem:
  /** A menu item whose label and enablement come from the command. A chord
    * given here is also an active window-wide shortcut.
    */
  def apply(command: Command[Unit, ?]): MenuEntry              = MenuEntry.Item(command, None)
  def apply(command: Command[Unit, ?], chord: Chord): MenuEntry = MenuEntry.Item(command, Some(chord))

final case class Menu(title: String, entries: MenuEntry*)

final case class MenuBar(menus: Menu*)

object MenuBar:
  val empty: MenuBar = MenuBar()

trait Window

object Window:
  def apply(title: String, menu: MenuBar)(content: View): Window         = ???
  def apply(title: Signal[String], menu: MenuBar)(content: View): Window = ???

object Application:
  /** Runs the event loop on the process main thread. `build` receives the
    * application scope, which outlives every window.
    */
  def run(build: Scope => Seq[Window]): Unit = ???
