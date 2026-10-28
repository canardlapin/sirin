package sirin.engine

enum CauseKind:
  case Transaction, Event, Deadline, Progress, Completion, Effect, Driver

enum TraceOutcome:
  case Committed, Refused, Failed, CycleDetected, BudgetExceeded

/** Numeric metadata only: no payload, scope, signal, exception or callback references. */
final case class TraceRecord(id: Long, parent: Option[Long], cause: CauseKind, outcome: TraceOutcome,
    graphPasses: Int, structuralPasses: Int, units: Int = 0)
final case class DriverSnapshot(activeTurns: Long, deliveredUnits: Long, transactions: Long, parks: Long,
    pendingEvents: Int, pendingEffects: Int, pendingDeadlines: Int, nextDeadlineNanos: Option[Long], traceEntries: Int)
final case class DriverTurn(executed: Int, ready: Boolean, nextDeadlineNanos: Option[Long],
    failures: Vector[CommitResult[Unit]], budgetExceeded: Boolean)
