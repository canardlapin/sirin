# Workbench cancellation and saves with controlled storage latency

Scope: owned worktree `/private/tmp/sirin-workbench-20261031`, parent `bfbfc6c`.
Mote: recovery `bd-01M6AJSZ7X502M2MDQ2QJCV1D1` and save-during-edit
`bd-01M6AS5FZ34H898H17ZK2QX40C`. The full workbench acceptance plan remains
open; these checks address two workflow cases.

## Natural-speed attempts and import review repair

A 480-file synthetic source stays within the repository's 512-document limit,
but the separate import-plan limit is 128 entries. Review refused it with
`requirement failed: Import plan exceeds its budget or repeats an ID`.
No destination file was created. A second source contained 128 maximum-length
bodies (16,384 BMP characters each, 49,152 UTF-8 bytes). This also completed
before the automation could observe an enabled Cancel button: all 128 receipts
reported success, and independent SHA-256 checks matched every created file to
its source. This is a successful import, not a cancellation result. Both runs
and their manifests remain preserved.

The importer now gives an actionable review error: import at most 128 documents
at a time and choose a directory with fewer documents. The unchanged bound has
one shared constant used by plans, reports and adapter feedback. The new 129-file
regression failed before the repair and checks the exact message and absence of
destination creation afterward. No collection/import limit or test threshold
was increased.

## Controlled fixture and what it can establish

`desktop/src/test/scala/sirin/desktop/WorkbenchAcceptance.scala` is a test-only
entry point. It constructs the real public `DocumentWorkspace` with a repository
wrapper around `FileDocumentRepository` and uses `FileDocumentImporter` unchanged.
It uses only public `Work` composition and the application's normal worker pool.
No kernel, scheduler, cancellation or save policy is modified.

The wrapper pauses saves before the real repository receives their captured
data, and pauses the third imported create after its real file is committed but
before the result is returned for progress delivery. Control files live beside
the isolated documents directory. Holds poll cancellation, time out after 120
seconds, and emit entered/released/cancelled receipts plus the exact body received
by the wrapper. These artificial holds make otherwise short races observable;
they do not measure normal filesystem latency or UI performance.

The normal package excludes test classes. The controlled run adds only the five
compiled `WorkbenchAcceptance` classes in a separate fixture JAR and temporarily
changes the isolated package's launcher main class and control-directory property.
The production launcher is restored and the app re-signed after acceptance.
Source, class, package and configuration hashes identify the exact payloads.

## Observed cancellation result

The actual Import window reviewed 128 entries and started normally. With the
third create held, the UI showed two receipts, Import running and an enabled
Cancel import button. Independent file hashing found three exact source copies,
and the gate receipt identified the third committed document. Clicking Cancel
changed the UI to cancelled with two received receipts and zero abandoned queued
receipts. The held worker recorded cancellation, never release. All three files
remained byte-identical; no fourth file appeared during the subsequent save
workflow or before clean Quit. Searching the main workbench for `0002` found the
third document despite the absence of its progress receipt.

The zero abandoned count refers only to queued receipts. The third create was
already committed but had not emitted its receipt; the UI correctly says that
files already written are retained and Refresh shows the current files. These
checks establish cancellation through the real UI/file workflow with injected
storage latency. They do not reclassify either naturally completed attempt as a
successful cancellation.

## Observed save-during-edit result

Through keyboard input in the document editor, the first submitted snapshot was
`Snapshot A.`. While its storage call was held, the on-disk file retained the
original fixture hash. The UI accepted and queued a second save of
`Snapshot A. Queued B.`, then accepted further edits to
`Snapshot A. Queued B. Newer C.` and displayed
`Saving; newer edits remain unsaved.`. The second save had not entered the
repository wrapper while the first was held.

After release, both submitted snapshots reached the real repository with their
exact captured bodies. Independent binary decoding found B on disk, while the
actual editor still contained C and displayed `Unsaved changes`. The brief
intermediate on-disk A generation was not separately sampled; existing queued
save tests cover that exact ordering. A final explicit Save wrote C and displayed
Saved. No save error was shown.

After a clean Quit, the fixture JAR and controlled launcher were removed by
restoring the freshly built normal package. The production entry point reopened
the exact C body and showed Saved. After another clean Quit, all generated files
were archived and the original isolated fixture was restored byte-for-byte.

## Verification and remaining boundaries

| Check | Result |
|---|---|
| `testJVM` | 449 passed |
| `testNative` | 449 passed |
| `desktopJVM/test` | 136 passed, including the new oversized-review case |
| `probeParagraph/test` | 40 passed |
| Authoring examples and workbench package | Compiled and built |
| Relocatable package closure / signature | Passed; controlled and restored packages re-signed and verified |
| Controlled live import cancellation | Two delivered receipts, three retained exact files, worker cancelled, no later publication |
| Controlled queued saves during editing | Frozen A/B submissions, exact B on disk with C still dirty, final exact C save |
| Normal production relaunch | Exact C body, Saved state, no fixture classes in the production JAR |

The fixture uses JVM repository adapters, so it does not establish a Scala Native
application provider. Shared Native compatibility remains green; P0.6, Linux
revalidation and the explicit Native delivery decision remain open. Actual IME,
VoiceOver, physical input, smooth live resize, display/appearance checks,
independent authoring and the matched ScalaFX workflow are separate unfinished
outcomes. No memory dump, system setting or approval bypass was used.

## Reproduction and artifacts

Compile `desktopJVM/Test/compile`, then package the five generated
`WorkbenchAcceptance*.class` files under `sirin/desktop` in a separate fixture
JAR. Copy a normal app into the isolated acceptance location, add that JAR to
its classpath, select `sirin.desktop.WorkbenchAcceptance` as its main class and
set `sirin.acceptance.control` to a sibling directory of `documents`. Keep the
normal `--documents` arguments. Create `hold-import` and `hold-save` in that
control directory. The retained launcher configuration gives the exact values.
Use UI Cancel at the import hold; rename `hold-save` to release saves. Gate
receipts identify the observed boundaries. Restore the normal package after
quitting; the harness is never in the production application JAR.

Raw artifacts are under `/private/tmp/sirin-workbench-acceptance-20261031`.
The 480-file source and 128-file subset are preserved; the fast completed run is
in `live-cancel-fast-run-documents`. The controlled package is archived in
`controlled-work-package`, and its generated documents in
`controlled-work-run-documents`. `controlled-work-artifacts.json` lists retained
logs, receipts and gate files with byte counts and SHA-256. Screenshots remain
in the computer-use conversation. The source receipt hashes all changed and new
Scala files; both launch receipts freeze JAR/dylib/config hashes.

| Artifact | SHA-256 |
|---|---|
| `controlled-work-artifacts.json` | `8e614b8cb487f8efb2b3ba339a8f9dd6b7732d7b097cba067d39c3c393db8aa9` |
| `live-cancel-source-manifest.json` | `a99bc763d92856aba5f2ed922ee610df0f9c9b9123bc30f2acf6851f30b45a49` |
| `live-cancel-fast-run.json` | `be89a8d0fc7f4fdc0a17e4427094b27001382d98e808b21dec677212386c18fb` |
| `controlled-work-before.log` | `a39f3c15d76ad59b26457057b7739ac279fb7a014f90a376c2a95b7ba6898b31` |
| `controlled-work-full.log` | `9dc8b6174170aa1bd2bfb9d272a3f50c2c61209828dc3c2cc61c66645c882a07` |
| `controlled-work-launch-receipt.json` | `d10ae012d1b981c14af63369c5e4e0f86ceb3252736b19b689cb5dd1400164ff` |
| `controlled-work-production-receipt.json` | `f00e6d64d6597c3c1f7f32d2bfba115030bc113efb94ef39edf5da9f8a74886d` |
| `controlled-work-ui.json` | `c913a61e1697d71a62dccd0d8f6be60317c8bc2b02f327487a82726603cacf2b` |
| `controlled-save-snapshot-b.sirin-doc` | `d6d9da20d09c2e894df06b9b2a44235e7a9672930f03fd828c206dd1016f5060` |
| `controlled-save-snapshot-c.sirin-doc` | `ae3cf7414f83e88d59352a5f7c73528cd005681a246b9ea138e3aa9be952a7af` |

Original checkout tracked diff remains
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
