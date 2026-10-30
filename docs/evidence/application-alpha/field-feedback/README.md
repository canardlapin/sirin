# Ordered native commands and recoverable field feedback

This successor keeps application quit and native menus behind earlier transported
owner input, including text held behind a geometry-dependent pointer/navigation
fact. Accessibility actions wait behind their peer's own input. Retiring a peer
clears obsolete input so zero-window commands can progress. Quit still uses the
ordinary application/window guards and existing owner publication laws. This does
not establish timestamp order between Cocoa callbacks and unpumped SDL events.

TextFieldView.maxLength declares committed user-input limits in UTF-16 units,
within the unchanged 16,384-unit hard maximum. The workbench uses title/search256,
import-directory2048 and body16384. Oversized typing/paste/IME commit/undo/redo/AX
replacement refuses the whole edit without changing the draft or undo history.
IME preedit remains provisional; a refused commit clears marked text and retains
the original draft. Programmatic invalid text is preserved and recoverable.
Field feedback is bounded per live identity, projected into the same immutable
layout/paint/accessibility description tree, and cleared by successful edits,
replacement or retirement. Wrapped captions/errors use their resolved height.
Save error presentation is bounded without replacing the retained error data.

Final checks: **387 JVM + 387 Native + 87 desktop**, plus examples compilation.
`full-checks-2/` freezes the final sources, commands, logs and zero source
mismatches. The earlier 386/386/85 stage remains in `full-checks-1/`. Two shared
field tests cover configuration, decoration/callbacks and identity retention; a
third shared regression preserves original save errors behind Unicode-safe
previews. Eight desktop additions cover queued quit/menu save, retirement,
UTF-16 boundaries, whole-edit refusal/recovery, provisional IME commit, current AX
replacement/malformed text, tightened-limit undo, Unicode-safe previews and the
explicit complex-paragraph resource refusal. These are headless/controller
checks, not actual IME or VoiceOver client qualification.

## Exact layout negative control

The first diagnostic-width fixture did not wrap and its failure remains under
`targeted-2/`. A narrow fixture requires a wrapped error and compares its resolved
extent with the editor/border geometry. With the exact prior Layout source,
`fixed-height-control/` fails and the image clips the second line. The same test
passes against the successor (`wrapped-height-successor/`), whose image shows
both lines. Neither the renderer limits nor input budgets changed. Both source
versions, the identical test hash, images and complete logs are retained.

`owner-boundary/targeted-1/`, `targeted-1/` and `targeted-3/` retain preceding
successful stages. Passing reruns do not replace the failed fixture/control.
The already published workbench base ddde0d4 has nine successful CI jobs
(`workbench-main-ci.json`); that is not CI for this uncommitted successor.

The separately frozen SirinWorkbench.app image is unchanged, and no UI launch
occurred. It predates these input/feedback fixes; current native-client validation
must eventually use a separately reviewed successor package. No native IME,
VoiceOver, focus/modal/dialog/drag, large-surface or visual acceptance is claimed.
All six alpha deliverables remain open, including the real diagram editor,
inspector, clean independent authoring/ScalaFX comparison and long GC/event gate.

Verify with `python3 -B docs/evidence/application-alpha/field-feedback/verify.py`;
add `--sources` to compare the current checkout with the frozen tested sources.


## Unicode successor and retained complex-text refusal

Final review exposed a preview cutoff inside a surrogate pair. The actual failed
control is retained in `unicode-bound-control/`. UTF-16-safe prefixes now omit
only a high surrogate split by the limit; original save errors remain untouched.
The first successor preserved Unicode but the repeated-emoji render exhausted the
unchanged paragraph shaping-work budget (`unicode-bound-successor/`). The final
suite separates a boundary-specific UTF-16 fixture from an explicit negative
resource regression, retaining that original 255-unsupported-emoji refusal.
`unicode-bound-successor-2/` and `full-checks-2/` pass with that limitation explicit.
No renderer budget was enlarged or fallback introduced. General complex-diagnostic
rendering and recovery remain required for the usable toolkit; this negative
control does not qualify them. The final source retains the exact earlier wrapped
layout tests and adds the Unicode/resource cases.
