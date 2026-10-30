# Desktop application alpha: six-part programme

Owner goal: “let's work diligently on1–6.” All six deliverables below are required;
this plan does not redefine completion around the first implementation or passing
slice. Authority: VISION, especially1.1,5,6,8 and11. Base main5c73273. Work is
isolated in `/Users/bbuchsbaum/code/scala/sirin-alpha`; preserve the dirty original
checkout and prior frozen receipts. The existing Native/input qualifications
remain open and do not prevent independent application work.

## Required deliverables and acceptance

| Item | Required end state | Authoritative acceptance evidence |
| --- | --- | --- |
| 1 Public application platform | Implement Application.run/Window/Windows, application/document/window ownership, native menus/shortcuts/dialogs, keyed multiple windows, orderly guarded close, main-thread host and pointer-only adapter boundaries | Real public-API app; identity/lifetime/menu/close/error tests on JVM and Native; separate actual host receipts; no remaining launch skeletons or private specimen adaptation |
| 2 Complete persistent workbench | Visible search/select/validate/edit, retained drafts through refresh/reorder/outlet move, save r17 while editing r18, bounded import/cancel, persistence/reopen/dirty-close/conflict/recoverable failure feedback | Entire keyboard-driven workflow, revision/staleness/failure tests, actual reopen and cancellation, external-modification controls, no publication after close or leaked owners |
| 3 Coherent toolkit | Finish required controls, navigation/focus/selection/scroll/popups/dialogs/enabled/error/loading states; typography/spacing/light-dark/scaling | Shared control contracts and interaction tests; reference app operation; owner V-0 visual approval; actual platform input/assistive-client receipts |
| 4 Public diagram editor | Complete custom-element layout/paint/hit/semantics seam; nodes/edges/selection/drag/pan/zoom/undo through public APIs | Complete usable editor, public client compilation, command/geometry/undo/resource checks, real pointer operation and meaningful dense-scene acceptance; no browser-parity inference |
| 5 Developer diagnostics | Bounded inspector for identities/state/dependencies/invalidation/refused commands/work/render/cache; usable starter and build/package docs | Diagnose real application failures, prove bounded retention/overhead and post-close release; clean-checkout build/package; independent feature addition using documented public APIs |
| 6 Alpha acceptance | Green required CI; original long GC/event50ms qualification; actual IME/VoiceOver/drag/keyboard/lifecycle/packaging; fully interactive matched ScalaFX workbench, independent authoring review and owner approval | Exact commit/platform results and raw receipts, complete matched workflows/feature changes, visual approval and explicit supported matrix; every missing/manual result remains incomplete |

Alpha exit: another Scala developer can clone/build/package, complete the full
keyboard workflow, reopen saved documents, diagnose a refused operation and add
a feature through documented APIs. No failed or missing required gate is silently
converted to a pass. Production runtime adoption, Native shipping and platform
support each need their actual evidence.

## Execution and boundaries

Start by reproducing Linux Native CI40974637787, then implement the public shell
and wire the visible workbench. Controls, diagram extension and inspector are
built against actual application requirements. Preserve all six tracks in Mote.
JVM and Native shared-law checks remain required for changes. Kernel transaction,
identity/lifetime/scheduling or command-policy changes require an explicit design
update before implementation; fixes preserving specified semantics are reported
as such. Native callbacks queue native-owned facts and never invoke Scala on a
foreign thread. Public widgets reuse existing Cell/UiTxn/Command/Scope contracts.

## CI baseline and first investigation

Public main5c73273 has7 successful jobs and1 failed job. macOS and Windows core
JVM/Native checks pass; Linux Native ReconciliationAgreementSuite fails with
ClassCastException: ArrayBuffer$$Lambda$1 cannot be cast to IterableOnce.
Failing seed: l8Dl-ZJbwAFzUVsay_UCl1zuWxTHh-oHefe7es6XzbN=.
Freeze the original public log and exact40-step program. Add deterministic replay
without weakening the existing300-case randomized property. Capture the exception
stack and identify its source; a passing rerun alone is not a fix. Linux runner
confirmation is mandatory for closing this issue. Retain all attempted variants.
