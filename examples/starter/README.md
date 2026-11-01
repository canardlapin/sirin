# ReviewPad: an independent Sirin consumer

Copy this directory outside the Sirin checkout. It is a standalone sbt build:
no source dependency, private framework import or Sirin build plugin. ReviewPad
uses public `Application`, `Scope`, `Command`, `Work`, controls and popup APIs to
edit and persist a title, multiline notes and a review-complete checkbox.

The currently available development provider is macOS arm64 on JDK 25. Sirin
remains unpublished. This starter consumes a local binary SDK exported from a
verified development app image; it does not establish Native application delivery
or a redistribution release. Shared framework tests still run on Scala Native.

## Prepare the SDK in a clean Sirin checkout

Fetch the pinned native artifacts using the repository's documented
`native/fetch-macos.sh` options. Then, with the exact JDK path for your machine:

```sh
sbt --java-home /path/to/jdk-25 desktopJVM/desktopWorkbenchPackage
python3 desktop/tools/export-sdk.py \
  desktop/target/package-workbench/SirinWorkbench.app /path/to/new/sirin-sdk
```

The exporter refuses to replace an existing directory. `sdk.json` records all
file hashes and symbolic links, platform/compiler requirements and the source
commit/dirty state. Keep that SDK intact. Packaging verifies its full inventory.

## Build and package the copied consumer

Set `SIRIN_SDK` to that exported directory and run in your copied starter:

```sh
SIRIN_SDK=/path/to/sirin-sdk sbt --java-home /path/to/jdk-25 clean test packageApp
```

The result is `target/package/SirinReviewPad.app`. Its Java runtime, Sirin binary,
native libraries and fixture fonts are bundled. Launch through Finder. The default
note is `~/.reviewpad/review.note`; the launcher also accepts `--file PATH`.
For an isolated acceptance image, package the compiled application JAR explicitly:

```sh
python3 package.py --sdk /path/to/sirin-sdk --java-home /path/to/jdk-25 \
  --jar target/scala-3.7.4/reviewpad_3-0.1.0-SNAPSHOT.jar --file /path/to/test/review.note
```

`package.py` replaces only this project's `target/package` and
`target/package-input` build outputs. `target/package/receipt.json` ties the
application JAR and native library to the SDK manifest. A package build never
launches the application. It needs no Sirin source checkout after SDK export.

## Workflow and ownership

Edit Title and Notes, toggle Review complete, save with the button or Command-S,
quit and reopen. The checked state and exact text persist. Edits made while a
save runs remain dirty if they differ from its snapshot. An empty title cannot
save. Quit/close with an unsaved note opens a modal decision; Escape or Keep
editing returns to the draft. Quit while saving waits for that work to finish.

The application scope owns the draft and jobs; windows render that state. Loading
and saving are lazy `Work` values. The UI transaction captures a save snapshot;
completion updates only the saved baseline, preserving newer edits. No filesystem
I/O runs in the UI transaction. A failed open shows Retry and never silently
replaces a corrupt file with a blank note. Failed saves retain the draft.

The small versioned UTF-8 file has a 128 KiB bound. Writes use a same-directory
atomic replacement and an advisory writer lock. A content stamp refuses another
instance's stale save. An uncooperative writer is not protected by filesystem
compare-and-swap, and power-loss durability is not qualified. After a conflict,
quit without saving and reopen the file; this starter has no merge editor.

`NoteStoreSuite` tests exact Unicode/checkbox round trips, conflicting saves,
corrupt/oversized input and invalid-edit preservation. Loaded text must contain
well-formed Unicode without NUL; escaped malformed text in a properties file
is refused before opening it. Real-window acceptance must separately check
editing, save/reopen, validation, dirty quit and checkbox
behavior. Building an image is not that acceptance result.
