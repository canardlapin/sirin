package sirin.desktop

import sirin.*
import sirin.specimen.*

/** Public application descriptions. Controls and native input follow separately. */
final class VisibleWorkbench(scope: Scope):
  val model=new Workbench(scope,DemoServices)
  val right=scope.cell(false)
  val leftOutlet=scope.ref();val rightOutlet=scope.ref()
  val panel=scope.reparent(GlobalKey("visible.editor"),Column(
    Text("Document").style(TextStyle.Heading).color(ColorRole.Accent),
    Text(model.document),
    Text("Draft and work stay owned while this panel moves").style(TextStyle.Caption).color(ColorRole.Muted)
  ).spacing(10.lp))
  val view: View = Column(
    Row(Text("Sirin").style(TextStyle.Title).color(ColorRole.Accent),Spacer().grow,
      Text("JVM development host").style(TextStyle.Caption).color(ColorRole.Muted)),
    Text("Committed layout and shared text").style(TextStyle.Heading),
    Text("office café   שלום   مرحبا").style(TextStyle.Body),
    Row(
      Scroll(VirtualRows(model.rows,28.lp,2)(record => Text(record.map(_.title)))).grow,
      Column(DockOutlet(leftOutlet),DockOutlet(rightOutlet)).width(200.lp)
    ).spacing(20.lp).grow,
    panel.at(right.map(value => if value then rightOutlet else leftOutlet)),
    Text(model.dirty.map(dirty => if dirty then "Document modified" else "Document saved")).style(TextStyle.Caption).color(ColorRole.Muted)
  ).spacing(10.lp).padding(20.lp)
