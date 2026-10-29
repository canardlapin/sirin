# Bounded JVM development host

JDK 25 and macOS arm64 are required. This is a single-window development
integration, using the concurrent graphics/text track in this workspace.
The normal cross-platform engine remains usable on the existing JDK baseline.

Fetch the pinned native window, text, Unicode and ThorVG artifacts through
`native/fetch-macos.sh`; see the individual graphics receipts for source-build
and fixture prerequisites. The desktop build links those existing artifacts.
It introduces no native dependency versions of its own.

```sh
sbt --java-home /path/to/jdk25/Contents/Home desktopJVM/test
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.WindowSpike failures'
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.WindowSpike resize'
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.DesktopWorkbench empty'
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.DesktopWorkbench scenario'
sbt --java-home /path/to/jdk25/Contents/Home 'desktopJVM/runMain sirin.desktop.DesktopWorkbench interactive'
```

The build supplies `-XstartOnFirstThread`, native access and the private library
path. Escape or the window close action exits the interactive launch. Smoke
launches exit after a quiet two-second interval, with a thirty-second ceiling.

The visible specimen has immutable text, Row/Column/padding/grow, fixed-height
virtual rows, clipping, a scroll viewport and a retained document panel. The
scenario requests an oversized resize, empties the list while moving the panel,
then restores rows at a deep scroll offset. These are scripted model changes;
pointer/keyboard controls, focus, text editing, IME and accessibility are the
next slice. Baseline alignment and other decorations reject explicitly.

Capture happens on a bounded worker. Native services and GPU owners are created
and closed there. Only generation-current immutable results can feed viewport
feedback or present. The window uses at most 1280x800 physical pixels. Rendering
uses the graphics track's unchanged reference renderer in tiles, reads back
RGBA and uploads a texture for a fullscreen presentation pass. Logs charge the
program, readback and surface-upload bytes. This bridge has no production
performance or zero-copy claim. Diagnostic PNGs are renderer captures in
`desktop/target/frames`, not OS screenshots.

See [the implementation contract](../docs/plans/2026-10-28-interactive-host.md)
and [host evidence](../docs/evidence/jvm-host-20261028/README.md) for acceptance
limits and the exact combined source snapshot used for validation.
