# Sirin status

Last updated 2026-10-30, against `main` at `1d5edcc`. This page says, in plain
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
- **Text.** Real HarfBuzz/FreeType shaping, Unicode segmentation and bidi,
  font fallback across supplied fonts, line wrapping, and caret, hit-testing
  and selection geometry.
- **Desktop applications on the JVM, macOS only.** The JDK 25 FFM development
  host runs `Application.run` with multiple windows, native menus and shortcuts,
  focus and keyboard routing, editable text fields (undo, clipboard, marked-text
  composition), AccessKit accessibility, and a persistent document workbench with
  save, reopen, import and cancellation. Packaged `.app` builds exist.

## What is partial

- **Scala Native desktop.** The kernel and tests run under Scala Native, but no
  Scala Native windowed application runs yet. Native delivery depends on the
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

- The public custom-element and custom-layout API (`Element.apply`,
  `Layout.apply`). It is being built under Alpha4.
- `Checkbox`, `Overlay`, `Popup` and motion (`Presentation`).
- The diagram editor, the inspector, a starter project, and the matched
  ScalaFX comparison.
- Windows and Linux desktop hosts. Those platforms are CI-only.

## Public-seam ledger

VISION §8 requires standard controls to use the same public `Element`,
`LayoutPolicy` and semantics interfaces that applications use. Each entry below
names a control that does not yet do that, and what public capability it lacks.
The goal is an empty ledger.

| Control | Built on | Missing public capability | Tracking |
|---|---|---|---|
| `Text`, `Row`, `Column`, `Scroll`, `VirtualRows`, `DockOutlet` | Host layout and committed tree (`host/`) | `Element.apply` and `Layout.apply` | Alpha4 |
| `Button` | Host interaction routing | `Element.apply`; public pointer and key handling | Alpha4, then seam migration |
| `TextField` | `host/TextEditing.scala`, `desktop/EditorController.scala` | Public text-input and composition events, and text-range semantics for accessibility | Seam migration |
| Menus and shortcuts | Application platform | None; these are application services, not controls | n/a |

## Worktree stocktake, 2026-10-30

Implementation is paused for review. All completed feature branch tips are in
main. The two older dirty source trees match their named archive commits; both
archives are now pushed. New unfinished element/diagram source is also committed
and pushed as `archive/public-elements-wip-20261030` (`0413beb`), with a known
compile failure and incomplete validation. It is deliberately outside main.

Nine worktrees remain, each accounted for in the
[worktree stocktake](plans/2026-10-30-worktree-stocktake.md). This is not a claim
that cleanup is complete: four old clean trees retain generated artifacts; one
holds a frozen launch candidate; another supplies the running Sirin app. The
original checkout also owns the authoritative Mote store. D11 remains open.
