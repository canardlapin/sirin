# Larger application windows with bounded GPU resources

Scope: owned worktree `/private/tmp/sirin-workbench-20261031`, parent `fb58b60`,
Mote `bd-01M6AR7DVGQG9X3W9MN6YA6V8F`. This is one part of the persistent
workbench acceptance plan, not completion of the broader application goal.

## Reproduction and policy

In the prior approved package, native zoom produced a 1240 by 816 screenshot,
and dragging the bottom-right corner produced at most 1280 by 856 including
window chrome. At 2x this limited content to 640 by 400 logical pixels. Source
inspection confirmed the 1280 by 800 physical native window constraint and the
1,048,576-pixel tile cache limit. The new large-frame regression, initially using
the existing cache constructor, failed with `GPU frame pixel budget` at its first
2560 by 1600 frame. That negative log is retained. An initial 5120-wide maximum test subsequently
failed the existing shared graphics 4096-pixel axis bound. The profile was
revised to fit that contract, including recalculated native byte budgets; the
failed full run is also retained.

The [design amendment](../design/decisions/2026-11-01-application-viewport.md)
selects an application viewport profile explicitly in the application render
session. It coordinates window dimensions, active/cache tile inventories and
exported texture accounting. The default development cache and native host keep
their old bounds. Complete packets retain exact coverage and device identity
checks, and the three-per-device / 48-process packet policy is unchanged.
Native signatures do not change. The shared private batch record adds a native
atomic byte counter; no callback enters Scala from a foreign thread.

## Automated verification

The large-frame regression keeps two 2560 by 1600 frames and a 3200 by 1800
replacement alive, then retires the cache. It reads each through the presentation
pipeline and checks known solid-color pixels at corners and across tile seams.
This exceeds the old tile, dimension, cache-pixel and exported-output bounds.
It verifies cache inventory limits and refusal of an out-of-profile height
without disturbing the valid cache. After retiring one old packet it renders the
maximum 4096 by 3200 viewport: 800 regular tiles with exactly 13,107,200 cached
pixels, and checks both opposite presentation corners. These simple fixtures
exercise the new dimensions; the existing independent complex-scene pixel
oracle and single-owner/coverage/device rejection tests remain unchanged.
All frames, tiles, exported bytes and owners return to zero on retirement.
A separate case proves the default development cache still refuses the large
fixture without allocating tiles.

The standalone native budget test checks exact device/process byte boundaries,
zero/negative/oversized rejection, rollback on process refusal, one-byte slot
reuse and 40 concurrent rounds of 64 native threads. Counters return to zero.
The existing frame inventory helper is also rerun unchanged.

The Scala Native batch suite compiles and exercises the changed private native
owner record. Shared Native tests and the packaged-library ABI probes are
compatibility evidence, not a Native application provider or P0.6 qualification.

| Check | Result |
|---|---|
| `testJVM` | 449 passed |
| `testNative` | 449 passed |
| `desktopJVM/test` | 135 passed on the final profile |
| `probeParagraph/test` | 40 passed |
| JVM authoring examples and workbench package | Compiled and built |
| Package closure and signature | Passed before isolation configuration; re-signed and verified afterward |
| Packaged-library Native ABI probes | Accessibility and text/focus probes passed against the exact dylib |
| `probeRender/testOnly sirin.render.BatchSuite` | 14 passed under Scala Native |
| Native output budget helper | Exact boundaries, rollback, reuse and 40 concurrent rounds passed |
| Native frame inventory helper | Existing limits, rollback, reuse and 40 concurrent rounds passed |

The first full run passed both shared suites, then failed one desktop case at
the initial 5120-wide proposal. The final desktop run passes all 135 cases;
no test threshold was relaxed. Shared source did not change in that correction.

## Packaged window verification

The approved isolated app used only the test documents directory. At the initial
1280 by 856 window size, a coordinate click and keyboard input replaced the body
with an unsaved draft. Native zoom expanded the window to 4096 by 2682 pixels
including chrome. The first observation showed a transient stretched prior frame;
a subsequent observation showed the correctly laid-out, crisp content at the new
size. This is not smooth live-resize qualification. Keyboard input continued,
and Save reached the Saved state.

An additional unsaved suffix survived dragging the window down to 2000 by 1456,
then up to 2560 by 1656. Editing and saving continued at the larger size. Import
was opened and resized to 2200 by 1456 while the enlarged main window remained
alive. Source text survived the round trip to main. Main editing/saving worked
after the handoff, and closing Import restored the document's keyboard focus:
the next typed suffix landed in the main document and saved.

Quit ended the process, verified from Finder and the app inventory. Relaunch
and document selection showed the exact saved title/body and Saved state.
An independent parser checked the full versioned binary document, including its
ID, title, body and absence of trailing bytes. The body was:

> Larger viewport draft. After zoom. Unsaved across shrink. After drag. After handoff. After close.

The generated document is archived; the original fixture was restored byte for
byte after a second clean Quit. No rendering-recovery screen occurred. The first
AX click on the document field did not establish keyboard focus; a coordinate
click did. That observation is retained alongside the pending native semantic
actions work rather than being presented as an accessibility pass.

Window sizes above include native chrome, and the checks used computer-use
pointer automation. They do not establish physical trackpad behavior, human
live-drag quality, arbitrary display scales, real IME, VoiceOver, Native desktop
provider support, performance gates, or completion of the broader workbench goal.
The unchanged old development host and CPU reference path retain their bounds.

## Artifacts

Raw logs and receipts remain in
`/private/tmp/sirin-workbench-acceptance-20261031`. The prior app is preserved in
`frame-inventory-repaired-package`, and generated documents in
`large-window-run-documents`. The launch receipt freezes all changed/new code
sources and the packaged JAR/dylib/config. `large-window-artifacts.json` lists
retained run files and their SHA-256 hashes. UI observations are described in
`large-window-ui.json`; screenshots remain in the computer-use conversation.

| Artifact | SHA-256 |
|---|---|
| `large-window-artifacts.json` | `a1ca1ec7b80d94e0423e748617222012fb4940418fa885d1515ce3a7cd275869` |
| `large-window-before.log` | `d88ac7f30b1a3289b0932531b2d193c6871809c907058aeec7786672572a842b` |
| `large-window-full.log` | `ae7ac092d2399c7c1d2ff81ef8dbc7e769927101b97664409cffd77c7667337c` |
| `large-window-final.log` | `6995e311445e48871eee8798c96f0d41abe3280696409b583cf6ef88421e065a` |
| `large-window-native-final.log` | `e557ed125f26a73fd161a6c1e923a6515168bf4b8bf1da5f1754e24c899d0695` |
| `large-window-focused.log` | `5351e6ea4c31f39f4a0ed37cf8a9d1822b0f613a0c164ef08d62905aca384dd6` |
| `large-window-launch-receipt.json` | `afb2e8a5df0d587ae5d8587f536df7bc6e1bf75dd1f959da395bb05fa67f29ec` |
| `large-window-ui.json` | `d2c5da8d63f3806c386a7a23136262f2cc85189ea110e929559d2c6b36082dac` |
| `large-window-saved-result.sirin-doc` | `2e2b67d7a52b5a111bad76257b2b00ed1388ccad58663bde0ecf70e6242d90ee` |

Final application JAR: `9e05312499041d7cafc507d5839b356546cdb8629b5bd40b5ebeaef80bf08710`.
Final native library: `794d4f29365994bb89f20caaa0b3b5be825ba0347dffc464658effb2b46dad53`.
The original checkout tracked diff remains
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
