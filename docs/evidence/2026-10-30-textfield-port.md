# TextField on the public seam, 2026-10-30

## Claim

TextField uses only public APIs. These are a `Component` with a scope-owned
draft, an `Element`, the text channel, editing services, focus events and
text-range semantics. The host's private editor has been deleted. The behaviors
that the legacy acceptance suite required still hold, observed only through the
public seam. The design is in
[the decision](../design/decisions/2026-10-30-textfield-port.md).

## Checks and results

Run on macOS arm64 with JDK 25.0.4.1 and the pinned Scala and Scala Native
toolchain, from task branch `textfield-port-20261030`:

- **`EditorControllerSuite`: all 21 tests pass.** The port keeps each test's
  scenario and claims. Assertions that read the private editor (`state`,
  `snapshots`, `fields`, `editorArea`, `textOrigin`, `Content.Editable`) now
  read the presented field's text semantics, prepared layout and error
  semantics, together with the bound cell, platform clipboard and composition
  effects, and host refusal feedback. "No undo step" and "history retired" are
  checked by pressing undo and observing the cell, not by reading history
  size. External replacement now also checks that the platform composition ends,
  which VISION requires and the legacy suite did not test.
- **`TextInputSeamSuite`: 11 tests**, including focus veto, hard-line
  documents, declared keys and services.
- **Other suites.** `WindowRenderRecoverySuite` (desktop and engine),
  `PersistentWorkbenchSuite`, `DocumentWorkspaceSuite`, `ElementSuite`,
  `MenuRoutingSuite`, `WindowCaptureSuite` and `TextFieldContractSuite` find
  fields through the component and element identity, or through presented
  semantics. Their claims are unchanged: retained drafts, identity across
  reorder and outlet moves, recovery with undo, shortcut bubbling, and
  rebinding to a different cell replacing identity.
- **`TextEditingSuite`.** The pure model tests moved to core with the model,
  and the two runtime contract tests moved to the host contract suite.
- **Pixels.** The limit-refusal frame
  (`desktop/target/field-feedback/limit-refusal.png`) shows the label, error
  border, selection, caret and wrapped diagnostic in the legacy layout.

Suite results:

- `desktopJVM/test`: 109 passed.
- Full `sbt testJVM`: 422 passed.
- Full `sbt testNative`: 422 passed. This required a clean of `hostNative` and
  `coreNative`, whose stale output still held the moved suite.
- `examples/compile` passed.

## Changes in observable behavior

- **Renderer.** The cached base drawing paints the focused text control's caret
  and selection through `FramePayload.textFocus`, instead of keying the whole
  base on focus whenever any editor existed.
- **Workbench undo and redo.** The editor workbench's Undo and Redo buttons are
  always enabled and send the field's own keys. Undo availability was private
  editor state.
- **Double-click.** Double-click selects word segments only. Clicking
  whitespace places the caret rather than selecting the gap.
- **Recovery artifact.** The render-recovery test no longer writes the refused
  `FrameRequest` to disk. Element frames are not serializable.

## Limits

No native window, real IME, VoiceOver session or packaged app was run.
`EditorWorkbench`'s scripted native scenario was ported but not executed.
