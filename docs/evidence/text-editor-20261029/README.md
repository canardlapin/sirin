# Editable text with AccessKit — 2026-10-29

Isolated candidate `codex/editable-workbench-20261029`, based on accepted camera
checkpoint `13030fe`, at `/private/tmp/sirin-editor-20261029`. The unfinished
editor in `/private/tmp/sirin-gpu-host-20261028` and the shared checkout remain
untouched. This implements the bounded [editor contract](../../plans/2026-10-29-text-editor.md)
and the owner's explicit choice to replace its inherited Cocoa AX bridge with
AccessKit. Native UI acceptance remains pending the launch approval below.

## Implementation

Public `TextField.bind(Cell[String])` now mounts with stable control identity,
labels, placeholders, errors, multiline layout and transactional commit/blur.
Immutable host state owns directional grapheme-safe UTF-16 selection, provisional
composition, viewport scrolling and bounded undo/redo. Retained paragraph layouts
supply drawing, bidi/wrap navigation, hit testing, selection and caret geometry.
Clipboard commands, composition cancellation, external replacement, retirement
and stale geometry/action rejection use the existing transaction/mount contracts.
No kernel scheduling, identity or lifetime policy changes are made.

AccessKit C0.23.1's macOS subclassing adapter owns platform accessibility objects.
There are no Sirin NSAccessibilityElement subclasses or custom AX query methods.
A native-owned immutable tree supplies window, text input, multiline input,
button and text-run nodes. Values, directional selection and geometry publish
after presentation. Native activation answers synchronously from that snapshot.
Owned requests are copied to a 64-entry queue tagged with control identity and
presented revision, then freed. The UI owner validates actions again. SDL wakes
occur outside the snapshot lock; no Scala/JVM upcall runs on native threads.
The adapter detaches before its native userdata is released. Invalid/oversized
requests are refused and reported; they do not terminate the editor.

AccessKit expects physical pixels. Native publication converts retained logical
bounds and character widths using the window's current backing scale; AccessKit
performs the Cocoa screen conversion. One text-run character represents one
retained grapheme, with explicit UTF-8 byte length and UTF-16 offset mapping.
A grapheme over 255 UTF-8 bytes cannot use AccessKit's byte-sized character length:
that field retains value/focus/set-value but omits rich text range/selection
semantics. The underlying editor remains usable; graphemes are never split to
hide that limit. General screen-reader word navigation is not qualified here.

The desktop now links one Rust static library, `sirin-native`, containing
wgpu-native, ICU4X and AccessKit, as required by VISION6.1. Its checked-in Cargo
lock freezes wgpu-core/hal/types/naga29.0.4 and AccessKit0.25.2/macOS0.27.2. These
resolved patches differ from the old standalone prebuilt graph; the desktop GPU
checks below execute the combined graph. The independent component probes keep
their original separate process/library boundaries. `otool -L` confirms the
desktop has no wgpu dylib dependency. Vendor sources, binaries and fonts remain
ignored. The FFM library has process lifetime, required by AccessKit's installed
Objective-C forwarding methods.

The combined build checked 149 GiB free before compiling (the script enforces
8 GiB). Download pins and the header digest are checked by
`native/fetch-sirin.sh`; [the frozen graph](validation/rust-graph.txt) and
[artifact/source hashes](manifest.json) identify this candidate.

- [wgpu-native v29.0.1.1 source](https://codeload.github.com/gfx-rs/wgpu-native/tar.gz/refs/tags/v29.0.1.1): `6008651e1d10022d4ded15b69405b4ba88577be5d623938ba98ad241b2cbf818`.
- [AccessKit C0.23.1 official release](https://github.com/AccessKit/accesskit-c/releases/download/0.23.1/accesskit-c-0.23.1.zip): `35b7ca8a6f1e038b5da35e1e9e5a0adaed9bfcf21e1496d29598fbbadcc7043f`.
- The pinned prebuilt WebGPU header is copied into the source archive's absent
  submodule, verified as `a483031c3fed05ea5dd1c74082a71676c46c5b2b820ccca10da515c033efc997`.
- Only ignored vendor Cargo manifests change their final library type to `rlib`;
  the parent emits the sole Rust static archive. No dependency source logic is changed.

## Automated validation

**789 distinct Scala cases passed**: 323 JVM, 323 Native, 7 font, 12 Unicode,
40 paragraph, 34 render and 50 desktop cases. Repeated refreshes are not added.
All required JVM suites passed in the first final-matrix log; the complete Native
alias passed in [its clean refresh](validation/editor-accesskit-native-refresh.log).
[The remaining component/desktop matrix](validation/editor-accesskit-full-2.log)
passes, including the existing 37 desktop cases and 13 editor cases. Those cover
Unicode editing, provisional composition, atomic commit/undo, clipboard, rollback,
wrapped/bidi geometry, copy coalescing boundaries, replacement/removal, normalized
preedit offsets and valid/stale accessibility actions. The final scoped refresh
also checks reverse native selection and rebuilds both app packages.

The [native protocol check](validation/editor-accesskit-protocol-3.log) creates
no NSApplication, window or adapter. It builds and frees real AccessKit updates,
checks 2x physical bounds/widths, emoji/combining UTF-16 selection mapping in both
directions, value requests, delivery from a pthread, queue overflow, retired
and inactive targets, malformed snapshots and oversized-grapheme fallback.
This qualifies the native data/queue seam, not macOS AX delivery or VoiceOver.
Six existing Python receipt tests pass; 13 authoring examples compile.

The combined desktop GPU suite re-executes the unchanged independent pixel
oracles. [Saved readbacks](pixels/) include 49 canvas/camera reference pairs and
four composed-frame outputs. Boundary exclusions and the <=2-byte checked-pixel
policy are unchanged. Raw maxima on excluded boundaries remain in the matrix
log. No renderer threshold or test assertion was weakened. These are GPU
readbacks, not native editor screenshots or visual acceptance.

```sh
bash native/fetch-macos.sh --sirin
bash desktop/tools/test-accesskit.sh
sbt --java-home /path/to/jdk25/Contents/Home testJVM testNative
sbt --java-home /path/to/jdk25/Contents/Home textNative/test probeUnicode/test probeParagraph/test probeRender/test desktopJVM/test examples/compile desktopJVM/desktopPackage desktopJVM/desktopEditorPackage
python3 -B -m unittest discover -s desktop/tools -p 'test_*.py'
python3 -B docs/evidence/text-editor-20261029/verify.py
```

## Preserved failures and open native acceptance

The first final Native run reports an error for ReconciliationAgreementSuite
without assertion/stack detail; it never printed that suite's start. Its
unchanged isolated rerun passes 2/2 and the subsequent full Native alias passes
323/323. The original failure stays in [history](history/editor-accesskit-full-1.log),
which also records the successful 323 JVM cases. No cause is asserted.
An initial build used JDK21 because setting JAVA_HOME did not select sbt's JDK;
it fails on existing JDK25 FFM APIs. Explicit `--java-home` resolves that toolchain
error. The first combined build refused `--locked` before the new lockfile
existed; explicit lock generation followed by a locked build passed. These logs
and the successful pre-AccessKit integration matrix remain in history/validation.

The final distinct local image is
`desktop/target/package-editor/SirinEdit.app`, bundle
`io.github.canardlapin.sirin.edit.integration`. It uses public TextField APIs.
Launching that locally built app through computer use was rejected by automatic
approval review: locally built executable code requires action-time confirmation.
Explicit launch approval was requested and remains pending. No alternative UI
launch route was used. Therefore the following are **not run on this AccessKit
candidate**: native AX queries/actions, actual OS typing/selection/clipboard,
the packaged IME/control/resize/device-recovery/close scenario, actual Japanese
or Chinese input methods, VoiceOver and the ordinary host latency regression.
The user's available-host waiver persists; no formal quiet-host or performance
qualification is claimed from test durations.

An independent session has recorded positive real-input/IME-control results for
the *older direct Cocoa* editor package. Those are not AccessKit results and are
not promoted into this receipt. A direct NSTextInputClient control is also not
an actual Japanese/Chinese input-method or VoiceOver session.

Keep the Mote editor task open until the candidate's native acceptance is run.
The complete asynchronous search/save/reorder/import workbench, other platforms,
full screen-reader qualification, arbitrary uncached zoom and browser parity
remain later gates.
