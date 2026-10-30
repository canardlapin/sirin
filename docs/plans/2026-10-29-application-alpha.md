# Desktop application alpha: six-part programme

Owner goal: “let's work diligently on1–6.” All six deliverables below are required;
this plan does not redefine completion around the first implementation or passing
slice. Authority: VISION, especially1.1,5,6,8 and11. Base main5c73273. Work is
isolated in `/Users/bbuchsbaum/code/scala/sirin-alpha`; preserve the dirty original
checkout and prior frozen receipts. The existing Native/input qualifications
remain open and do not prevent independent application work.

## Required deliverables and acceptance

| Item | Required end state | Authoritative acceptance evidence |
| --- | --- | --- |
| 1 Public application platform | Implement Application.run/Window/Windows, application/document/window ownership, native menus/shortcuts/dialogs, keyed multiple windows, orderly guarded close, main-thread host and pointer-only adapter boundaries | Real public-API app; identity/lifetime/menu/close/error tests on JVM and Native; separate actual host receipts; no remaining launch skeletons or private specimen adaptation |
| 2 Complete persistent workbench | Visible search/select/validate/edit, retained drafts through refresh/reorder/outlet move, save r17 while editing r18, bounded import/cancel, persistence/reopen/dirty-close/conflict/recoverable failure feedback | Entire keyboard-driven workflow, revision/staleness/failure tests, actual reopen and cancellation, external-modification controls, no publication after close or leaked owners |
| 3 Coherent toolkit | Finish required controls, navigation/focus/selection/scroll/popups/dialogs/enabled/error/loading states; typography/spacing/light-dark/scaling | Shared control contracts and interaction tests; reference app operation; owner V-0 visual approval; actual platform input/assistive-client receipts |
| 4 Public diagram editor | Complete custom-element layout/paint/hit/semantics seam; nodes/edges/selection/drag/pan/zoom/undo through public APIs | Complete usable editor, public client compilation, command/geometry/undo/resource checks, real pointer operation and meaningful dense-scene acceptance; no browser-parity inference |
| 5 Developer diagnostics | Bounded inspector for identities/state/dependencies/invalidation/refused commands/work/render/cache; usable starter and build/package docs | Diagnose real application failures, prove bounded retention/overhead and post-close release; clean-checkout build/package; independent feature addition using documented public APIs |
| 6 Alpha acceptance | Green required CI; original long GC/event50ms qualification; actual IME/VoiceOver/drag/keyboard/lifecycle/packaging; fully interactive matched ScalaFX workbench, independent authoring review and owner approval | Exact commit/platform results and raw receipts, complete matched workflows/feature changes, visual approval and explicit supported matrix; every missing/manual result remains incomplete |

Alpha exit: another Scala developer can clone/build/package, complete the full
keyboard workflow, reopen saved documents, diagnose a refused operation and add
a feature through documented APIs. No failed or missing required gate is silently
converted to a pass. Production runtime adoption, Native shipping and platform
support each need their actual evidence.

## Execution and boundaries

Start by reproducing Linux Native CI40974637787, then implement the public shell
and wire the visible workbench. Controls, diagram extension and inspector are
built against actual application requirements. Preserve all six tracks in Mote.
JVM and Native shared-law checks remain required for changes. Kernel transaction,
identity/lifetime/scheduling or command-policy changes require an explicit design
update before implementation; fixes preserving specified semantics are reported
as such. Native callbacks queue native-owned facts and never invoke Scala on a
foreign thread. Public widgets reuse existing Cell/UiTxn/Command/Scope contracts.

## CI baseline and first investigation

Public main5c73273 has7 successful jobs and1 failed job. macOS and Windows core
JVM/Native checks pass; Linux Native ReconciliationAgreementSuite fails with
ClassCastException: ArrayBuffer$$Lambda$1 cannot be cast to IterableOnce.
Failing seed: l8Dl-ZJbwAFzUVsay_UCl1zuWxTHh-oHefe7es6XzbN=.
Freeze the original public log and exact40-step program. Add deterministic replay
without weakening the existing300-case randomized property. Capture the exception
stack and identify its source; a passing rerun alone is not a fix. Linux runner
confirmation is mandatory for closing this issue. Retain all attempted variants.


## First implementation checkpoint (still incomplete)

The exact Linux failure is frozen at b0facfb. Diagnostic run 40980659690
completed three predeclared full Native attempts (327 cases each), including the
exact-seed suite and frozen program. All passed; the original ClassCastException
cause remains unresolved. Full required CI is also enabled on this alpha branch;
its test commands and platform matrix are unchanged.

Window and Windows descriptions now reuse existing Component/ForEach lifetimes,
with live titles/content, transactional close decisions, revision-tagged dismissal
records, and mounted-instance close routing. The explicit design is in
../design/decisions/2026-10-29-application-windows.md. Two failing draft regressions
exposed automatic dismissal after application disposal and key comparisons with
UI write authority; both receive permanent tests. This is the shared model layer,
not completion of item 1 or of the six-part programme.

Next platform implementation must replace the C transport's one-window lifetime
with an application-owned SDL lifetime and bounded global event routing. The
application owner must survive zero open windows; closing a view cannot implicitly
cancel workspace save jobs. Native peer IDs must map to mounted window instances,
with late facts rejected after retirement. Capture and render each window's
content independently using the existing host/render contracts. Implement the
actual Application.run backend, native title updates, menus/shortcuts/dialogs and
orderly application quit. Only then wire the complete persistent workbench into
that public platform. Existing native qualification and all remaining items stay
open; no UI acceptance is inferred from the headless window tests.


## Native application implementation checkpoint (UI acceptance pending)

Application.run now has a backend-neutral service-provider launch path and a real
JVM/JDK25 development provider. The native context owns SDL across zero or more
windows and routes bounded copied input to individual peers. The provider uses
one workspace runtime and existing editor/render/viewport machinery per window;
peer retirement hides immediately and waits for renderer release asynchronously.
Full checks pass 351 JVM + 351 Native + 54 desktop cases and authoring compilation.
The exact sources, initial portability refusal and corrected app-image checks are
in ../evidence/application-alpha/native-application/.

The b320e8a window-model checkpoint is now published on main; main CI40986314862
passes all eight jobs. This does not qualify the new application host. A separate
headless macOS/JDK25 host/package CI job is added for the new implementation.

A concrete SirinAlpha.app is built and its packaged provider/native closure/fonts
and deep strict signature verified without UI. Action-time confirmation for that
new executable is pending; the earlier approval named a different SirinEdit.app.
The scripted multiwindow/failed-open/save/zero-window/reopen/title/quit scenario
has not run, and no native multiwindow/IME/VoiceOver/drag qualification is claimed.
The provider remains JVM development support; a Scala Native UI provider needs
its own implementation/qualification. Native menus/shortcuts/dialogs are the next
platform work (nonempty menus currently fail explicitly). Complete visible
persistence, controls, diagram editor, inspector/starter, independent comparison,
owner visual approval and all original acceptance gates remain required.

A fresh nonqualifying host screen observed 65.71/66.60% idle, still below the
original GC gate's 80% requirement. No 600-second qualification was started and
no thresholds or unrelated processes changed.


## Menu and shortcut implementation checkpoint (native client receipt pending)

Application-owned Windows.menuBar now keeps command dependencies live with zero
windows. Active-window menus merge by top-level title; the JVM provider builds
and publishes native Cocoa menus and consumes revision/ID action facts on the UI
owner. The previous nonempty-menu refusal is removed. Local keyboard bindings
bubble from the focused subtree, followed by window and application chords,
after IME/focused-control editing and activation keys. Disabled matches record
the shared command refusal. Native callbacks never execute Scala.

The additive decision is ../design/decisions/2026-10-30-menus-shortcuts.md.
Shared full checks pass 363 JVM and 363 Native cases plus authoring compilation.
The desktop full baseline passes 60; the final native key-encoding successor
passes the entire 61-case desktop suite. Shared sources are identical between
those receipts. Exact sources, failed fixtures, unchanged candidate hashes and
reconciliation are in ../evidence/application-alpha/menus/.

The preceding f908f6b host checkpoint has nine successful CI jobs, including
headless application packaging. This does not qualify actual menus, windows,
IME or VoiceOver. The original packaged launch candidate remains frozen while
its action-time confirmation is pending. Dialogs, public programmatic quit
services and all remaining platform/workbench/toolkit/graphics/tooling/acceptance
requirements are still open. No six-part deliverable is closed here.


## Persistent document implementation checkpoint (visible workflow unfinished)

DocumentEditor now uses public Scope/Cell/Command/View APIs for editable title/
body validation, committed revision-associated queued saves, dirty/error/refusal
state and a dirty-close prompt signal. Application/document ownership is separate
from window ownership. FileDocumentRepository provides real JVM Work-based I/O,
strict bounded versioned files, exact Unicode reopen, per-session save baselines,
external-change/deletion conflict detection, atomic publication, cancellation
cleanup and explicit locks/bounds. The design and filesystem limitations are in
../design/decisions/2026-10-30-document-workbench.md.

Full checks pass 369 JVM + 369 Native + 69 desktop cases, plus authoring
compilation. Six shared document cases and eight real filesystem cases are
included. Exact source/logs, initial negative fixtures and reconciliation are in
../evidence/application-alpha/documents/. These qualify the component/store
checkpoint, not full application reopen, native filesystem delivery or power-loss
durability. The preceding host/menu checkpoint 0c2ee30 is now on main with all nine
CI jobs successful (40996045243).

Next integrate visible collection/search/select, retained document/draft ownership
through reorder and window/outlet changes, import/cancel, recoverable conflict/
dirty-close dialogs and actual persistent application startup/reopen. Native
menus/IME/VoiceOver/drag still need client receipts. Dialog/programmatic quit,
remaining controls, public graphics editor, inspector/starter, long GC and full
alpha comparison/visual gates remain open; no numbered deliverable is closed.


## Integrated persistent workspace checkpoint (native acceptance pending)

The public DocumentWorkspace now integrates asynchronous collection search,
retained document/draft ownership through refresh/filter/reorder/outlet moves,
multiple document views, actual filesystem startup/save/reopen, copy/reload and
external-change feedback. Import has a separate reopenable view, frozen source
IDs/stamps, explicit receipt denominators, per-file failures and cancellation that
retains committed files. ApplicationServices.quit and Windows.onQuitRequest reuse
existing transaction/command publication laws and guard dirty drafts even with
zero windows. Queued window close cannot overtake preceding owner input.

Full checks pass 384 JVM + 384 Native + 79 desktop cases, authoring compilation
and the distinct SirinWorkbench.app package. A public controller/shortcut/render
workflow saves and reopens exact Unicode through the real filesystem adapter
without opening a native window. A full workspace rendering failure exposed
redundant ancestor clip nesting. Exact frozen frame replays fail under old host
traversal and pass under the successor with every renderer budget unchanged.
Sources, failures, controls, counts, image and package hashes are frozen in
../evidence/application-alpha/workspace/. Build/workflow guidance is in
../guides/persistent-workbench.md.

The preceding document checkpoint fce32bf has all nine CI jobs successful
(40998156355). That result is not CI qualification for this successor. The new
workbench app has native closure, deep strict signature and no-UI library-load
checks; native launch confirmation and actual operation remain pending. Current
GPU physical-pixel limits, viewport adaptation, emoji coverage, field/diagnostic
limits, focus/scrolling, native dialogs/modality, IME/VoiceOver/drag and full
lifecycle acceptance remain toolkit/platform work. Diagram editor, inspector,
clean-checkout/independent authoring, matched ScalaFX comparison, visual approval,
long GC gate and the original Linux Native failure investigation remain open.
No numbered deliverable is closed by this checkpoint.
