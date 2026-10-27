// A-0b program 10. First mount starts with a defect; recover, fail again after
// success, and recover again. Explicit safe fallback is identical across build
// modes. The raw binding exercises the default unavailable/error boundary.
// Failed enablement disables + reports. All state/effects retire with scope.
package sirin.examples.failure

import sirin.*

val FailedBinding = Component[Unit]("FailedBinding") { (scope, _) =>
  val broken = scope.cell(true)
  val raw: Signal[String] = broken.map { fail =>
    if fail then throw new IllegalStateException("diagnostic detail never shown in release")
    "Recovered value"
  }
  val safe = raw.attempt.map {
    case Value.Ok(value) => value
    case Value.Failed(_) => "Value unavailable. Use Recover to retry."
  }
  val failedEnablement: Signal[Boolean] = raw.map(_.nonEmpty)
  val gated = scope.action[Unit]("failure.gated", "Use value", enabled = failedEnablement)(_ => ())
  val recover = scope.action[Unit]("failure.recover", "Recover")(_ => broken.set(false))
  val failAgain = scope.action[Unit]("failure.again", "Fail after success")(_ => broken.set(true))
  Column(Text(raw), Text(safe), Button(gated), Button(recover), Button(failAgain))
}
