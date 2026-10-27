# Independent A-0b review and resolutions

Reviewer: fresh-context agent `api_review`, started with no conversation history
and the repository/design/corpus as inputs, 2026-10-27. Read-only review; the
reviewer ran no builds. Initial verdict: **A-0 cannot pass yet**. The initial
working-tree snapshot is identified by `docs/evidence/a0b-20261027/manifest.json`.

## Initial findings

| Finding | Severity | Resolution applied by main session |
|---|---|---|
| Universal `[V <: View]` modifiers cannot immutably reconstruct arbitrary final/singleton subtypes | P1 | Explicit invariant ViewRebuild capability. Supported facades provide it; custom authors opt in. Real custom final-view reconstruction test plus negative unsupported-subtype fixture. |
| Publication/effects versus last-Settled rollback boundaries conflict | P1 | Each transaction settles privately then publishes. Effects are separate follow-on transactions. A later failure retains the latest settled commit and cannot retract notifications. |
| Intermediate staged snapshot may falsely mark final text saved | P1 | Cell.snapshot returns the committed pair and refuses unequal staged writes. versioned reads are explicitly candidate/provisional during staging. Document uses snapshot. |
| Progressive commands cannot expose stale result metadata | P1 | CommandResult carries invocation identity/outcome/current to completion, scoped invocation observation and lastResult. |
| Arbitrary locally caught exceptions are invisible to a library | P1 | Poison guarantee applies to exceptions crossing runtime-owned boundaries; a wholly internal throw/catch is outside it. |
| Channel close versus no-loss/disposal contracts conflict | P2 | Graceful close rejects new offers and drains, cancellation/disposal explicitly abandons pending items; observable channel state and transactional capacity reservation order specified. |
| Repeated single node/revision is not proof of a cycle | P2 | Cycle requires dependency cycle or full deterministic working-state repeat; otherwise budget exhaustion. |
| Shared button/menu/shortcut refusal lacks feedback | P2 | Bounded shared lastRefusal, cleared on accepted dispatch; Document error UI reads it. |

A popup comment incorrectly included a sibling notes field in a header shortcut
scope; corrected. Public text/IME/measurement services remain explicitly Stage 2;
phase checks must reject graph mutation even through captured scope references.

## Re-review

The same fresh-context reviewer inspected each fix and returned: **A-0's
provisional authoring/API review can pass, conditional on compile and required
checks passing.** No remaining blocker among the eight findings. The main
session then verified `sbt testJVM testNative examples/compile`: exit 0,
7 JVM + 7 Native tests, and all 13 authoring sources compiled. The independent
reviewer did not run builds. The receipt is
`docs/evidence/a0b-20261027/independent-review-fixes.log`.

A-0 and A-0b are therefore complete at their provisional-authoring scope.
Engine conformance and Stage 1 exit require their own independent review.
Remaining obligations: snapshot refusal/subsequent writes; refusal-status and
channel reservation rollback; graceful drain versus abandonment; phase/thread/
scope enforcement; and complete public text/IME services.
No claim is made here of an implemented widget/runtime or Stage 1 completion.
