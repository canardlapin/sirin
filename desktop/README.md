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

The benchmark uses100 records at1200x720 physical pixels. Fixed warm-up is20
action plus20 scroll turns, followed by100 measured samples/class. The CSV in
`desktop/target/input-to-present.csv` retains both phases. Targets, prior failures,
OS observations and source identity belong in the
[responsive receipt](../docs/evidence/responsive-host-20261028/README.md).
Optional `-Dsirin.capture=true` writes renderer PNGs; these are separate from OS
screenshots. The [plan](../docs/plans/2026-10-28-responsive-workbench.md) defines
this slice; [the earlier receipt](../docs/evidence/jvm-host-20261028/README.md)
retains the original host baseline.
