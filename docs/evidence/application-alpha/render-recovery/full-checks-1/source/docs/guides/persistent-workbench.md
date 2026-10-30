# Build and inspect the persistent workbench

This is a development application using Sirin's public model. Native desktop
support currently means the macOS/JDK25 provider; the shared model also compiles
and tests on Scala Native. Native UI delivery and the complete alpha remain open.

On macOS, install the pinned prerequisites used by `.github/workflows/ci.yml`,
then prepare dependencies from the repository root:

```sh
native/fetch-macos.sh
native/fetch-macos.sh --text
native/fetch-macos.sh --unicode
native/fetch-macos.sh --thorvg
native/fetch-macos.sh --sirin
sbt testJVM testNative examples/compile
sbt --java-home /path/to/jdk-25 desktopJVM/desktopWorkbenchPackage
```

The package is `desktop/target/package-workbench/SirinWorkbench.app`. Open it
through Finder. It has a distinct identity from the preserved SirinAlpha and
SirinEdit probe images. Its default document directory is
`~/.sirin-alpha/documents`; the entry point also accepts `--documents DIRECTORY`.
Package verification does not launch the application:

```sh
python3 -B desktop/tools/portable-image.py \
  desktop/target/package-workbench/SirinWorkbench.app --load-library
```

## Workflow

Create a document, select it in the collection, and edit its title and body. A
changed draft has an asterisk in the collection. Refresh, filter, reorder and
move the editor between panes: the document scope and editor identity survive.
Search is asynchronous and keeps previous results visible while pending.

| Shortcut | Operation |
| --- | --- |
| Command-N | Create a new document |
| Command-R | Refresh search results |
| Command-0 | Open/reopen the workbench |
| Command-I | Open import review/progress |
| Command-S | Save the document shown by the active view |
| Command-W | Close the active view |
| Command-Q | Request guarded application quit |

Saves capture committed field revisions. Continue typing while revision 17 saves:
revision 18 remains dirty until its own save succeeds. An external change refuses
a stale save and retains the draft. Reload requires confirmation and rechecks the
draft after I/O. Save a copy persists an explicit new document and retains the
original draft. Closing a view retains app-owned state/jobs. Closing a document
releases its scope after any required decision. Quit has an application-owned
guard even with no windows; decisions become stale after newer edits.

Import accepts a directory containing Sirin `.sirin-doc` files, not arbitrary text
or CSV. Review freezes the IDs/stamps and shows the denominator before starting.
Existing target IDs produce failed receipts without replacement. Changed source
generations fail individually. Cancel abandons queued UI receipts explicitly;
files already committed remain, and refresh reconciles the resulting collection.
A completed session is one-shot; reviewing again creates a distinct successor.
The last eight sessions are retained in bounded application state.

Document contents persist across reopening. List order remains session-local.
Current bounds include 512 stored documents, 32 cached editors, 128 documents per
import, three additional document views and one import view. The file format
accepts titles of at most 256 UTF-16 units and bodies of at most 16,384. Title,
search and import fields declare their input limits. Oversized user edits refuse
as a whole and show field feedback; provisional IME stays separate until commit.
Programmatic invalid drafts are retained and cannot save. The store uses strict UTF-8, content stamps,
advisory writer locks and atomic replacement. Digest checks are not filesystem
compare-and-swap against an uncooperative writer; power-loss durability remains
unqualified.

If a current render is refused, its window shows an explicit recovery view. The
normal content stays mounted and document state/undo is retained. Queued input
that could not be applied and cancelled composition are reported. Retry renders
the current model explicitly; it does not repeat automatically. The built-in
acknowledge-and-close command still runs the application's dirty-close guard.
The workbench keeps its confirmation controls available without painting failed
document titles. Other windows and application-owned saves remain independent.

Window.renderFailureContent adds application recovery controls. Keep them simple
and independent of failed data. A failed custom recovery view is suppressed once;
a further minimal-panel failure parks rendering while native menus/OS close remain
available. A View menu exposes retry and reviewed close. Developer receipts keep
only the last eight bounded records per window. Explicit source removal remains
application policy; native capture/initialization, cross-mode event delivery and
all real client interactions still require their own qualification.

## Source and extension points

- `desktop/.../PersistentWorkbench.scala`: application entry and service adapters.
- `specimens/workbench/.../DocumentWorkspace.scala`: public application/window,
  selection, retained panels, decisions and import composition.
- `specimens/workbench/.../Documents.scala`: editor revisions, validation and save.
- `desktop/.../FileDocumentRepository.scala`: bounded filesystem implementation.
- `desktop/.../FileDocumentImporter.scala`: public sequential Work composition.

Application code uses Scope/Cell/Command/Work/Windows/Window/Switch/VirtualRows and
reparentable panels. Backend native transport stays in the platform provider.
Repository and importer interfaces let an adapter change storage or import format
without duplicating UI ownership, scheduling or command policies. Work composition
is lazy; cancellation is checked between steps, and attempt exposes failures as
data without catching cooperative interruption.

For an independent authoring review, add a real import format or document feature
through those interfaces, run the whole workflow and compare the change against
the matched ScalaFX application. That review has not yet been performed.

## Qualification boundary

Current tests cover headless public workflow, actual filesystem operations,
keyboard/controller/menu save and file reopen, native compilation and packaging.
Offscreen images are development snapshots, not owner visual approval. Native
menus, true IME candidate interaction, VoiceOver, pointer/drag, full keyboard/focus
and popup/modality behavior still need native client evidence. The current GPU
frame cap is 1,048,576 physical pixels; larger surfaces need bounded streaming,
not an enlarged cap. Emoji coverage, viewport adaptation and scrolling/focus polish remain toolkit
work. Field input/refusal feedback has controller and offscreen checks; actual
IME and assistive-client delivery still need native evidence. The long GC/event
qualification, graphics editor, inspector, clean-checkout/independent review,
matched ScalaFX comparison and every six-part alpha exit condition remain open.
