# Workbench activation after close and bounded sidebar titles

Scope: owned worktree on parent `e4f287c`, the approved isolated workbench
package and synthetic documents. The repair follows the remaining failures in
[window-input evidence](2026-11-01-workbench-window-input.md) and the
[design decision](../design/decisions/2026-11-01-workbench-window-rows.md).

## Repair

The desktop application records recent native keyboard-focus windows. When the
focused window retires, it activates the most recently active surviving window,
unless another window already has focus or the application is quitting. With no
surviving history it uses the last-created survivor. Background retirement and
application deactivation do not initiate activation. The surviving peer keeps
its logical control focus and updates the existing native text-input client.
No native ABI or kernel transaction, lifetime or scheduling law changes.

`Button.singleLine` is a public, opt-in description modifier implemented through
the public Element text and clipping APIs. It prepares unwrapped text, converts
hard line separators to spaces only for display, reserves ellipsis space on
overflow and chooses a visual grapheme-caret boundary for the retained portion.
RTL overflow puts the marker at the left. The full original label remains the
semantic name. Ordinary buttons still wrap. The sidebar allocates its existing
80 lp row as 40 lp title, 4 lp gap and 36 lp reorder controls, bounding even
fallback-font metrics without adding an application-specific host hook.

## Automated regressions

Three ordering checks cover most-recent survivor choice, no-history fallback,
retired-ID pruning, already activated windows, background retirement,
deactivation, Quit and closing the last window.

Rendered button checks cover a short label, long Latin with combining marks and
ligatures, Arabic, hard line separators, narrow/zero-content widths, full semantic
names, pointer and Space activation, retained identity and default wrapping.
They inspect actual glyph commands as well as semantic geometry. Workbench
regressions at 640x400 and 1000x720 verify a long title and both usable move
targets fit the 80 lp row; pointer reordering retains the title control identity.
The existing persistence, modal, docking and import checks continue in the same
scenarios.

The first focused run exposed an incorrect new test assumption: Arabic fallback
font metrics have a taller single line than the Latin short-label font. The
assertion now compares against the actual prepared unwrapped line height plus
button padding. The original failure log is retained. The explicit sidebar
allocations independently assert the row boundary; no wrapping or overlap
requirement was removed. The second focused run passed all seven cases.

| Suite | Passed |
|---|---:|
| `testJVM` | 449 |
| `testNative` | 449 |
| `desktopJVM/test` | 132 |
| `probeParagraph/test` | 40 |

The shared Native result verifies shared code on macOS arm64. This repair adds
no native entry points; it does not establish a Scala Native application provider
or close P0.6, Linux revalidation, IME, VoiceOver or physical-input acceptance.

## Additional failure found in the real window

Both close-and-type paths passed in the first package, and the Latin long-title
draft survived reorder, filtering and save. Opening the Arabic-title fixture
then exited the application. The same selection in a diagnostic launch reproduced
`IllegalStateException: native editor transport refused: 2` from
`EditorAccessibility.publish` through `NativeWindow.accessibilityField`.

The rendered regression reproduces missing source range 21–22 in the actual
Arabic layout. `TextGeometry.selection` correctly omits zero-width filled areas,
while the native text bridge correctly requires contiguous source coverage.
Publication now supplies downstream caret geometry when a grapheme has no
painted selection region. The character and its UTF-16 range remain intact,
clipped through the existing geometry path. Native validation and graphics
selection semantics are unchanged. The regression also covers zero-width space,
bidi isolates and an empty field.

## Package and real-window checks

Examples compiled and the clean package passed library-closure and deep/strict
signature checks. The shared suites above ran after the control/window changes;
the complete desktop suite ran again after the publication repair. No shared
source or native shim changed after the shared checks.

The first package verified Command-W and native close-button handoffs from
Import to main, with immediate Document typing and Command-S without another
click. Exact persisted bytes contained `Close handoff: Command-W works. Red
close works.` Long Latin titles showed ellipses, kept complete AX names and
usable move controls, and an unsaved body survived Up reordering and filtering
before saving.

The final clean package opened the previously fatal Arabic-title fixture,
accepted keyboard editing and repeated both close-and-type paths. The exact
saved title remained Arabic and the body was `Arabic document opens and saves.
After Command-W. After red close.` Reopening in the diagnostic rerun recovered
that exact content. Closing main also activated surviving Import and accepted
typing in its retained Source field. Clean Quit ended the process. Querying an
exited app through the automation binding reopened it once; subsequent exit
verification used Finder and the app inventory, without querying the exited app.

A **separate unresolved renderer failure** appeared during repeated handoffs:
the clean run showed Import recovery generation 4, with zero queued inputs.
Retry restored Source and its value. The diagnostic rerun reproduced a recovery
screen in main (generation 87), and narrow application logging captured
`IllegalArgumentException: requirement failed: GPU frame coverage, generation or
inventory rejection` from `GpuFrame.apply`, through `GpuTileCache.render` and
`RenderSession.renderGpu`. This is not qualified away by successful retry or by
the tests. Mote `bd-01M6APTQYS91VRFD96KXG58PMK` tracks the remaining causal repair.
Post-failure VM exception logging alone did not capture the original rejection;
its interrupted-preparation traces are not evidence of the cause.

Automatic review rejected a proposed full JVM heap dump because of broad memory
capture and pause risk. No dump was obtained. The safer narrow exception log
captured the failure on reproduction. The diagnostic app exited, its config/logs
were archived, the exact clean package hashes were restored, and all generated
documents were archived before restoring the original fixture byte-for-byte.

These checks use synthetic input and AX observation. Actual VoiceOver, IME,
physical trackpad/drag, large-window and other programme obligations remain open.
The three repaired issues close only their stated scopes:
`bd-01M6AN6YB0JZPB269MZV4SVM5S`, `bd-01M6AM7Q2SWWS9D3HAS6YHJYZ2` and
`bd-01M6APE64861VYF1QBSFWW24H0`.

## Artifacts

Raw logs, launch receipts, exact synthetic results and both UI observation
receipts are retained under `/private/tmp/sirin-workbench-acceptance-20261031`.
`window-rows-artifacts.json` inventories their hashes and sizes. Diagnostic
package/config artifacts remain separate from the final clean app; generated
files are in `window-rows-run-documents`.

| Artifact | SHA-256 |
|---|---|

| `window-rows-artifacts.json` | `3bd828ed2419f4cbe60cab7ccd12d74c45c5ccdc708e1b35730ac5993c13163b` |
| `window-rows-full.log` | `efe7388669a9210c548b084d781e8366c6ef59df7bd517666cc0675d898a58ec` |
| `window-rows-final-desktop.log` | `a18680bbd7d75402db424ffc8b1706007fba8c59e5a55faf84a4b954f8f8f846` |
| `window-rows-spans-before.log` | `22bcfd255f67705a738fafea6c6fe91afa101bc886dfb89cccf47aea647d76f4` |
| `window-rows-diagnostic.log` | `8cd1de45be9e1a54ecdefbafd8d7443381b4dc7df02dfb36d715dc6175ead176` |
| `window-rows-render-diagnostic.log` | `9ed50b85a248fd74efc2c52da6b870e642f0036b542e276f20af8de7b584f0cc` |
| `window-rows-final-launch-receipt.json` | `b19daae88648f0e97279d49e422d9d31407e18e38978007cad6c178ffe08a7a2` |
| `window-rows-final-ui.json` | `8a2621663365c3997ebfd290864a39f9f922c2cd026e7e560bc83188cbe21d73` |
| `window-rows-arabic-result.sirin-doc` | `1915900a3bb9907de6cb8ea9bab57d967b65352538fdcae56f999c41e14b98ca` |

The final app JAR is `67189f514b557432ab65445fae975256126e993ebf999ab0536785746c4e9435`.
Its native library is unchanged from the prior exact-packaged Native ABI check:
`788faba25931b162282f7edc77e3ab947331c0f272a6115021ece7311ae2d9b3`.
The restored fixture is `1e717858dda45fc34d230f0c58294cd96d9a286e0ed0a8776bdd51fa86a37d28`.
The original dirty checkout's tracked diff remains
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
