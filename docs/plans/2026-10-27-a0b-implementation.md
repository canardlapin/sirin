# A-0b implementation and review checklist

Status: **complete at provisional-authoring scope after independent review and fixes**.
The owner accepted the package on 2026-10-27 in
`docs/design/decisions/2026-10-27-a0-recommendations.md`. The execution record below distinguishes completed work from open gates. Existing skeleton bodies remain
explicitly unimplemented. Existing law tests must never be replaced by stubs.

## Execution record

- Accepted package recorded in Mote; VISION and original ruling status updated.
- Provisional interfaces revised and six existing programs adapted; six missing
  positive programs added. All 24 review items have dispositions in `examples/REVIEW.md`.
- Seven negative compiler assertions have positive controls. Clean corpus
  compilation and 39 test executions passed (6 JVM, 6 Native, 14 ABI, 13 mailbox).
  Pure revision/capacity/task-projection checks are separate from unimplemented
  Stage 1 laws. Receipts: `docs/evidence/a0b-20261027.md`.
- **Complete:** independent fresh-context review found eight gaps; fixes passed
  re-review and 7 JVM + 7 Native checks plus authoring compilation. A-0/A-0b
  close at provisional-authoring scope. Details in the independent review record.
- **Open:** idle-host runtime repeat; preserve the upstream candidate and all
  failed pause evidence. Acceptance did not adopt the runtime default.

The following checklist records the intended scope; the review records actual
API choices and deliberate deferrals, including four-arity mapN and Stage 2
text/IME requirements.

## Ordered implementation slices

### 1. Authoritative contract

Update the rulings' status and VISION together after acceptance. Integrate
R1/R6 in §5.1, R3/R7/R8 in lifetime/identity rules, R9–R15 in frame/input and
presentation rules, and R2/R16 in §5.4. Remove conflicting old prose, including
"no savepoints", the conflated Serial freshness case, and any suggestion that
unaccepted rulings already supersede the authoritative text.

Record the owner's accepted package and any exceptions in Mote. Neither
passing compilation nor this checklist substitutes for acceptance.

### 2. Core authoring interfaces and existing corpus

| Files | Planned change | Existing program that must improve |
|---|---|---|
| `Reactive.scala` | attempt/refusal result, explicit read-only committed capability; document per-cell revisions and effect/failed-value rules | Validated form reveals errors while refusing; no throwing parse for normal validation |
| `Task.scala`, `Command.scala`, `Scope.scala` | separate Concurrency/Acceptance with presets; narrow input-driven policy type; explicit status builder; bounded queues and deterministic terminal lifetime | Document save serializes snapshots; submitting form refuses while busy without a duplicate boolean |
| `Component.scala`, `View.scala` | stable withDeps definition, Inputs-driven ForEach, explicit keyed Switch; Unit placement helper | Editable rows preserve drafts through replacement/reorder without component factories |
| `View.scala` | type-preserving modifiers; deliberate refusal handling; argument-sensitive activation and popup dismissal | TextField modifiers keep control methods; refused rename leaves the editor open |
| `Element.scala`, `Reactive.scala`, `Theme.scala` | per-phase inputs, Committed reads constrained to declared inputs, separate Presentation channel and reduced-motion behavior | Diagram lift avoids rebuilding semantic/model signals; hit and visible transforms stay coherent |
| `Collection.scala` | fixed-height realization contract, keyed ranges/deltas, state-preserving explicit reparent interface | New virtualization and docking programs |
| `App.scala` | keyed window collection instead of a fixed Seq | New multi-window program with application/document/window ownership |
| `Task.scala`, `Scope.scala` | typed progress channel, delivery/capacity/closure shape, cancellation-aware producer contract | New streaming program covers coalescing and ordered pressure |
| `Neg.scala` | reject transaction capture, writes from read-only phases, unsupported latest policies, Presentation used as Signal, and lost control refinement | Compiler-based checks; no fabricated runtime passes |

Structural and phase APIs will evolve together with examples. Public text,
IME, hover/cursor and active-descendant capabilities must be represented or
explicitly retained as Stage 2 requirements; A-0b cannot claim a public-only
TextField implementation merely because placeholder signatures compile.

### 3. Missing authoring programs

| Proposed program | Required authoring scenario | Later executable law (not claimed by compilation) |
|---|---|---|
| `VirtualizedRows.scala` | Fixed-height range/overscan, selection survives unrealization, keyed replacement before viewport | Realized count bounded by visible plus overscan; no full scan |
| `DockingWorkspace.scala` | Move a dirty panel across owners with GlobalKey and explicit reparent; show ordinary-move reset separately | Explicit reparent preserves draft; ordinary owner change resets; disposal rejects stale callbacks |
| `StreamingImport.scala` | Coalesced progress plus ordered records; finite capacity, cancellation and closed owner | No silent record loss; bounded memory; blocked producer wakes on cancel/dispose |
| `FailedBinding.scala` | First-value defect, defect after success, explicit fallback/retry, failed command enablement | Same observable failure policy across build modes; disabled failed enablement; recovery |
| `MultiWindowDocument.scala` | Multiple keyed windows observe one workspace document; closing one view does not cancel workspace save | Correct window/document lifetime separation and serial save ordering |
| `ReducedMotion.scala` | Same target under normal/reduced motion; presentation cannot enter Signal composition | Reduced motion reaches target without animation-frame train; interaction geometry matches display |

These programs supplement the six existing positive programs and negative
compile cases. Each uses public APIs only and states owner, update policy,
refusal/failure path, and teardown behavior in its source commentary.

## Disposition proposed for all 24 API awkwardnesses

The numbered entries refer to `examples/REVIEW.md`. These are implementation
recommendations; accepted semantic rules take precedence.

| Review item | Proposed disposition |
|---|---|
| 1 command busy self-reference | Adopt explicit read-only status builder; remove duplicate submitting cells |
| 2 static dependencies/remount traps | Adopt stable Component.withDeps; explicitly define changed-dependency remount behavior |
| 3 Unit props ceremony | Add a Unit placement helper; avoid a second component identity model |
| 4 conflated work policies | Adopt split policies and named presets; bounded Serial preserves every accepted queued save. Defer QueueLatest until an example requires dropping queued work |
| 5 one state invalidates every phase | Adopt phase-specific inputs and Presentation; keep color/shadow-only changes paint-only |
| 6 argument-sensitive enablement | Add enabledFor / bound-argument dispatch checks so row actions cannot advertise impossible moves |
| 7 generic modifiers lose subtype | Adopt type-preserving public modifiers; verify TextField.ref(...).onCommit(...) compiles |
| 8 clickable rows impersonate buttons | Add public activation behavior with explicit row semantics and keyboard/focus support |
| 9 ignored dispatch refusal | Keep the typed result; examples handle Refused and close editors only after acceptance. Explicit ignore is allowed only for deliberate no-op behavior |
| 10 literal/signal overloads | Keep small explicit overloads in v0; defer generic union Prop until it resolves real corpus gaps without Signal-of-Signal ambiguity |
| 11 cell lenses | Defer zoom until projected revision/equality semantics have a clear executable contract; current split cells remain supported and documented |
| 12 pattern-heavy task state | Provide derived shown/pending/stale/error helpers; keep the full state available. Preserve dependency/input distinction |
| 13 captured transaction in Work | Keep compile-time guard and authoritative runtime ownership check; improve diagnostic wording |
| 14 four async entry points | Keep input-driven latest and dispatched command; synchronous action shares command dispatch. Defer standalone launcher unless a corpus program needs it |
| 15 reveal errors and refuse | Express with caller writes outside command attempt; no extra hidden transaction boundary |
| 16 dismissal reason/veto | Add DismissReason and explicit dismissal intent; keep default open=false for simple popups |
| 17 shared NodeRef and semantic parts | Keep one ref concept, add part-aware semantic targets, specify attachment timing and duplicate-attachment diagnostics |
| 18 custom element focus parts | Add public active-descendant/focused-part shape and focus-ring capability; runtime implementation belongs to Stage 2 |
| 19 theme as implicit dependency | Treat theme/font metrics as declared global phase epochs; record invalidation cause rather than hiding it |
| 20 text measurement | Add shared TextLayout/TextServices capability shape; same result feeds draw/caret/hit/semantics. Do not claim shaping implemented |
| 21 index-only custom layout | Add named slots while retaining indexed homogeneous children; unplaced-child rules stay explicit |
| 22 mapN arity | Add general tuple composition only if implementation stays readable and compiler diagnostics remain useful; otherwise add the concrete missing arities and record the limit |
| 23 optional-content verbosity | Add Show as ordinary keyed/switch sugar without a new lifetime model |
| 24 fixed window sequence | Adopt keyed Windows with explicit scope and close behavior |

## Validation and exit

- Compile core on JVM and Native, all authoring programs, and negative cases.
- Run `sbt testJVM testNative`; keep existing ABI/mailbox checks intact.
- Audit examples for manual duplicated status, accidentally recreated
  definitions, implicit stale fallback, and ignored refusal outcomes.
- Re-run the authoring checklist: clear ownership, no unnecessary context,
  no mandatory manual subscription cleanup, no accidental remount, no private
  hooks. Record remaining failures rather than declaring a clean API early.
- Obtain the required independent fresh-context review. This checklist and
  the current session's own review do not satisfy that exit criterion.
- A-0b completion does not implement or qualify the Stage 1 behavioral laws.

## Runtime work alongside A-0b

Use the preserved full upstream `fa4a9e6` executable and the unchanged 600 s,
4-worker, 1 ms event, 128 MiB-cap protocol. Run `check-idle.py` first and save
its raw samples. Its thresholds are a conservative scheduling preflight,
not a substitute for the actual 50 ms assertions or proof that the machine
remained idle throughout the run. Do not stop unrelated workloads.

When a quiet window or alternate suitable host is available, repeat the soak.
If the tail persists, add diagnostic timestamp correlation in a separate
instrumented build and compare with the uninstrumented candidate. Preserve
failures; do not relax the threshold or promote the runtime pin by default.
