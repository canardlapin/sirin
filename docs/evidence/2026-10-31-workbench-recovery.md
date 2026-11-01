# Persistent workbench recovery and import review

Scope: the owned workbench branch on parent `6a2d3c2`, using the approved
isolated application and synthetic documents under
`/private/tmp/sirin-workbench-acceptance-20261031`. The decisions are recorded in
[the import feedback note](../design/decisions/2026-10-31-import-review-feedback.md).
This is macOS/JVM packaged-workflow evidence with shared Scala Native regression
checks. It is not Native application-provider qualification.

## Real-window recovery before the repairs

An open document was edited locally, then its file was replaced by an external
writer. Save displayed the conflict message, kept the local draft and left the
external file byte-for-byte unchanged. Save a copy persisted the exact local
draft under a new identity while retaining the original draft. Reload's Keep
editing decision retained the draft; accepted Reload displayed the external
content. Both versions are archived independently.

These checks also found misleading feedback: successful Save a copy retained
its temporary Saving message, and a missing import directory's error appeared
only in the main window. A reviewed import still allowed Start after its source
field changed. The view did not identify which directory had been reviewed.

## Repairs and package checks

Successful copy creation clears the transient notice. Import review errors now
appear beside Source directory, with native AX Help on that field. A review
captures the source revision and a monotonic attempt number. Editing away and
back or starting another review revokes the previous Start permission, including
when the new review fails. A started import retains its frozen source plan.

In the first rebuilt package, a three-document review identified its source,
disabled Start after a source edit, and required a fresh review even after
restoring the old text. One source file was then changed after review. Starting
that plan produced all three receipts: one created file, one refused existing
identity and one refused changed source. Disk inspection verified that both
pre-existing documents were unchanged, the imported file matched its source,
and the changed source had no destination file. A further Save a copy produced
the exact expected body with Saved status and no lingering Saving message.

The readable status retains the expected denominator, delivered receipt count,
failures and any abandoned queued receipts. The view shows the last eight
receipts; the model retains the bounded complete record set. Raw IDs and stamps
remain available in the model and disk evidence.

The long receipt view exposed another failure: after scrolling Source directory
offscreen and returning to the main window, Import could not focus/raise the
existing import window. Clipped nodes are deliberately absent from interaction.
The source controls and Open workbench now stay outside the scrolling review
and receipt body. This uses the public focus interface and adds no host hook.
A rendered regression failed before this layout change at 640x400, then passed
at 640x400 and 1000x720, checking visible controls and focus after scrolling.
These are logical-size render checks, not physical large-window acceptance.

## Negative and incomplete checks

A live cancellation attempt used 128 files with bounded bodies. The import
completed before the synthetic Cancel click could interrupt it: 128/128 receipts
succeeded and all 128 files matched their source bytes. This is not evidence
that live cancellation works. The existing controlled importer cancellation
test remains separate; actual interruption and its UI receipts are still open.

The final package passed the scrolled-window navigation check: Source directory,
Review and Open workbench remained visible, Open workbench raised main, and
Import raised the existing import window with Source directory focused.

Keyboard input remains a concrete open defect. In the first final-package run,
Command-Q, Command-0 and typing had no observed effect after switching windows,
including with pointer focus and a fresh bundle binding. A fresh-launch control
then accepted typing in main Search, in the new import Source field, after
native SetValue, after 128 completed imports and after scrolling receipts.
After Open workbench raised main and focused Search, further typing had no
visible effect. Native File Quit exited normally in both runs. This narrows the
observed trigger to the window transition; it does not establish the causal
fault or exclude an automation interaction. Mote
`bd-01M6AM7PZWM787VDNEKERJ6N8E` tracks the investigation.

Long titles such as Cancellation specimen 000 also wrapped beyond the fixed
virtual sidebar row height and overlapped neighboring controls at the default
size. Mote `bd-01M6AM7Q2SWWS9D3HAS6YHJYZ2` tracks that layout defect. Neither
open issue is hidden by the passing automated suite.

The first new rendered test fixture wrote the source field and requested review
in one transaction, so its captured revision was superseded at commit. It was
corrected to use separate source-edit and review actions, matching the UI.
That setup failure is retained separately from the subsequent layout regression.
An earlier focused compile failure used a widened anonymous importer subtype;
the test now uses a named local class. Neither failure was relabelled as a pass.

Actual VoiceOver, IME, physical trackpad/drag, Native provider and P0.6 GC gates
remain open. No macOS accessibility setting was changed. Original test files
and all generated documents are preserved; only the isolated fixture directory
is restored between app runs.

## Final verification and artifacts

| Check | Result |
|---|---|
| `testJVM` | 449 passed |
| `testNative` | 449 passed |
| `desktopJVM/test` | 127 passed |
| `probeParagraph/test` | 40 passed |
| Examples | Compiled |
| Workbench package | Built |
| Unconfigured package portability and library load | Passed |
| Isolated configured package signature | Verified |
| Final scrolled-window navigation | Passed in the packaged app |
| Keyboard after switching windows | Unresolved failure, preserved above |
| Live import interruption | Not qualified; completed before Cancel |

The final app's JAR is
`fda744f6349fe0bd2bb93e602c3780d141f84bed1bd6f96b3ab4619394f38050`.
Its native library remains
`b6d28ce595d2b93cd31632c9b6eebcde7b191dbe3822610249b024548706d306`.
The launch receipt records the source and isolated configuration hashes.

The first rebuilt run's 132 files are archived in `recovery-run1-documents`.
Each later 129-file run is separately retained in `recovery-final-documents`
and `recovery-input-control-documents`. Every imported cancellation specimen
was compared byte-for-byte with its source. The app exited before fixture
restoration; the final document directory contains only the original fixture,
SHA-256 `1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
The original checkout's tracked diff hash remains
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.

Artifacts below are under the isolated acceptance folder, not in git.

| Artifact | SHA-256 |
|---|---|
| `recovery-before-ui.json` | `998d91ec7862d82b0f37a82a463bfb6eadb3a41fddc653284368ac00d2e70576` |
| `recovery-conflict-files.json` | `cf0171fb8b82b551532da98f667accf7c4093c61090b1006f454c449a9e0edd7` |
| `recovery-focused-1.log` | `49fb25cc5cd16f3e99ac8916b9456fd18c18f86c2621bdd098ad5c0ea89d8bfd` |
| `recovery-focused-2.log` | `bdba938c4c4c598e9eb194f144b759250629189761a7db8f1dfc83012d7ff4bb` |
| `recovery-full.log` | `d44522f2dad3826ce4471d8c2d06c20c4913f380583daf702ed82624966e2823` |
| `recovery-ui-1.json` | `3ef52547d4ebf22c414a0d3fab65eb108243b1c2069e161f18b95a76a251a2c9` |
| `recovery-import-reviewed.json` | `5f0d0c76d23384a9bc9b33e32adf92c8c2746c5056c187b351abc7498544721b` |
| `recovery-import-result.json` | `0670cdc06749c694416e89f72b4221cbbc8a6425b71be4a83de2c2ded0fa80d8` |
| `recovery-cancel-copy-files.json` | `67f14935ae17dcbc65e5de8cd232b265ad716d353574d6a5249dd37e7709e273` |
| `recovery-run1-documents.json` | `34417521c9948735df12efe331ad70e04cd5c0286b33f26d4660a730dac77ed2` |
| `recovery-layout-before.log` | `cc756481eba2915671b96e8ab57690ecb4466aa1c057629430698f1e5362d1ca` |
| `recovery-layout-before-2.log` | `5a47bde19c2e194ad8423b8c395e1a764474759b69735675936b95fcb26b275b` |
| `recovery-layout-after.log` | `86bb6228a37362d117d10b64e8715270d2bff955b45ee69481c293b76d4c46e1` |
| `recovery-final.log` | `c79890eb8f88576e0d385ab005a25d427ce053907a9c0afffaea5458d0cef632` |
| `recovery-final-portable.json` | `09c9b016312bdc22b9217319b5d34f791e71130e18e0a4ecf51d04f47db7ecdb` |
| `recovery-final-launch.json` | `281f8326fc4d028054faa7bc55ffeba1b6479178f2c12585ef059644b93c378b` |
| `recovery-final-ui.json` | `649545ac9f290fe3278ac52ae3f9f95d0778d1cb9843ac011edb9e550e076de3` |
| `recovery-final-files.json` | `9bc65469a511b62fedaa66e9b47f7fd88468b1917863f5ca63a53db747ffd397` |
