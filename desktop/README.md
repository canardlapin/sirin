# Bounded JVM development host

JDK25 and macOS arm64 are required. This local development integration renders
the public workbench using Sirin's shared graphics/text code. The engine keeps
its existing JDK baseline. Fetch pinned artifacts with `native/fetch-macos.sh`;
the component receipts describe the font, Unicode and ThorVG prerequisites.

```sh
sbt --java-home /path/to/jdk25/Contents/Home desktopJVM/test
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.WindowSpike failures'
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.DesktopWorkbench scenario'
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.DesktopWorkbench benchmark'
sbt --java-home /path/to/jdk25/Contents/Home desktopJVM/desktopPackage
```

The package task produces `desktop/target/package/Sirin.app`, with bundle ID
`io.github.canardlapin.sirin.dev`. It includes this JDK runtime and points to local
native libraries and fixture fonts. It is a local app image, not a distributable
installer. The build supplies first-thread, native-access and library options.
Window close or Escape exits.

The specimen supports Tab/Shift-Tab, Enter/Space, pointer command activation,
disabled command refusal, wheel/Page/arrow/Home/End scrolling, and a retained
panel move that preserves focus. Focus intents and status use public NodeRefs.
The scripted scenario also checks a newly mounted focus target without another
native event, oversized resize clamping, and native close. Text editing, IME,
native control accessibility and baseline alignment remain open.

One dedicated Scala-created render thread owns persistent font, Unicode and GPU
services. Text, masks, base tiles and composited tiles have finite caches. Current
immutable results alone may feed viewport feedback or present. The shared GPU
renderer paints changed tiles in bounded native batches, reads back RGBA and
uploads it for presentation. The window admits at most1280x800 physical pixels;
this bridge makes no zero-copy or production performance claim.

The benchmark refuses geometry outside600x360 logical /1200x720 physical
pixels and uses100 records. Fixed warm-up is20
action plus20 scroll turns, followed by100 measured samples/class. The CSV in
`desktop/target/input-to-present.csv` retains both phases. Targets, prior failures,
OS observations and source identity belong in the
[responsive receipt](../docs/evidence/responsive-host-20261028/README.md).

After packaging, measure without a concurrent sbt/compiler process:

```sh
python3 -B desktop/tools/run-latency.py
python3 -B desktop/tools/run-latency.py --diagnostic
```

The default runner requires three quiet interval samples (each85% CPU idle and
load1m at most half the logical CPU count) before launching. It preserves a fresh
receipt under `desktop/target/latency-runs/` even when preflight refuses. Diagnostic
runs are always unqualified. Both modes keep raw samples, per-input targets/labels,
geometry, handler/queue/worker/present timings, cumulative GC counters, startup
timing from main entry, and exact package hashes. Independent receipt validation
checks counts, geometry and the unchanged targets. The
[follow-up evidence](../docs/evidence/responsive-latency-20261028/README.md) retains
the busy-host refusal and diagnostic miss. `benchmark benchmark-resize-control`
as main arguments exercises resize refusal after the first presentation.

Optional `-Dsirin.capture=true` writes renderer PNGs; these are separate from OS
screenshots. The [plan](../docs/plans/2026-10-28-responsive-workbench.md) defines
this slice; [the earlier receipt](../docs/evidence/jvm-host-20261028/README.md)
retains the original host baseline.

## Retained GPU candidate

The default workbench in `codex/retained-gpu-host-20261028` uses the bounded batch
renderer with a window-owned device shared with the render worker. Completed
immutable tile textures present directly to the surface. Normal frames report
zero readback bytes; optional `-Dsirin.capture=true` explicitly reads pixels.
The older PixelProgram renderer and its tests remain as reference comparisons.

Unchanged requests skip layout, drawing/tile preparation, compilation, upload
and dispatch. Focus/press changes reuse prepared base commands; changed tiles
combine base and overlay before sample resolution. Resizing or changing scale
invalidates target-dependent variants. Cache variants, exported texture bytes and
outstanding frame packets have separate enforced bounds, including references
surviving eviction. See `docs/plans/2026-10-28-gpu-host.md` for scope and gates.
