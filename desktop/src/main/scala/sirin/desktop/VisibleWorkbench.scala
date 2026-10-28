package sirin.desktop

import sirin.*
import sirin.specimen.*

final class VisibleWorkbench(scope: Scope):
  val model=new Workbench(scope,DemoServices)
  val right=scope.cell(false)
  val count=scope.cell(0)
  val allow=scope.cell(true)
  val extra=scope.cell(false);val extraRef=scope.ref()
  val moveRef=scope.ref();val actionRef=scope.ref();val listRef=scope.ref()
  val leftOutlet=scope.ref();val rightOutlet=scope.ref()
  val move=scope.action[Unit]("panel.move","Move document")(_ => {right.set(!right.get)})
  val increment=scope.action[Unit]("count.increment","Activate",enabled=allow)(_ => {count.set(count.get+1)})
  val toggle=scope.action[Unit]("count.toggle","Toggle activation")(_ => {allow.set(!allow.get)})
  val panel=scope.reparent(GlobalKey("visible.editor"),Column(
    Text("Document").style(TextStyle.Heading).color(ColorRole.Accent),
    Text(model.document),
    Button(move).ref(moveRef),
    Text("The focused control keeps its identity when moved").style(TextStyle.Caption).color(ColorRole.Muted)
  ).spacing(10.lp))
  val view: View = Column(
    Row(Text("Sirin").style(TextStyle.Title).color(ColorRole.Accent),Spacer().grow,
      Text("JVM development host").style(TextStyle.Caption).color(ColorRole.Muted)),
    Text("Keyboard and pointer workbench").style(TextStyle.Heading),
    Row(Button(increment).primary.ref(actionRef),Button(toggle),Show(extra)(Button("New control").onPress(count.set(count.get+1)).ref(extraRef)),Text(count.map(n => s"Activated $n times"))).spacing(10.lp),
    Text("Tab / Shift-Tab • Enter / Space • wheel / Page Up / Page Down").style(TextStyle.Caption).color(ColorRole.Muted),
    Row(
      Scroll(VirtualRows(model.rows,28.lp,2)(record => Text(record.map(_.title)))).ref(listRef).grow,
      Row(Column(DockOutlet(leftOutlet)).width(180.lp),Column(DockOutlet(rightOutlet)).width(180.lp)).spacing(12.lp)
    ).spacing(20.lp).grow,
    panel.at(right.map(value => if value then rightOutlet else leftOutlet)),
    Text("office café   שלום   مرحبا").style(TextStyle.Body)
  ).spacing(10.lp).padding(20.lp)
