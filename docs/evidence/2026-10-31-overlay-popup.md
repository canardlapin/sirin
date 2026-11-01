# Overlay and Popup, 2026-10-31

## Claim

`Overlay` and `Popup` were `???` placeholders; both now work.
- `Overlay` is a public layout.
- A `Popup` is mounted under its originator only while it is open. The desktop
  host shows it in a window overlay layer: placed against its anchor, opaque to
  input, dismissed through shared rules, with focus moved in on open and
  restored on close.

A-0 corpus program 4, the rename-layer popup, runs end to end on the desktop
host. Each behavior follows a numbered decision in
[the design note](../design/decisions/2026-10-31-overlay-popup.md).

## Checks and results

Run on macOS arm64 with JDK 25.0.4.1, from task branch `popup-20261031`.

- **`PopupSeamSuite` (desktop, real text and renderer): 5 new tests.** Program 4
  is copied in, with its layer cell lifted out so the test can observe it.
  - **Opening (D2, D3, D11, D14).** Rename opens a `Role.Dialog` named "Rename
    layer" at least 4 units below the button, and the name field has focus.
  - **Escape (D5, D12, D15).** Escape in the field dismisses the popup, and
    focus returns to the Rename button.
  - **Outside press (D6).** With an edited draft, a press on the Locked
    checkbox is vetoed: the popup stays open and the checkbox is not toggled.
    The Rename button commits the name and restores focus. With an unedited
    draft, the same press dismisses the popup, and is still consumed.
  - **Tab, shortcuts and effects (D10, D13).** Six Tabs keep focus among the
    field, Cancel and Rename. The header's Cmd-Shift-R shortcut resolves from
    inside the popup. Locking the layer closes the popup through the program's
    effect, and focus returns to the now disabled Rename button.
  - **Previous focus and ownership (D1, D12).** Opened by command from the
    focused Notes field, Cancel returns focus to Notes. Replacing the
    originator removes the popup and all its semantics.
  - **Modality and placement (D3, D9, D12).** A modal popup with no room below
    its anchor opens above it. A press beneath it does nothing and dismisses
    nothing. Four Tabs stay inside it. Escape closes it, and focus falls back
    to the anchor.
- **`PopupSuite` (engine, JVM and Native): 1 new test.** Closed content is not
  mounted. Opening mounts it; closing disposes its state; reopening builds
  fresh state; disposing the originator disposes the popup.
- **`PopupLayoutSuite` (host): 2 new tests.**
  - Overlay layers take the base's size and the last one is on top.
  - Placement: the exact positions for all five placements, flipping above
    near the bottom, clamping inside the right margin, and falling back to 0
    when the popup is too wide for its margins.
- **Picture.** `desktop/target/popup-seam/open.png` shows the open popup: a
  raised, bordered surface below and right-aligned to Rename, about 400 wide,
  with the label, field, Cancel and Rename readable over the base. The first
  render had no surface and was full width. D16 and D17 fixed both.

Suite results: `testJVM` 442, `testNative` 442, `desktopJVM/test` 121,
`probeParagraph/test` 40, and `examples/compile` passed.

## Limits

- No native window was run. Real pointer, trackpad and VoiceOver use of popups
  is unqualified.
- Native accessibility transport does not carry dialogs or checkboxes.
- There is no relation between a dialog and its anchor, no shadow, and no
  open or close motion.
- A modal popup does not dim the base.
- The bounded `DesktopWorkbench` does not route popup dismissal or popup focus,
  because it drives `Interaction` directly. The application and editor hosts
  go through `EditorController`, which does.
- When focus arrives programmatically, the caret starts at the beginning of the
  field. The field's text is not selected on focus.
