package sirin.specimen

import sirin.*

/** Public application code; host viewport and snapshots belong to the driver. */
final class StructuralWorkbench(scope: Scope):
  val rows = scope.keyed(0 until 1000)(identity)
  val minimum = scope.cell(0)
  val filtered = rows.filterBy(minimum)((q, value) => value >= q)
  val right = scope.cell(false)
  val leftOutlet = scope.ref()
  val rightOutlet = scope.ref()
  val draft = scope.cell("")
  val move = scope.action("editor.move", "Move editor") { (_: Unit) => right.update(!_) }
  val retained = scope.reparent(GlobalKey("structural.editor"), View.empty)
  val row = Component[Int]("record")((_, _) => View.empty)
  val view: View = Column(DockOutlet(leftOutlet), DockOutlet(rightOutlet),
    retained.at(right.map(value => if value then rightOutlet else leftOutlet)),
    VirtualRows(filtered, 28.lp, 2)(input => row(input)))
