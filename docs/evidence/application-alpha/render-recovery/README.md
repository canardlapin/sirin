# Explicit recovery from a refused window render

A current nonfatal render outcome now publishes a bounded transactional failure
receipt to its mounted window instance. The ordinary content stays mounted; an
explicit recovery holder supplies retry and reviewed close under the same owner.
Only the selected holder is captured for input/paint/semantics. Separate normal
and recovery controllers preserve normal editor history. Queued input that cannot
be applied and cancelled provisional composition have explicit receipts. Old
activation keys cannot act on the recovery buttons. Fresh known menu shortcuts
remain eligible after coalesced receipt publication. OS/quit close cannot dismiss
unreviewed input, and reviewed close still invokes the application dirty guard.
Application source removal retains its existing explicit ownership semantics.

Window.renderFailureContent supports application-specific recovery controls. The
workbench uses a data-independent dirty-close/quit confirmation panel with fresh
revision authority. A custom recovery render can escalate once to the minimal
panel; repeated minimal failure parks automatic submissions. Stale outcomes and
reused source keys cannot publish to another mounted window. Last-eight records
contain bounded values, not frames/exceptions/owners. Current-generation retry
invalidates old frames even if the model payload is identical.

Full required checks: **394 JVM +394 Native +89 desktop**, plus examples
compilation, with zero frozen source mismatches (`full-checks-1/`). Seven new shared
cases cover transactional rollback, retained identities/drafts, reviewed guarded
close, bounded history/stale/replacement/disposal behavior, custom recovery
suppression, declared recovery menu capture, coalesced unapplied input and safe
workbench quit authority. Two desktop cases use the same real renderer to observe
the original paragraph refusal, render the recovery UI, operate a healthy second
view, clear the diagnostic, retry and restore undo; they also check generations,
input epochs, known shortcuts and bounded escalation. No native window opens.

`refused.frame` and `recovery.frame` are trusted internally generated FrameRequest
fixtures, not an external document import format. `recovery.png` is the actual
headless same-budget recovery image, not native UI or owner visual approval. The
underlying unsupported-emoji scene still refuses; recovery is not a rendering
pass. Paragraph, wrapping, pixel work and GPU frame sources are unchanged from
base main (`unchanged-rendering-sources.json`). No budget was enlarged.

## Failed attempts and exact dependency control

Initial test compilation had a CommandId/String comparison (`shared-2/`). The
first contract fixture read a committed snapshot during write dispatch and
correctly failed the read-only-phase guard (`contracts-1/`). Its successor exposed
a missing explicit keyboard modifier (`contracts-2/`). The real capture fixture
then found undeclared recovery menu bindings (`contracts-3/` and
`routing-diagnostic/`), after failure publication. Those failures are retained.
`undeclared-menu-control/` runs the exact earlier Reconciliation source against
an identical shared capture test and fails; `declared-menu-successor/` passes the
same test after declaring the bindings. Source/test hashes and logs are frozen.
Later input-epoch/shortcut successors remain separate, including their sources.

Base main1570c9d has nine successful CI jobs (`field-feedback-main-ci.json`); that
is not CI for this uncommitted successor. Existing SirinWorkbench/SirinAlpha
launch candidates remain unchanged and unlaunched. Current native client checks
ultimately need a reviewed successor image. Capture/initialization failures,
transitions back from recovery with real event sources, device lifecycle, native
menus/IME/VoiceOver/drag/focus/modality and visual acceptance remain unqualified.
Complex paragraph/large-surface rendering, Native UI delivery, diagram editor,
inspector, independent authoring/ScalaFX comparison and the original long GC/event
gate remain required. No numbered alpha deliverable is closed.

Verify with `python3 -B docs/evidence/application-alpha/render-recovery/verify.py`;
`--sources` compares current tested files and unchanged rendering sources.
