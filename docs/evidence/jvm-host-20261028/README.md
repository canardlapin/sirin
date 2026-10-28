# Bounded JVM host receipt, 2026-10-28

The JDK25 macOS development shell and pure committed host/layout nucleus are
implemented and verified at the bounded scope below. This is the first visible
development-host slice in the [interactive-host plan](../../plans/2026-10-28-interactive-host.md),
not the operable workbench or JVM shipping/usability qualification.

Base: `ae1817f`, branch `investigate/gc-threading`, macOS arm64. Validation ran
from `/tmp/sirin-host-validation-20261028`, a frozen source copy with separate
build outputs and a symlink to the existing pinned vendor artifacts. This avoids
concurrent graphics compiler writers. Scala 3.7.4, sbt 1.12.14, ordinary Scala
Native **0.5.12**. Platform checks use Java 21.0.12.1; desktop checks explicitly
use Java 25.0.4.1. The pinned-upstream GC/pause experiment was not rerun.

## What runs

A process-main-thread SDL/wgpu window receives pointer-only FFM downcalls.
C callbacks own native request records and never call Scala/JVM. The UI owner
captures declared committed values through an expiring read-only reader. A
bounded existing Work transport prepares actual font/Unicode/paragraph results,
full layout, recordings and immutable RGBA on Scala-created workers. Generation
validation rejects obsolete frame and viewport results. Viewport feedback settles
before presentation. The host feeds actual placed/clipped geometry to fixed-height
virtual rows and clamps scroll offsets when content shrinks.

Public descriptions now implement Text/style/color, Spacer and Scroll. Structural
snapshots retain layout decorations, stack orientation/gap/alignment and virtual
row height. The pure host supports Row/Column, padding, explicit width/height,
grow, Start/Center/End/Stretch, vertical scrolling and clipping. Baseline and other
unimplemented decorations reject. Captures cap nodes/depth/text storage.

The adapter reuses the graphics track's actual C font/Unicode/GPU services and
shared NativeParagraph, NativeTextServices, PixelProgram and shader sources.
It adds FFM transports, not another shaping or raster algorithm. Bounded tiling
preserves painter order, clip/layer semantics and the shared renderer's per-tile
work/memory budgets. The development window is limited to **1280x800 physical
pixels**, including scale re-clamping. The display path reads back RGBA and
uploads an sRGB texture for a fullscreen GPU presentation pass. Copy costs are
reported. Per-frame service creation/full relayout/readback are intentionally
unoptimized; this is not the production resource/atlas or zero-copy path.

## Verification

Both commands exited **0**, with no failed or ignored tests:

```sh
sbt testJVM testNative examples/compile
sbt --java-home /path/to/jdk25/Contents/Home \
  desktopJVM/test \
  'desktopJVM/runMain sirin.desktop.WindowSpike failures' \
  'desktopJVM/runMain sirin.desktop.WindowSpike resize' \
  'desktopJVM/runMain sirin.desktop.DesktopWorkbench empty' \
  'desktopJVM/runMain sirin.desktop.DesktopWorkbench scenario'
```

| Project | JVM | Native |
|---|---:|---:|
| graphics (concurrent track) | 59 | 59 |
| core (including 2 concurrent text API tests) | 9 | 9 |
| textUI (concurrent track) | 2 | 2 |
| graphicsUI (concurrent track) | 6 | 6 |
| engine | 174 | 174 |
| testkit/reference models | 30 | 30 |
| workbench | 7 | 7 |
| host | 6 | 6 |
| desktop FFM/native services/GPU comparison | 4 | — |

**590 observed test executions**. The public authoring corpus also compiles
13 sources. These checks do not replace the graphics track's isolated Native
font/Unicode/paragraph/renderer corpus or backend decision.

- C compiler ABI sizes/offsets and a by-value color round trip pass through the
  pointer-only FFM shim. Window creation off the process main thread rejects.
- Injected failures after each of eight acquisitions (SDL, window, Metal view,
  instance, surface, adapter, device, queue) end with zero live windows and
  request records. Resize/wake/quiet-window smoke reports `firstThread=true`,
  `pixels=1280x800`, worker wake delivery, zero quiet-interval presentations and
  clean shutdown. This measures presentation count, not idle CPU/power.
- Real-font shaping/Unicode wrapping and retained editing geometry work through
  FFM. Owned geometry remains readable after native owners close; using a
  closed native font rejects.
- The independent tile/full GPU comparison includes fractional rectangular and
  triangular clips, nonuniform/sheared glyph transforms, images and isolated
  opacity. Edges 16/23/32 at scales 1/2 all have **maximum channel error 0**
  (the predeclared tolerance was at most 1). This is bounded fixture coverage,
  not arbitrary-affine/browser-parity qualification.
- The empty initial list presents generation 1 after one zero-height feedback
  pass, guarding the unchanged-payload presentation case.
- The scripted scenario presents four settled frames: initial workbench,
  oversized resize clamped to 1280x800, empty list plus retained-panel move,
  then restored rows scrolled to offset 1800. Four feedback passes, zero native
  wake failures and no pending driver work remain. Measured worker rendering
  is roughly **1.1–1.7 seconds/frame** on this busy development host; no input
  responsiveness or performance-superiority claim is made.
- Pure tests cover independent space-allocation arithmetic, allocated-axis
  alignment, scroll/virtual geometry, stale/restored/retired generations,
  committed-reader expiration/phase guards, runtime-relative timer origins,
  and wake detachment/failure accounting.

Raw [desktop log](desktop.log) and [platform log](platforms.log) are preserved.
The [initial workbench](workbench.png), [empty moved panel](empty-moved.png) and
[deep-scroll frame](scrolled.png) are renderer PNGs for generations 2/5/7 that
the native surface reported presenting. They are **not OS screenshots**.

## Review and remaining gates

The existing source reviewer found six issues during implementation: mixed clock
origins; acquisition/worker cleanup gaps; allocated-cross-axis alignment; marking
a frame accepted before zero-height feedback settled; lifetime rather than
consecutive feedback budgeting; and unbounded physical resize. All were fixed
and reviewed again. Final review found no remaining bounded source blocker;
see [the review record](../../design/decisions/2026-10-28-jvm-host.md).

Native request records survive owner timeout by reference ownership, but the
fault controls above fail after successful requests complete. **Injected timeout
and late-callback behavior remain unqualified.** No leak-free timeout claim is
made. A previously captured wake callback may finish after detachment; native
wake serializes with close and rejects a closed window.

The desktop automation connector could not identify/bind the unbundled Java
process by inventory, executable name or window title. Therefore OS screenshot,
manual live drag-resize/scale/occlusion and visual acceptance remain open. This
also leaves quiet-window CPU/power, package/launch ergonomics, Native UI launch,
controls/focus/hit dispatch, real scrolling input, editing/undo/composition,
clipboard, accessibility/VoiceOver and the matched ScalaFX usability decision
open. Source-level native surface success is not substituted for those gates.

## Ownership and reproducibility

No graphics-owner files were edited or staged by this tranche. This remains a
**working-tree integration candidate**, not a clean-checkout release or standalone
landed host commit. The shared build and concurrent graphics/text modules are
still uncommitted. Keep their ownership separate when landing.

[Owned source hashes](owned-source.sha256) match the frozen tested source.
[Combined source/font hashes](combined-source.sha256) identify all observed
compiled source inputs, including concurrent work. The
[observed build](observed-build.sbt) is a receipt, not another active build.
[Current-source drift](current-source-drift.txt) lists any concurrent source
changes after the frozen snapshot; the receipt qualifies the frozen hashes.
The [tested source archive](tested-source.tar.gz) preserves the compiled source,
font fixtures/licenses and native fetch/build scripts, so later concurrent edits
do not erase the tested candidate. Its [SHA-256](tested-source.tar.gz.sha256) is
recorded. Extract it into a fresh directory and fetch the pinned artifacts before
running the commands above. This archive does not import or land the graphics
owner's work. Vendor binaries and build outputs remain untracked. Re-fetch using the existing
pinned scripts and font manifests; do not interpret the hashes alone as a
replacement for the native artifact receipts.
