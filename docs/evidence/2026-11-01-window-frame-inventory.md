# Independent frame inventories for workbench windows

Scope: the owned worktree on parent `d0abca5` and the approved isolated
workbench package. This continues the reproduced renderer failure in
[window/title evidence](2026-11-01-workbench-window-rows.md) under Mote
`bd-01M6APTQYS91VRFD96KXG58PMK`.

## Cause and negative control

The previous packaged run entered rendering recovery during repeated Import
handoffs. Narrow logging captured `GPU frame coverage, generation or inventory
rejection` from `GpuFrame.apply`. The original native entry point returned the
same status for invalid coverage, device mismatch and inventory exhaustion.

Source inspection found the global `gpu_frames >= 3` guard. Two independent
window renderers can each own a displayed frame and construct its replacement;
that fourth packet is valid but refused by the old process-wide limit. The
regression creates a 16x16 tile on each of two real devices and alternates frame
creation. It fails at the second device's second packet, on the same native
entry point, with only two exported textures (2 KiB total). Both devices already
successfully created packets from their own tile. This rules out dimensions,
coverage, mixed-device input and texture-memory pressure in that reproduction.
The failing source and output are retained; the pre-existing single-owner
rejection test remains unchanged.

## Repair and bounds

[The design update](../design/decisions/2026-11-01-window-frame-inventory.md)
keeps three packets per device/generation and bounds the process total at 48,
derived from the existing 16-window capacity. Native atomic admission checks
both counters and rolls back on global refusal or allocation failure. Closing a
packet releases its owner slot before tile retirement can free the owner.
Native status 6 identifies inventory exhaustion separately from invalid coverage
or mixed-device status 4. No entry-point signature or managed callback changes.

This is a change in the ownership scope of packet accounting. The 16 MiB
process-wide exported-texture cap, per-cache pixel/entry limits, frame dimensions
and coverage/device checks are unchanged. Multiple packets reference the same
immutable textures; the two-device test retains six packets with the same 2 KiB
of texture storage. The fourth packet on either owner is still rejected.

## Verification

The complete GPU-frame suite passes seven cases, including the new two-device
case and the existing pixel oracle, coverage/device rejection, single-owner
exhaustion, cancellation and retirement checks. It returns frames, tiles, output
bytes and owners to zero. The new case also retires/reuses an owner slot and
reads back every retained packet.

The standalone C inventory check uses the same admission helper. It fills all
16 owner inventories, refuses a seventeenth owner at the 48-packet total without
leaking its owner reservation, releases/reuses a slot, then checks 40 rounds of
64 native threads against the per-owner and global limits. All counters return
to zero. No window or managed callback is created by this check.

Scala Native `probeRender/testOnly *BatchSuite` passes all 14 cases, compiling
and exercising the same private native owner structure. That is evidence for the
shared batch path; it does not establish a Native desktop application provider,
P0.6 qualification, or the separately required Linux revalidation.

| Check | Result |
|---|---|
| `testJVM` | 449 passed |
| `testNative` | 449 passed |
| `desktopJVM/test` | 133 passed |
| `probeParagraph/test` | 40 passed |
| `probeRender/testOnly *BatchSuite` | 14 passed under Scala Native |
| Native inventory helper | Limits, rollback, reuse and 40 concurrent rounds passed |
| Examples and workbench package | Compiled/built |
| Package closure/signature | Passed before isolated document configuration; re-signed/verified after configuration |

The existing accessibility and text/focus Native ABI probes also ran against the
exact packaged library. Both exited successfully; retained dyld logs identify
`SirinWorkbench.app/Contents/app/libsirin_desktop.dylib`. This does not add frame
bindings to a Native desktop provider or claim such a provider exists.

## Fresh-package workflow

The clean package started from the same Arabic fixture bytes used before the
failing diagnostic run. It replayed the exact sequence: open, replace the body
with `Diagnostic handoff.`, save, open Import, enter Source, Command-W, append
`After Command-W.` and save, reopen Import, native close, append `After red
close.` and save, reopen Import, then Command-0. Main rendered normally with
the complete expected document instead of entering recovery.

Four additional round trips between the two live windows showed the expected
Source field and main document without recovery. Closing main activated Import;
keyboard input immediately replaced Source with `Source after main close`.
Command-0 reopened main with the document retained. Independent file parsing
verified the unchanged full Arabic title and the exact saved body:
`Diagnostic handoff. After Command-W. After red close.` Clean Quit ended the
process, verified through Finder and the app inventory without re-querying the
exited app. Generated files were archived and the original fixture restored
byte-for-byte.

These checks close this specific inventory defect. They do not establish an
unbounded number of full-resolution windows, a larger viewport budget, real
VoiceOver or IME use, physical trackpad/drag behavior, performance gates or
completion of the broader workbench acceptance plan.

## Artifacts

Raw outputs remain under `/private/tmp/sirin-workbench-acceptance-20261031`.
`frame-inventory-artifacts.json` lists every retained run artifact with its size
and SHA-256. The launch receipt also freezes the final code-source hashes. The
prior clean package is preserved in `window-rows-repaired-package`; generated
documents are in `frame-inventory-run-documents`. The original failing packaged
trace remains `window-rows-render-diagnostic.log`.

| Artifact | SHA-256 |
|---|---|

| `frame-inventory-artifacts.json` | `c882cd5ac661e506eb11ef9271e542c5a2bed7b5acbd5f1a4cc19b106fbc43cd` |
| `frame-inventory-before.log` | `ce6f6035de7af7acae7a55ff009ae2f3e8b16ccabdf43093c3d9b62874eb1daf` |
| `frame-inventory-native.log` | `5b107eb4e7e0e2a25ec1ba291f030fcdc108f9e6cb31f8f13a005629dd35fc05` |
| `frame-inventory-focused.log` | `a0fecc9339940913a7cbd1ce68efcfc4398a9ab48f9339b83bb7486d55a55721` |
| `frame-inventory-full.log` | `edca169791b8ac975bd3a122f3624767b9a98f792c4dca8ac9c228f2397e239a` |
| `frame-inventory-launch-receipt.json` | `4590558b38a63d335adbada5775c44f0305e13e133f84f6dc0699f37ba1124aa` |
| `frame-inventory-ui.json` | `2d1ec8656fb9bc05c4e1e6c15e1caea13fce174830f9158e677a283854ffd533` |
| `frame-inventory-saved-result.sirin-doc` | `9640c8166f7ef46fe5b47b40b08ff97eb19bcc35e5fb4173b1d8f43ea9f53db5` |

Final application JAR: `fbc8effec62d439dd9dd6780a1f282723aeebce3c891a30ac3dcd53be1f68a8c`.
Final native library: `07bf47bd35b0a8674dbbbb6f0aedcc8afcd36e8bdfc8c4e54ce8f22b8fc9c492`.
The original checkout remains untouched; its tracked diff hash is
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
