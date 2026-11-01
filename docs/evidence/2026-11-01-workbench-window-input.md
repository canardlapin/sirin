# Workbench keyboard input across window activation

Scope: the owned worktree on parent `e5acda7`, the approved isolated workbench
package and synthetic documents. This follows the preserved failure in
[recovery evidence](2026-10-31-workbench-recovery.md) and implements
[the native ownership decision](../design/decisions/2026-11-01-native-text-input-owner.md).

## Diagnosis

A fresh package accepted typing in Search and in a newly opened Import window.
Returning to main with Open workbench then left the visible Search value
unchanged after typing; an application shortcut also had no visible effect.
No import was needed to reproduce the failure in the diagnostic run.

Temporary traces recorded native events, focus and text-input start/stop order,
plus delivered managed event kinds and target IDs. They did not record document
text. The decisive native sequence was start window 2, focus loss for window 3,
stop window 3, then focus gain for window 2. Text events stopped appearing after
that sequence. The trace patch and diagnostic app are preserved separately;
none of that logging remains in the repair.

The pinned SDL 3.4.18 Cocoa backend has one field editor for the process, while
SDL's active flags are per window. Starting the new window moves the shared
editor there. A later stop for the old window removes the same editor, leaving
the new window's active flag true. The next update therefore did not restart it.
The design note links the pinned upstream implementations.

## Independent native regression

`desktop/tools/test-text-input.sh` creates two hidden SDL windows. It neither
shows nor activates them. The optional `--legacy-order` control performs the
old direct SDL start/start/late-stop sequence. It fails with
`active=1 attached=0`: the active flag alone is insufficient evidence.

The normal check calls the same ownership helper as the desktop shim. Four
alternating handoffs retain a real Cocoa NSTextInputClient attached to the
intended window. Repeated starts retain the client identity, late stops for the
old window leave it attached, destroying an inactive window leaves the current
client intact, and stopping/restarting the owner detaches/recreates it correctly.
This is platform field-editor ownership evidence, not a real IME session.

Initial attempts inside the filesystem sandbox could not initialize SDL video
because the macOS service connection was unavailable. Those logs are retained
as environment failures. The unchanged hidden-window check ran outside that
sandbox: the legacy control failed and the ownership check passed.

## Repair boundary

The native application now owns the text-input client. Handoffs stop the old
owner before starting the new one. Background presentations, late stops and
late composition clears cannot take or clear another window's client. Closing
the owner releases it before destroying the window. All work stays on the
native application's owner thread; no managed callback, ABI entry, kernel
semantics or focus policy is added.

## Scala Native and final package

The existing accessibility Native probe did not bind the text/focus entry
points. `probeTextInputNative/run` now reaches the four existing declarations
in `desktop/native/text_input.h` and checks invalid-owner rejection, without
creating a window. The accessibility ABI probe also passes. This supplies a
Native binding check; it does not establish a Native application provider,
P0.6 GC qualification, or real IME acceptance.

A comparison against a later local rebuild found different library bytes.
Rather than infer binary identity, the final Native check explicitly loaded
`SirinWorkbench.app/Contents/app/libsirin_desktop.dylib` through
`DYLD_LIBRARY_PATH`. The retained dyld log confirms that exact path, its packaged
SDL framework and successful calls to all four entry points. Temporary trace
strings are absent from the final app JAR and native library.

The final package passed the original failing sequence: type Alpha in Search,
open Import, type source, return with Open workbench, then type Z in Search.
Search became AlphaZ. BackSpace, Command-I, further Source typing and Command-0
also worked. Keyboard editing replaced the document body; Command-Q displayed
the dirty-Quit dialog, Escape restored Document focus, and further typing plus
Command-S produced the exact expected saved bytes.

Closing Import with Command-W exposed a distinct remaining issue: the main
window is left inactive. Immediate typing has no effect; clicking the editor
activates it and typing/Save work. Mote `bd-01M6AN6YB0JZPB269MZV4SVM5S` tracks
remaining-window activation. Reopening Import then accepts typing, and clean
Command-Q from that window exits. This check does not claim automatic focus
restoration after closing an auxiliary window.

| Check | Result |
|---|---|
| `testJVM` | 449 passed |
| `testNative` | 449 passed |
| `desktopJVM/test` | 127 passed |
| `probeParagraph/test` | 40 passed |
| Examples | Compiled |
| Hidden-window legacy order | Failed as expected: active flag with detached client |
| Hidden-window ownership/lifecycle | Passed |
| Scala Native text/focus ABI | Four existing entry points reached; invalid owners refused |
| Scala Native accessibility ABI | Seven entry points reached; invalid owners refused |
| Exact packaged library from Native probe | Loaded and checked |
| Package portability/library load | Passed |
| Isolated app signature | Verified |
| Packaged window handoffs and keyboard persistence | Passed |
| Activation of remaining window after close | Open; pointer activation restores input |

The final package's native library SHA-256 is
`788faba25931b162282f7edc77e3ab947331c0f272a6115021ece7311ae2d9b3`.
The final saved body was
`Window handoff: keyboard editing remains live. After Escape. After closing Import.`
and its complete document is archived as `input-saved-result.sirin-doc`.
After the app exited, the original fixture was restored to SHA-256
`1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.

Actual VoiceOver, IME sessions, physical trackpad/drag, live import cancellation,
long-title row layout, large-window qualification and the remaining acceptance
plan are still open. No macOS accessibility setting changed.

## Local artifacts

Artifacts are retained under `/private/tmp/sirin-workbench-acceptance-20261031`.
The diagnostic app is archived in `input-diagnostic-package`; the final
configured app remains at the approved path, exited.

| Artifact | SHA-256 |
|---|---|
| `input-diagnostic.patch` | `4a0002d29ca3d6a6e96e7e6f4408f04fc8c76d477e00214f2f9f3d811058d9f5` |
| `input-diagnostic-build.log` | `da3e19cb8a16135cfd394a7fcc5a5039867d6aa8d1cd3c11127f9f2f6f3866a1` |
| `input-diagnostic-launch.json` | `cc2da3fecbb327bf32c051986bff08f971959da1d0a9c4a2c4c3031754de432b` |
| `input-native-trace.log` | `232c0dcda3c98bd9e613db691c740a9ae8dd215f237f04c3c23941660a7dd63a` |
| `input-cocoa-trace.log` | `a81736cc8ea85b4e78ac1e9a685a227285cc99d1e8dc58b50742ac093847a274` |
| `input-managed-trace.log` | `960e3191d7a946babb123d071394a6997cfb9df541246f1da9476dab849d9364` |
| `input-owner-before-sandbox.log` | `98b914baed59676c23eff8e5d471caaee7a60e15ff69ff9a312f721f0b4eebea` |
| `input-owner-after-sandbox.log` | `d52273bb64637e8687994f245e60c99a9ca6a7ed07146b08b2d750f7f0c0f196` |
| `input-owner-before.log` | `485e10a35905f8b1d652a8182ff06be982737a270b331b5e52a992ead56e622f` |
| `input-owner-after.log` | `053cddd832064ecd5ace1abbb426806ca61f4146d218bbd7b782a469da5d03b8` |
| `input-owner-final.log` | `053cddd832064ecd5ace1abbb426806ca61f4146d218bbd7b782a469da5d03b8` |
| `input-full.log` | `4bbe0c1465c6cf804c40b23d6c79db23b44735adc323f2f29ef7c3a6d8385454` |
| `input-native-abi.log` | `4b90f0cc66b280fc2edfb0958a9d4d491618af7040c99a24ff0e609fa2fb650f` |
| `input-native-abi-final.log` | `7d95f2f0d926e362ac67dcd5c0048ad922018b979092eb693021db662d2ed291` |
| `input-native-packaged.log` | `c0949310835d33a989cca29e9520051169b649f02e1422aa30799618dfef8cda` |
| `input-library-comparison.json` | `227492fe3257aa64f2fdc220671ce82372a950af3c6642674f2678538308f7ac` |
| `input-portable.json` | `652e1607420ab00be57e8524601d0fa31d0004a4ae03b62d9696c679e18e0234` |
| `input-launch-receipt.json` | `eefa1f96c83617f5a31c7da5c4ead67b38d92b7cbe77b06993c696d5c607c053` |
| `input-ui-receipt.json` | `cc8da16cd1d73e4e9368b374a9deba3efd42e5f37f85e5c3265573578a251aa5` |
| `input-saved-result.sirin-doc` | `312da0634200a095fcefba33178b1e17bd23d7cbd9644a83c6d5a70925766640` |
