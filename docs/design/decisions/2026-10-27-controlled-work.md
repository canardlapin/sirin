# Controlled tasks and commands

Scope: the authorized C1.6a/C1.7 controlled headless tranche based on `397e20f`.
VISION §5.1/§5.4 remain authoritative. Progress channels, deadlines, the real worker
adapter, foreign producers, graphics/native work and full Stage 1 remain separate.

## Decisions

- The finite-capacity controlled scheduler separates reservation/publication,
  execution and terminal delivery. Its explicit controls run outside transactions
  and read-only phases. Work executes under a worker guard without UI ownership;
  this adapter uses no real threads. External work never runs speculatively.
- Scope/action/command construction is transactional and allowed in a component's
  construction subtree. Same-command reentrant dispatch refuses before start.
  General and argument enablement recheck staged state under read-only guards;
  failed enablement disables and reports. withArg rereads its staged argument.
- Dispatch uses an implicit refusal attempt. Refused validation/start cannot erase
  caller writes outside it. Reservations, status, generations, queues, cancellation
  requests and starts restore on refusal; defects poison the outer transaction.
  Invocation IDs are monotonic and never reused; escaped provisional handles fail.
- running counts accepted nonterminal jobs, including serial waiters. Serial
  capacity bounds waiters beyond one executing slot. Global capacity bounds all
  reserved work, including completed tickets awaiting delivery. Full queues refuse.
- Concurrency and acceptance are independent. Soft Supersede requests cancellation
  but policy decides acceptance of actual results. Explicit Invocation.cancel
  suppresses application reconciliation. A cancelled executing serial slot stays
  occupied until actual termination and terminal delivery; cancelled queued work
  can terminalize without execution. Cancellation does not undo external writes.
- Every terminal invocation delivers its actual outcome to live explicit observers,
  including outcomes suppressed by acceptance. Only accepted outcomes update the
  command's single lastResult. lastRefusal is separate and clears on the next
  accepted dispatch. Asynchronous current means latest accepted dispatch at
  terminal delivery; synchronous actions are current at immediate execution time.
- SnapshotAssociated returns the captured S to application reconciliation. The
  kernel cannot inspect arbitrary snapshots. Save markers use explicit monotonic
  revision reconciliation; serial execution prevents older saves overtaking newer.
- Terminal bookkeeping commits before the separate owner-checked application
  completion transaction. Refusal/defect in that callback cannot retain resources,
  block serial work or rerun external work. If bookkeeping itself fails, the
  completed ticket remains for explicit redelivery without execution again.
- Latest Pending/failure state participates in structural settling. Its worker
  factory and reservation run once after the final fixed point; tentative inputs
  never launch. Final-equal input restores the staged baseline state. Input epochs
  advance once per committed unequal input, including failure/recovery. LatestOnly
  drops obsolete results; Progressive shows stale results while newer input stays
  pending and never regresses accepted epochs. Capacity waiting retains the input,
  not a Work closure, and resumes after committed retirement/disposal without polling.
- Disposing the job owner retires work and blocks delivery. Disposing an observing
  scope removes its observation and leaves workspace-owned work alive. Retained
  terminal handles own data, not scheduler work or disposed observer cell sets.

## Independent review

The continuing fresh-context api_review reviewer read contracts, implementation,
regressions and the command/latest oracles without editing or running builds.
Main session retained kernel semantics and ran verification.

| Finding | Resolution / regression |
|---|---|
| Latest dispatched from intermediate structural passes | Split candidate-state reconciliation from final preparation. Sum source/Inputs diamond launches only final value under capacity one; difference diamond restores its baseline and neither launches nor cancels. |
| Reentrant start bypassed RefuseWhileRunning/Serial occupancy | Reject same-command reentrant dispatch before invoking start; both policies have direct nested-dispatch controls. |
| Unrequested InterruptedException left work runnable | Terminalize as Failed; cancelled interruption becomes Cancelled. A regression refuses second execution and delivers one failure. |
| Synchronous current wording disagreed with multiple same-txn actions | Define it at immediate execution time and test both published handles. Asynchronous delivery semantics are unchanged. |
| Command model omitted disposal; latest liveness lacked an independent model | Add all-policy fixed command disposal/refusal/cancellation scripts and separate immutable LatestModel with random run/deliver order, failures, saturation, rollback and fixed eventual-completion/disposal cases. |
| Latest outcome metadata needed failed/cancelled worker coverage | Add deterministic error/shown/pending/stale controls for both latest policies and successful recovery. |

Final source review found no remaining blocker in the bounded implementation and
models, conditional on closing the named regressions and final JVM/Native checks.
See `docs/evidence/c1-work-20261027.md` for the final evidence and explicit limits.
