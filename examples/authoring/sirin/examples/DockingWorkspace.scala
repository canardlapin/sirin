// A-0b program 8. Reparent explicitly keeps the dirty panel in this scope;
// outlets only place it. Ordinary owner change through Switch remounts its
// comparison panel. Disposal retires both and rejects stale callbacks.
package sirin.examples.docking

import sirin.*

val DraftPanel = Component[String]("DraftPanel") { (scope, title) =>
  val draft = scope.cell("")
  Column(Text(title), TextField.bind(draft).label("Local draft"))
}

val DockingWorkspace = Component[Unit]("DockingWorkspace") { (scope, _) =>
  val right = scope.cell(false)
  val leftOutlet = scope.ref()
  val rightOutlet = scope.ref()
  val target = right.map(r => if r then rightOutlet else leftOutlet)
  val retained = scope.reparent(GlobalKey("workspace.draft"), DraftPanel("Retained draft"))
  val move: Command[Unit, Unit] = scope.action("dock.move", "Move panel") { _ => right.update(!_) }
  Column(
    Button(move),
    Row(DockOutlet(leftOutlet).grow, DockOutlet(rightOutlet).grow),
    retained.at(target),
    // Each branch owns its placement. This comparison intentionally resets.
    Switch(right) { onRight =>
      if onRight then Row(Spacer().grow, DraftPanel("Ordinary move resets"))
      else Row(DraftPanel("Ordinary move resets"), Spacer().grow)
    }
  )
}
