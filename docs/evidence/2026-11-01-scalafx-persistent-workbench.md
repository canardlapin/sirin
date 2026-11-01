# ScalaFX persistent workbench candidate

Parent revision: `ae96019`, in the owned worktree
`/private/tmp/sirin-workbench-20261031`. Mote issue:
`bd-01M65371R4RTNF9M92P2DCGHX8`. This adds a buildable, packaged persistent
ScalaFX application for the matched workflow. It has not yet been operated in a
visible window and establishes no Sirin or ScalaFX usability advantage.

## What changed

The independent build module now has a strict Sirin v1 storage adapter,
application-owned drafts, asynchronous JavaFX Task work, native controls,
retained editor panes, detached windows, import review/results and guarded
close/reload/quit decisions. It uses ScalaFX 26.0.0-R38 and JavaFX 26 with the
selected JDK 25 runtime. The package contains no Sirin runtime dependency.
The storage contract follows Sirin's adapter, so independent execution does not
mean independent authorship.

The application exposes bounded queues and refusal messages. Source lifecycle
checks guard late results and stale consent; a successful older save advances
only its captured revision. Search, pane movement and refresh retain draft
identity. Import cancellation retains completed files and records delivered and
discarded receipts. Native menu, quit/reopen and window behavior are implemented
but remain unqualified until observed in a real window.

## Verification

The final `final-checks.log` records successful `testJVM` (470), `testNative`
(470), `scalafxWorkbench/test` (16), and
`scalafxWorkbench/scalafxWorkbenchPackage`. The process exited zero. Sirin's
desktop implementation was not changed in this tranche, so its suite was not
repeated here.

Six storage tests verify byte-identical fixture compatibility, strict decoding,
identity and bounds, own-session stamp advancement, stale/external writers,
no-overwrite create and precommit cancellation. Ten workspace/control tests
exercise retained drafts, older saves with newer edits, reload/close consent,
queue limits, retirement, frozen import changes and partial results, cancellation
with queued receipts, native text-control whole-edit refusal and undo after
reparenting, plus full offscreen scene layouts at 640 by 400 and 1000 by 720.
Compact invalid-title layout is checked separately in the same layout test.

The scene checks assert actual root dimensions and visible control bounds, and
produce PNGs. The compact normal and invalid states were visually inspected.
These tests start the JavaFX thread but never call Stage.show. They do not prove
native menu routing, OS quit/reopen, physical input, IME, VoiceOver, smooth resize
or application acceptance.

Retained failures informed the final checks:

- Initial worker test hooks used assertions inside blocking work and prevented
  the test's release from progressing. Explicit timeout failures retain the same
  ten-second bound. Cancellation checks explicitly catch InterruptedException.
- Initial captures could crop a larger scene while passing a pixel-only check.
  Those images are preserved. The corrected check sets scene dimensions and
  verifies visible control bounds; compact editor minimums and empty feedback
  layout were repaired.
- A new regression caught a stale external-change notice after a matching disk
  stamp returned. Disk state now has a separate notice that refresh clears when
  appropriate, while external deletion still retains the draft with a warning.
- Initial JavaFX classpath configuration warned that the setup was unsupported.
  Tests and packages now use the pinned module path. A Scala LazyVals Unsafe
  deprecation warning remains; it is not reported as an application failure.

## Package and artifacts

Artifacts are in `/private/tmp/sirin-scalafx-workbench-20261101`: compilation and
test logs, original and corrected images, check results and package receipts.
The isolated app is
`/private/tmp/sirin-scalafx-acceptance-20261101/ScalaFXWorkbench.app`; its fixed
arguments select the sibling `documents` directory. Deep strict signature
verification and module validation through its bundled runtime both passed.
No window was launched by packaging or validation.

The starting synthetic fixture has SHA-256
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
The packaged workbench JAR has SHA-256
`6b0a848a1c19e09224efae3bf02d4d84dbe0d47a7b9f1f49b84d0cf8da449acb`.
`package-receipt.json` records the full application dependency/config hashes.
The frozen manifest covers 36 logs, images, receipts and source snapshots; all
entries were rehashed successfully. Its SHA-256 is
`141c309163560208007a8dfef024d3f2a00e37c61018b1890e43eaa99a663bc8`.
The evidence note itself is excluded to avoid a self-referential digest.
Launch confirmation for this separate app has been requested and remains
pending. The original shared checkout's tracked diff is unchanged, with SHA-256
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.

The [paired window plan](../plans/2026-11-01-matched-persistent-workbench.md)
defines the next task sequence. Actual paired operation, presentation timing,
graphics-rich workloads, independent authoring review and owner approval remain
open. Scala Native runtime qualification and delivery remain separate required
work; passing 470 shared Native tests does not provide a Native desktop host.
