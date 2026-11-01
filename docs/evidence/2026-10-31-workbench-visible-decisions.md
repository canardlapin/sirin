# Visible workbench decisions and usable editor geometry

Scope: the owned `workbench-acceptance-20261031` branch, on parent `d1b7e72`.
The earlier real-window check found a narrow editor and confirmation controls
below the viewport. The implementation and alternatives are recorded in
[the design note](../design/decisions/2026-10-31-workbench-decisions.md).

## Automated regression

`PersistentWorkbenchSuite` now runs the existing actual-file, Unicode editing,
menu-save and frozen-render replay scenario at both 1000x720 and the default
640x400 logical size. It additionally checks that title, body and Save fit,
the body has useful width and height, moving the editor preserves its actual
field identity and width, and the dirty-close modal and both decision buttons
are inside the window. Initial focus is Keep editing. Outside clicks do not
create a document or close the decision; five Tab transitions remain inside;
Escape preserves the draft and restores its field focus.

The existing shared workspace identity walker was extended through Referenced
and Popup snapshots to reach the newly wrapped content. Its original identity
assertions were preserved.

## Failures retained

Raw logs are retained in `/private/tmp/sirin-workbench-acceptance-20261031`:

- `usability-focused.log`: the identity helper did not traverse Referenced.
- `usability-focused-2.log`: new key-event tests omitted explicit modifiers.
- `usability-focused-3.log`: both rendered popup cases exceeded the reference
  renderer's per-sample work budget.
- `usability-render-diagnostic.log`: the expanded rounded stroke contained
  thousands of edges. The popup border now uses nested rounded fills.
- `usability-focused-4.log`: both workbench cases and seven popup regressions
  pass. No budget or assertion was weakened.

## Qualification boundary

The automated test uses the offscreen reference path, including CPU readback.
It is not latency evidence for the application's shared-device batch path.
The Unicode save assertion proves exact persistence; the capture still shows
an unsupported emoji glyph and does not establish universal glyph coverage.
Native Dialog and Checkbox publication, VoiceOver, actual IME composition and
physical trackpad input remain unqualified. Shared Scala Native tests do not
establish a qualified Native application provider.

## Retained log hashes

| Artifact | SHA-256 |
|---|---|
| `usability-focused.log` | `4cf4b076004fec42abcade290f6586381f1d7711473aed641a41e3e146bcf04c` |
| `usability-focused-2.log` | `4cf11eb762b8b8bfec9ad0e287f7d2f1c3002407382457019ef2a49303705e77` |
| `usability-focused-3.log` | `742c8715d4d3de9acb204befca5fbb2d8e27787571d226efe312e793f03752f5` |
| `usability-render-diagnostic.log` | `19c69708cc0b3e281850ad9d296794f815452c33ba547be5b28389a9a5bb3323` |
| `usability-focused-4.log` | `34009833b94a50906bbb3445a2e7779408ec9a6bf36ecb52d88827698ff2d845` |

## Fresh-package results

| Check | Result |
|---|---|
| JVM shared suites | 447 passed |
| Scala Native shared suites | 447 passed |
| Desktop | 125 passed |
| Paragraph probe | 40 passed |
| Examples and workbench package | Passed |
| Unconfigured package portability and library loading | Passed |
| Configured isolated package signature | Verified |

The app was opened from Finder at the approved path, with only the isolated
synthetic document directory configured. The default window showed the full
editor and Save without scrolling. Dirty close displayed both decision buttons
inside the window, with Keep editing focused. An outside search-field click
left the decision open; Tab focused Close view; Escape dismissed the decision
and restored the exact draft and body focus. The controller regression covers
repeated Tab wrapping; the real-window check is narrower.

The native File action moved the editor to the opposite side and kept the draft
and focus. Native close followed by Close view removed the last window;
Command-0 reopened the same unsaved draft. Command-Q then showed the dirty-Quit
popup. Keep editing cancelled it, and Command-S saved the exact body
`Visible popup acceptance: draft retained.`. Decoding the saved file confirmed
that text. A separate document view used the full window; its workbench-only
menu actions were disabled. Clean File > Quit exited; the subsequent AX
observation returned `procNotFound`.

The first observation after batched typing and Command-W showed input still
arriving. The close command was reissued after the body had settled. This does
not establish input latency or losslessness under a rapid synthetic sequence.
The saved file was archived, then the original synthetic fixture was restored
with its previous SHA-256 checked. Native AX still exposes the popup buttons
without its Dialog container and explanatory text: accessibility acceptance
remains open.

Additional artifacts in the same local directory:

| Artifact | SHA-256 |
|---|---|
| `usability-full.log` | `d6e7021f5a96352e2b8c4c172c7f68e3bd5db54cda03586e3b2d0ab7bbb7e3da` |
| `usability-portable.json` | `2b25e8a972073987d92ae402cfb4edb80661ef55d697a69dd88146bb50b55b0e` |
| `usability-launch-receipt.json` | `1e565f90dea15eed6cd259c9788efd8a521d153b9c7317ee66baee38aa5e08e7` |
| `usability-ui-receipt.json` | `ff10b77d9925386b58ac1181653aa5f219c2c085109902beeffd13fbc2f250ae` |
| `usability-saved-draft.sirin-doc` | `3593731c390e857149f6066dbf6ff50b4f522c585516d5c3c8a6458ca2fa2886` |
