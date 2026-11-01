# Sirin status

Last updated 2026-10-31, for workbench window checks. This page says, in plain
language, what works today. Update it whenever what works changes (VISION §11).
Detailed claims and their evidence are in `docs/plans/` and `docs/evidence/`.

Sirin is a pre-release prototype. Nothing is published.

## What works

- **Headless kernel, on the JVM and Scala Native.** Cells, signals,
  transactions, commands with work policies, scopes and lifetimes, keyed
  collections, fixed-height virtual lists and retained outlets. The shared test
  suites run on both platforms in CI (macOS, Windows, Linux).
- **Standalone graphics.** Immutable drawing recordings with paths, strokes,
  gradients, images, clips, transforms and layers. An offscreen wgpu renderer
  checks pixels against reference output.
- **First public toolkit proof.** Custom Element and Layout descriptions run
  through guarded committed phases. Element.simple provides one-state authoring.
  The graphics-ui adapter exposes the full standalone drawing vocabulary with
  shared logical units. Button now uses this public seam, including prepared
  measurement text, input activation, semantics and focus/press feedback.
  `Text`, `Row` and `Column` do too; text now exposes a label to accessibility
  snapshots. [Port](design/decisions/2026-10-31-layout-controls.md). `Scroll`
  is a public composition, and `VirtualRows` places rows with a public layout
  policy fed by public viewport feedback.
  [Port](design/decisions/2026-10-31-viewport-controls.md). `Checkbox` is a
  public element with checked semantics. The native bridge now carries its
  role, state and activation. [Control evidence](evidence/2026-10-31-checkbox.md),
  [native transport](evidence/2026-10-31-native-semantic-tree.md). `Overlay` is a public
  layout. `Popup` is mounted under its originator and shown in a window overlay
  layer with placement, dismissal, focus restoration and modality.
  [Design](design/decisions/2026-10-31-overlay-popup.md),
  [evidence](evidence/2026-10-31-overlay-popup.md).
  Modal input isolation also holds when focus cannot move; nested popups bubble
  shortcuts through their parent anchors. Shared JVM/Native and desktop checks
  pass. [Repair evidence](evidence/2026-10-31-popup-input-repair.md).
  [Validation and limits](evidence/2026-10-30-public-toolkit-proof.md).
- **Public text input and TextField.** Custom elements receive committed text,
  IME composition and assistive select/replace requests as UTF-16 events. They
  place the IME caret, use staged clipboard and segmentation services, receive
  focus events and expose text-range semantics. TextField is built on these
  public APIs alone; the host's private editor is gone. Real IME and VoiceOver
  sessions have not been run on the port.
  [Seam](evidence/2026-10-30-text-input-seam.md),
  [port](evidence/2026-10-30-textfield-port.md).
- **Motion.** `scope.animate` gives scope-owned animations on the runtime
  clock. A new target continues from the current value and velocity. Content
  that is not on screen settles instead of animating and schedules no frames.
  Under reduced motion, a change shows its final state in the next frame.
  `offset` moves drawing, hit testing and semantics together; `opacity` and
  element presentation are paint only. The diagram lifts dragged nodes. The
  application host follows the macOS Reduce Motion setting; switching it on a
  real machine has not been tried.
  [Design](design/decisions/2026-10-30-motion.md),
  [evidence](evidence/2026-10-30-motion.md).
- **Public diagram client.** Application code outside the `sirin` package uses
  Element, named Layout, commands and the graphics adapter for curved connections,
  gradient nodes, prepared labels, selection tools, drag/cancel, pan/zoom and bounded
  undo/redo. Automated host and GPU checks include 512 nodes and all their labels.
  A separate packaged entry exists; real-client acceptance remains open.
  [Controls and limits](guides/diagram.md).
- **Text.** Real HarfBuzz/FreeType shaping, Unicode segmentation and bidi,
  font fallback across supplied fonts, line wrapping, and caret, hit-testing
  and selection geometry.
- **Desktop applications on the JVM, macOS only.** The JDK 25 FFM development
  host runs `Application.run` with multiple windows, native menus and shortcuts,
  focus and keyboard routing, editable text fields (undo, clipboard, marked-text
  composition), AccessKit accessibility, and a persistent document workbench with
  save, reopen, import and cancellation. Packaged `.app` builds exist.
  A real-window check created, edited, saved and reopened exact test content.
  Reopening after closing the last window exposed an AccessKit class-setup
  crash, now repaired and checked in a native regression and a packaged window.
  The native last-window close policy is also repaired: real-window checks
  retained unsaved drafts across zero windows, restored Quit consent from that
  state, and exited only after explicit Quit. Decisions now appear in a visible
  modal popup; Escape restores editing focus. The editor fills the space beside
  the document list, and Save stays visible at the default size. These paths
  passed fresh-package window checks and rendered regressions at two sizes.
  [Usability evidence](evidence/2026-10-31-workbench-visible-decisions.md).
  [Close-policy evidence](evidence/2026-10-31-workbench-native-close.md).
  Foreground Finder launch renders; background presentation and visual usability
  remain under investigation.
  [Window evidence](evidence/2026-10-31-workbench-window-lifecycle.md).

## What is partial

- **Scala Native desktop.** The kernel and tests run under Scala Native, but there is no
  qualified Scala Native application provider. Native delivery depends on the
  open GC safepoint stall (P0.6). The delivery order is to be decided before
  Alpha6 (VISION §1.1). A separate Native executable reaches the same seven
  accessibility ABI functions as JVM FFM; that is a binding check, not a provider.
- **Native platform qualification.** Real input-method sessions, VoiceOver,
  physical pointer drag and trackpad behavior remain unqualified. Packaged
  workbench modal decisions have been exercised with synthetic pointer and
  keyboard input. Native Dialog, label and Checkbox transport is implemented;
  the fresh workbench exposes a dialog with explanatory text and excludes
  background document controls from the modal AX tree. Native actions for text,
  buttons and custom parts are checked. Actual VoiceOver remains unqualified.
  [Transport evidence](evidence/2026-10-31-native-semantic-tree.md).
- **Rendering.** The shared-device batch path presents retained GPU frames
  without a full-frame CPU readback. Explicit captures read pixels back; the
  reference/fallback path still reads back and uploads. This is not a zero-copy
  claim. Latency comparisons must identify the path they measured.
- **Text coverage.** Complex paragraphs (for example, repeated unsupported
  emoji) can exceed the shaping budget. Such a frame is refused and the window
  shows a recovery view instead.

## What is missing

- The inspector, a starter project, and the matched ScalaFX comparison.
  The diagram still needs real pointer and accessibility acceptance.
- Windows and Linux desktop hosts. Those platforms are CI-only.

## Public-seam ledger

VISION §8 requires standard controls to use the same public `Element`,
`LayoutPolicy` and semantics interfaces that applications use. Each entry below
names a control that does not yet do that, and what public capability it lacks.
The ledger has no controls left. Button, TextField, Text, Row, Column, Scroll and
VirtualRows use the public seam. `VirtualRows` row realization and `DockOutlet`
placement are kernel structure, like `ForEach` and `Switch`, and have no
private host code. The headless interaction and rendering checks do not
constitute real-client approval.

| Control | Built on | Missing public capability | Tracking |
|---|---|---|---|
| Menus and shortcuts | Application platform | None; these are application services, not controls | n/a |

## Current direction and worktrees

Implementation resumed after the owner approved the course correction. Prove
public toolkit authoring first, then application quality and independent authoring,
with the Native GC/delivery decision kept explicit. See the
[execution sequence](plans/2026-10-30-public-toolkit-proof.md).
The active [workbench acceptance tranche](plans/2026-10-31-workbench-acceptance.md)
requires real application workflows and keeps Scala Native runtime and delivery
work explicit.

Four obsolete clean worktrees and their branches were removed after all generated
files were moved to an external artifact directory. The original checkout owns
the authoritative Mote store and must not be reset or cleaned. The GPU-host tree
still supplies a running app; the editor tree retains the approved launch candidate.
Their disposition remains in the [stocktake](plans/2026-10-30-worktree-stocktake.md).
The three WIP archive branches remain on origin. The diagram archive was reference
material; the new client was written against the accepted public seam.
