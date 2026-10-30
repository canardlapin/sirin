# Sirin status

Last updated 2026-10-30, for the public diagram tranche. This page says, in plain
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
  [Validation and limits](evidence/2026-10-30-public-toolkit-proof.md).
- **Public text input.** Custom elements receive committed text, IME
  composition and assistive select/replace requests as UTF-16 events. They place
  the IME caret, use staged clipboard and segmentation services, and expose
  text-range semantics. TextField itself is not yet ported.
  [Checks and limits](evidence/2026-10-30-text-input-seam.md).
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

## What is partial

- **Scala Native desktop.** The kernel and tests run under Scala Native, but there is no
  qualified Scala Native application provider. Native delivery depends on the
  open GC safepoint stall (P0.6). The delivery order is to be decided before
  Alpha6 (VISION §1.1).
- **Native platform qualification.** Real input-method sessions, VoiceOver,
  pointer drag, dialogs and modality have not been checked with real clients.
  The packaged apps have not yet been launched for acceptance.
- **Rendering.** The shared-device batch path presents retained GPU frames
  without a full-frame CPU readback. Explicit captures read pixels back; the
  reference/fallback path still reads back and uploads. This is not a zero-copy
  claim. Latency comparisons must identify the path they measured.
- **Text coverage.** Complex paragraphs (for example, repeated unsupported
  emoji) can exceed the shaping budget. Such a frame is refused and the window
  shows a recovery view instead.

## What is missing

- `Checkbox`, `Overlay`, `Popup` and time-varying motion. Constant and mapped
  Presentation values exist; they are not animation.
- The inspector, a starter project, and the matched ScalaFX comparison.
  The diagram still needs real pointer and accessibility acceptance.
- Windows and Linux desktop hosts. Those platforms are CI-only.

## Public-seam ledger

VISION §8 requires standard controls to use the same public `Element`,
`LayoutPolicy` and semantics interfaces that applications use. Each entry below
names a control that does not yet do that, and what public capability it lacks.
The goal is an empty ledger. Button has moved onto the public seam; its
headless interaction and rendering checks do not constitute real-client approval.

| Control | Built on | Missing public capability | Tracking |
|---|---|---|---|
| `Text`, `Row`, `Column`, `Scroll`, `VirtualRows`, `DockOutlet` | Host layout and committed tree (`host/`) | Migration through the now-working seam; preserve virtualization and outlet behavior | Seam migration |
| `TextField` | `host/TextEditing.scala`, `desktop/EditorController.scala` | None. Public text input, composition, editing services and text-range semantics now exist. The control itself still needs porting. | Seam migration |
| Menus and shortcuts | Application platform | None; these are application services, not controls | n/a |

## Current direction and worktrees

Implementation resumed after the owner approved the course correction. Prove
public toolkit authoring first, then application quality and independent authoring,
with the Native GC/delivery decision kept explicit. See the
[execution sequence](plans/2026-10-30-public-toolkit-proof.md).

Four obsolete clean worktrees and their branches were removed after all generated
files were moved to an external artifact directory. The original checkout owns
the authoritative Mote store and must not be reset or cleaned. The GPU-host tree
still supplies a running app; the editor tree retains the approved launch candidate.
Their disposition remains in the [stocktake](plans/2026-10-30-worktree-stocktake.md).
The three WIP archive branches remain on origin. The diagram archive was reference
material; the new client was written against the accepted public seam.
