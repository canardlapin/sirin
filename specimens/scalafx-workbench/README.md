# Persistent ScalaFX workbench baseline

This macOS ARM64 application supplies native JavaFX controls for the persistent
editing comparison with Sirin. It uses ScalaFX 26.0.0-R38, JavaFX 26 and JDK 25.
It does not depend on Sirin's runtime. The original `Workbench.scala` remains a
controller specimen; the packaged application does not use it.

Build and check from the repository root with JDK 25 selected:

```sh
sbt 'scalafxWorkbench/test' 'scalafxWorkbench/scalafxWorkbenchPackage'
```

The image is `target/package/ScalaFXWorkbench.app` beneath this directory. The
packaging task does not launch it. It includes the selected JDK runtime and
places the three JavaFX modules on the module path. Acceptance currently targets
macOS ARM64; this packaging task does not claim cross-platform support.

The entry point accepts `--documents DIRECTORY`. Without arguments it uses
`~/.sirin-scalafx/documents`. The prepared acceptance copy at
`/private/tmp/sirin-scalafx-acceptance-20261101/ScalaFXWorkbench.app` has fixed
arguments selecting its sibling `documents` directory. Use that isolated copy
for testing, rather than the default document directory.

## Source and behavior

- `DocumentStore.scala` implements strict Sirin v1 file compatibility, digest
  conflict checks, writer locks, atomic save replacement and no-overwrite create.
  The contract follows Sirin's adapter; this is not independent authorship.
- `PersistentWorkspace.scala` owns drafts and asynchronous JavaFX Tasks, with
  explicit retirement and late-result guards. Filtering, reordering and refresh
  preserve a retained draft. Older saves cannot mark newer edits clean.
- `PersistentWorkbench.scala` supplies native text controls, validation, menus,
  pane moves, detached editors, import review/results and close/quit decisions.
  Moving the same editor preserves its control state and undo history.
- `workbench.css` supplies the ordinary control typography and spacing.

The workspace has at most 32 cached drafts, 512 listed documents and three
detached editor windows. Search is debounced by 150 ms. General work has two
workers and eight waiting tasks. Saves have one worker and 96 waiting tasks,
with at most one active and two waiting saves per draft. Import reviews freeze
at most 128 source identities and stamps; receipt capacity is two and each JavaFX
pulse drains at most eight receipts. Cancel preserves already committed files.
Recent import history retains eight sessions. These limits are visible in the
implementation and must be stated in any performance comparison.

The storage adapter shares Sirin's limitations for uncooperative writers and
power-loss durability. It is a comparison specimen, not a new storage format.
Use native JavaFX controls and Task lifecycle conventions when extending it:
[ScalaFX documentation](https://scalafx.org/docs/home/) and
[JavaFX Task](https://openjfx.io/javadoc/26/javafx.graphics/javafx/concurrent/Task.html).

## Verification boundary

Six storage tests and ten workspace/control tests pass. They cover exact fixture
bytes, validation, conflicts, cancellation, retained drafts, stale publication,
save queues, import receipts, native text-control bounds and offscreen layouts.
No test shows a window. Native menu routing, OS quit/reopen, actual input,
accessibility and visible window behavior still require application testing.

The [matched task plan](../../docs/plans/2026-11-01-matched-persistent-workbench.md)
defines the next visible comparison. Graphics-rich workflows, independent
authoring review, paired presentation measurements and owner approval remain
open. Neither this build nor its tests establish superiority for either toolkit.
