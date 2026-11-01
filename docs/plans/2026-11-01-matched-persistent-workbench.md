# Matched persistent workbench window acceptance

This plan governs the first paired visible session. It was written after the
ScalaFX model and offscreen checks; it does not retroactively predeclare them.
The parent goal remains the
[usable persistent workbench](2026-10-31-workbench-acceptance.md), and VISION
§1.1 still requires a broader matched comparison and owner review.

## Inputs and execution

Use separately copied synthetic documents. The initial document is
`3a07f5e5-a5e1-476e-8f18-a6d37a5d6075`, titled `Acceptance Alpha`, with body
`Workbench acceptance: saved body 2026-10-31.`. Its Sirin v1 fixture SHA-256 is
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
Record the exact app, source revision, configuration and starting directory
hashes for each run. Preserve failed attempts and label repairs separately.

The prepared ScalaFX application is
`/private/tmp/sirin-scalafx-acceptance-20261101/ScalaFXWorkbench.app`, using its
sibling `documents` directory. A Sirin comparison must use an uninstrumented
production package and a separate copy of the fixture. The previously approved
Sirin popup diagnostic with a 200 ms rendering delay cannot support a fair
responsiveness comparison. Approval of that app is not approval of a new app.

Record whether each task used computer automation or a person, native keyboard
input or accessibility actions, and the actual presentation path. Computer
automation does not establish physical trackpad, IME or VoiceOver usability.

## Equivalent visible tasks

| Task | Required observation |
|---|---|
| Persistence | Open Alpha, edit title and body, save, quit, relaunch and verify exact file content |
| Collection | Create two additional documents; filter, reorder and refresh while retaining the exact unsaved draft |
| Editing | Verify whole-edit refusal at the same bounds, visible validation and undo after moving the editor |
| Windows | Detach the selected editor, edit and save there, then return it without losing the draft |
| Async save | Keep editing after a submitted save; an older completion must not clear newer edits |
| Conflict recovery | Replace a synthetic file externally, observe refusal, save a copy or explicitly reload |
| Import | Review frozen sources, run a mixed-success import and inspect receipts; separately observe cancellation during work |
| Lifetime | Close all windows, reopen retained drafts, then cancel dirty Quit with Escape and type immediately |
| Native input | Separately qualify real composition/commit/cancel, pointer/trackpad and VoiceOver navigation |

New document UUIDs may differ. Match the content, operations, state and refusal
semantics, rather than requiring random identifiers to coincide. If an operation
finishes before cancellation can interrupt it, record that limit; do not call it
an in-flight cancellation pass. Any controlled-latency follow-up must disclose
the wrapper on both sides.

## Comparison limits

The ScalaFX implementation uses two general workers with eight waiting tasks,
one save worker with 96 waiting tasks and a per-document save capacity of three.
Import receipt capacity is two with up to eight drained per pulse. Record the
corresponding Sirin policies when comparing responsiveness or backlog behavior.

No input-to-present budget, sampling schedule or paired latency measurement has
yet been accepted. Freeze those before collecting comparative timings, and state
readback/upload behavior. Offscreen snapshots are layout checks, not latency
measurements. No usability or performance winner follows from test counts.

The graphics-rich specimen, independent feature/discovery/repair exercises,
complete visual review, Native delivery-order decision and owner approval remain
separate requirements. This bounded task sequence cannot close Alpha6 alone.
