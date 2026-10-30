package sirin.desktop

import sirin.*
import sirin.host.*
import scala.jdk.CollectionConverters.*

class ApplicationMenusSuite extends munit.FunSuite:
  private val none=new NativeApplication.MenuKey("",0)
  test("native rows preserve nested parentage, action IDs, enabled/running and Unicode labels") {
    val menus=CapturedMenus(Vector(MenuNode.Group("文件",Vector(MenuNode.Item(1026,"保存",Some(Chord.primaryShift('S')),false,true),
      MenuNode.Separator,MenuNode.Group("More",Vector(MenuNode.Item(7,"New",None,true,false)))))),Map.empty)
    val rows=ApplicationMenus.rows(menus)
    NativeApplication.validateMenus(rows.asJava,Set(ApplicationMenus.key(Chord.primaryShift('S'))).asJava)
    assertEquals(rows.map(_.node()),Vector(1,2,3,4,5));assertEquals(rows.map(_.parent()),Vector(0,1,1,1,4))
    assertEquals(rows(1).action(),1026);assertEquals(rows(1).key(),new NativeApplication.MenuKey("s",3))
    assert(!rows(1).enabled());assert(rows(1).busy());assertEquals(rows(4).action(),7)
    assertEquals(rows.head.label(),"文件")
  }
  test("all public logical keys have distinct Cocoa equivalents and physical Control stays distinct") {
    val keys=Vector(Key.Enter,Key.Escape,Key.Tab,Key.Space,Key.Backspace,Key.Delete,Key.ArrowUp,Key.ArrowDown,
      Key.ArrowLeft,Key.ArrowRight,Key.Home,Key.End,Key.PageUp,Key.PageDown,Key.Character('界'))
    assertEquals(keys.map(k => ApplicationMenus.key(Chord(k)).key()).distinct.size,keys.size)
    assertEquals(ApplicationMenus.key(Chord(Key.Character('界'),Mods.Control|Mods.Alt)).mods(),12)
    assertEquals(ApplicationMenus.standard(Chord.primary('Q')),Some(1))
    assertEquals(ApplicationMenus.standard(Chord(Key.Character('h'),Mods.Primary|Mods.Alt)),Some(3))
    assertEquals(ApplicationMenus.standard(Chord.primaryShift('q')),None)
  }
  test("merged menus, rows, chords and text have enforced transport bounds before publication") {
    val nodes=Vector(MenuNode.Group("File",Vector.fill(512)(MenuNode.Separator)))
    intercept[IllegalArgumentException](ApplicationMenus.rows(CapturedMenus(nodes,Map.empty)))
    def check(rows: Vector[NativeApplication.MenuRow],keys: Set[NativeApplication.MenuKey]=Set.empty): Unit =
      NativeApplication.validateMenus(rows.asJava,keys.asJava)
    val root=new NativeApplication.MenuRow(0,1,0,0,"File",none,true,false)
    val item=new NativeApplication.MenuRow(1,2,1,7,"Save",none,true,false)
    intercept[IllegalArgumentException](check(Vector(root,item,item)))
    intercept[IllegalArgumentException](check(Vector(item)))
    intercept[IllegalArgumentException](check(Vector(root,item,new NativeApplication.MenuRow(1,3,1,7,"Again",none,true,false))))
    val deep=Vector.tabulate(34)(i => new NativeApplication.MenuRow(i,i+1,0,0,"sub",none,true,false))
    intercept[IllegalArgumentException](check(deep))
    intercept[IllegalArgumentException](check(Vector(root,new NativeApplication.MenuRow(1,2,1,7,"x"*100000,none,true,false))))
    intercept[IllegalArgumentException](check(Vector.empty,Vector.tabulate(769)(i => new NativeApplication.MenuKey(i.toString,1)).toSet))
    intercept[IllegalArgumentException](new NativeApplication.MenuKey("a\u0000b",0))
    intercept[IllegalArgumentException](new NativeApplication.MenuKey("\ud800",0))
    assertEquals(NativeApplication.liveApplications(),0);assertEquals(NativeWindow.liveWindows(),0)
  }
  test("native input keeps non-ASCII logical command keys separate from committed text") {
    val event=new NativeWindow.Event(true,0,100,100,100,100,1,1000+'界'.toInt,8,0,false,0,0,0,0,0,0,"",0)
    assertEquals(NativeInput.decode(event),Some(HostInput.KeyDown(Key.Character('界'),Mods.Control)))
  }
