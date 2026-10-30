package sirin.host

import sirin.*
import sirin.engine.*

/** UI-owner capture; only content.tree is sent to a render worker. */
final case class CapturedWindow(instance: Long,title: Value[String],menu: CapturedMenus,content: CapturedTree,renderStatus: WindowRenderStatus=WindowRenderStatus.empty)

final case class CapturedApplication(menu: CapturedMenus,windows: Vector[CapturedWindow])

object WindowCapture:
  def capture(snapshot: MountedSnapshot,reader: CommittedRead,maxWindows: Int=16,
      maxNodes: Int=10000,maxTextUnits: Int=100000): Vector[CapturedWindow] =
    captureApplication(snapshot,reader,maxWindows,maxNodes,maxTextUnits).windows

  def captureApplication(snapshot: MountedSnapshot,reader: CommittedRead,maxWindows: Int=16,
      maxNodes: Int=10000,maxTextUnits: Int=100000): CapturedApplication =
    require(maxWindows > 0 && maxWindows <= 16,"native window budget must be 1 through 16")
    val budget=new CaptureBudget(maxNodes,maxTextUnits)
    val result=scala.collection.mutable.ArrayBuffer.empty[CapturedWindow]
    val seen=scala.collection.mutable.Set.empty[Long]
    var applicationMenu=CapturedMenus.empty
    var applicationMenuSeen=false
    def loop(value: MountedSnapshot,depth: Int): Unit =
      budget.visit(depth)
      value match
        case MountedSnapshot.Empty => ()
        case MountedSnapshot.Component(_,_,child) => loop(child,depth+1)
        case MountedSnapshot.Rows(rows) => rows.foreach((_,child) => loop(child,depth+1))
        case MountedSnapshot.Group(children,_,_,_) => children.foreach(loop(_,depth+1))
        case MountedSnapshot.Leaf(description: Windows.MenuDescription) =>
          require(!applicationMenuSeen,"duplicate application menu declaration")
          applicationMenuSeen=true
          applicationMenu=MenuCapture.capture(description.menu,reader,budget,depth+1,0)
        case MountedSnapshot.Window(instance,title,menus,child,status) =>
          require(result.size<maxWindows,"native window budget exceeded")
          require(seen.add(instance),"duplicate mounted window instance")
          title match
            case Value.Ok(text) => budget.text(text.length)
            case _ => ()
          val capturedMenu=MenuCapture.capture(menus,reader,budget,depth+1,1024)
          result+=CapturedWindow(instance,title,capturedMenu,CommittedTree.captureWithBudget(child,reader,budget,depth+1),status)
        case MountedSnapshot.Unavailable(error) => throw error
        case _ => throw new IllegalArgumentException("application root must be a Windows description")
    loop(snapshot,0);CapturedApplication(applicationMenu,result.toVector)
