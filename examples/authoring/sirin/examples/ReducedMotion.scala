// A-0b program 12. Scope owns animation. Platform reduced-motion preference
// makes the same target immediate without writes or unnecessary animation
// frames. Offset is coherent across draw/hit/accessibility geometry; opacity
// changes paint only. Hidden/disposed content schedules no animation frames.
package sirin.examples.motion

import sirin.*

val ReducedMotion = Component[Unit]("ReducedMotion") { (scope, _) =>
  val expanded = scope.cell(false)
  val position = scope.animate(expanded.map(e => if e then 180.0 else 0.0))
  val offset = position.map(x => Point(x.lp, Lp.zero))
  val alpha = scope.animate(expanded.map(e => if e then 1.0 else 0.5))
  val toggle = scope.action[Unit]("motion.toggle", "Toggle target")(_ => expanded.update(!_))
  Column(
    Button(toggle),
    Button("Moving target").command(toggle).offset(offset).opacity(alpha),
    Show(expanded)(Text("Motion preference changes presentation, never application state"))
  )
}
