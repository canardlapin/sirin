package sirin.host

import sirin.*
import sirin.engine.*

/** UI-owner capture; only content.tree is sent to a render worker. */
final case class CapturedWindow(instance: Long,title: Value[String],menu: MenuBar,content: CapturedTree)

object WindowCapture:
  def capture(snapshot: MountedSnapshot,reader: CommittedRead,maxWindows: Int=16,
      maxNodes: Int=10000,maxTextUnits: Int=100000): Vector[CapturedWindow] =
    require(maxWindows > 0 && maxWindows <= 16,"native window budget must be 1 through 16")
    val budget=new CaptureBudget(maxNodes,maxTextUnits)
    val result=scala.collection.mutable.ArrayBuffer.empty[CapturedWindow]
    val seen=scala.collection.mutable.Set.empty[Long]
    def menu(value: Menu,depth: Int): Unit =
      budget.visit(depth);budget.text(value.title.length)
      value.entries.foreach {
        case MenuEntry.Item(command,_) => budget.visit(depth+1);budget.text(command.label.length)
        case MenuEntry.Separator => budget.visit(depth+1)
        case MenuEntry.Submenu(child) => menu(child,depth+1)
      }
    def loop(value: MountedSnapshot,depth: Int): Unit =
      budget.visit(depth)
      value match
        case MountedSnapshot.Empty => ()
        case MountedSnapshot.Component(_,_,child) => loop(child,depth+1)
        case MountedSnapshot.Rows(rows) => rows.foreach((_,child) => loop(child,depth+1))
        case MountedSnapshot.Window(instance,title,menus,child) =>
          require(result.size<maxWindows,"native window budget exceeded")
          require(seen.add(instance),"duplicate mounted window instance")
          title match
            case Value.Ok(text) => budget.text(text.length)
            case _ => ()
          menus.menus.foreach(menu(_,depth+1))
          result+=CapturedWindow(instance,title,menus,CommittedTree.captureWithBudget(child,reader,budget,depth+1))
        case MountedSnapshot.Unavailable(error) => throw error
        case _ => throw new IllegalArgumentException("application root must be a Windows description")
    loop(snapshot,0);result.toVector
