# First interactive Sirin host and JVM usability experiment

Status: bounded development-shell slice implemented; operable-workbench and
usability qualification remain open. 2026-10-28.
Authority: [VISION](../design/VISION.md). Builds on the headless workbench,
retained outlets and fixed-height virtualization. Graphics/text changes are a
concurrent track: their own receipts and ownership rules remain authoritative.

## First app

One macOS window runs the public workbench model: type a search, select and edit
a record, refresh/reorder while an edit is dirty, scroll a filtered list, move
the editor between two outlets, save a document while continuing to type,
import rows with bounded progress, and cancel import while its producer is
blocked. Show loading, validation, stale-result, conflict, cancellation and
failure feedback in the window. Keyboard operation is required for every step.
The same source model must run on JVM and Native. The bounded JDK 25 FFM development shell now loads the existing pointer-only
services, validates the process main thread and ABI, parks/wakes, resizes and
cleans up. A committed tree/layout/text/recording path presents the static
workbench and scripted model changes through an owned offscreen/readback/upload
bridge. See the [host receipt](../evidence/jvm-host-20261028/README.md).
This does not change VISION §12's Native-first shipping order or qualify JVM
shipping. The complete interaction workflow above remains unimplemented; Native
launch and OS-level visual/interaction acceptance each need their own receipt.

Retained editor placement keeps its owner, draft and work. Virtual row-local
state retires outside the realized range; persistent editing/selection belongs
to the workspace or explicitly retained editor. Filtering away the selected
record must follow a declared product policy (keep an off-list editor with an
explicit label, or close after confirmation if dirty). Deleting the record
clears selection and prevents stale activation. Do not equate filtering with
deletion in a filtered-source changes handler.

## Boundaries to implement

| Boundary | Existing concrete input | Required first implementation and acceptance |
|---|---|---|
| Shell and lifecycle | SDL3/wgpu pointer-only shim and Stage 0 window probes; `Application`, `Windows`, `Window` remain skeletons | Process main thread creates the window. Scala-created workers own slow work. Native callbacks enqueue POD facts; no Scala runs on foreign threads. Closing disposes the window scope before releasing shell resources. |
| Owner driver | `HeadlessRuntime.drive`, activity wakeups, monotonic deadlines, event/work/progress capacities | One serialized UI owner drains bounded work and shell facts; park until activity/deadline. A shell wakeup must wake a parked owner. Display invalidation requests one frame; no idle frame loop. Measure quiet-window active turns, frames and CPU. |
| Structural identity | `MountedSnapshot`, `RefAttachment`, stable component/retained IDs, per-node `MountedView.setViewport` | Build a committed host tree with epoch-tagged geometry and attachment identities. Ref focus methods are currently deferred. Add focus ownership, contains-focus and keyboard/pointer focus-ring laws before wiring them. Reject stale targets after disposal; do not deliver coordinates from one epoch to a different tree. |
| Layout and scrolling | `Constraints`, `LayoutPolicy`, fixed-height `CollectionWindow` and virtual spacers | Start with Row/Column/padding/text/scroll constraints. Validate against full relayout. Feed actual local offset and viewport height to each virtual node ID; retain layout feedback until a new committed pass, then render the settled result. Clamp host scroll offset on list shrink. Reject stale revision windows. |
| Text preparation | `TextServices.layout`, `TextLayout`, `ResolvedTextLayout`, explicit Native styles/fonts | Prepare exact text/style/width results outside paint, through a bounded owner-safe path. Cache by request plus font/scale generation. Use the identical resolved result for measure, paint, hit, caret and selection. Never shape as a fallback during paint. Native services retain their creating-thread requirement. The pure host/bridge cross-builds. The bounded JVM FFM adapter now uses the same C shaping/font/Unicode services and shared paragraph implementation; production service caching/font discovery remains open. |
| Painting | `Canvas`, `RecordingCanvas`, `PreparedText`, graphics recordings/scenes and backend receipts | Lower committed layout to recordings with painter order and clips. Resolve prepared text before recording. Present using the graphics track's supported scene/device bridge; do not create a competing drawing vocabulary. Recording replay alone does not qualify displayed pixels, GPU resource retirement or resize. |
| Editing and IME | UTF-16 `TextPosition`, affinity, `TextHit`, `TextSelectionRegion`, existing input/semantics shapes | Implement selection and editor commands, grapheme-safe edits, undo, composition/preedit/cancel, clipboard and visual navigation. Preserve hit alternatives at bidi/wrap boundaries. Text geometry supplies no editing policy. Pass real macOS IME and marked-text tests before claiming a usable field. |
| Accessibility and commands | `Semantics`, semantic parts and command metadata | One committed geometry/semantics tree drives hit tests and accessibility frames. Expose role/name/value/selection/enablement and actions. Keyboard and semantic activation share command validation. Check focus announcements, list selection and edits with VoiceOver. |

The shell adapter and host tree should be separate from core and the headless
engine. Dependency direction is host → engine/core and host → graphics/text
bridges. Native artifact linking stays in the native backend. No Intaglio or
backend-specific type enters the public UI contract. Exact module names and
APIs are implementation choices after the phase/lifetime design review.

## Ordered implementation slices

1. **Visible development shell:** first the bounded JDK 25 FFM ABI/window spike
   above, then owner wakeup/parking, committed tree, minimum layout,
   prepared text and ordered recording presentation. Paint static workbench
   content, resize correctly, capture displayed output and verify clean close.
   The bounded implementation and receipt now cover the ABI, main-thread lifecycle, scripted resize/empty/scroll behavior and independent tile/full GPU equivalence. Packaged macOS screenshots were inspected in-session and actual drag-resize/close
   passed in the responsive slice; see `../evidence/responsive-host-20261028/README.md`.
   Monitor-scale/occlusion qualification and quiet-window CPU/power remain open. Frame/power counters must agree with actual presentation. This establishes
   a development window, not editable controls or JVM shipping support. If the
   FFM spike fails, preserve its receipt and continue Native host integration
   while the binding blocker is resolved; do not substitute headless JVM tests.
2. **Operable workbench:** the bounded focus/dispatch, button, retained-focus and
   wheel/Page scrolling slice is implemented and reviewed; its action-p95 exit is
   still open. Continue with
   text editing including composition, shared command bindings, accessible
   geometry, bounded worker/progress integration. Run the complete workflow
   above. Add Native build/run of the same model and view.
3. **Usability decision:** matched ScalaFX app, independent authoring review,
   keyboard/VoiceOver and visual approval, measured responsiveness. Iterate
   on public APIs until the bar below is met. Full Stage 1 and Stage 2 exits
   remain separate from a successful first window.

## Matched ScalaFX experiment

Use `specimens/workbench` and `specimens/scalafx-workbench` as the starting
models. The current ScalaFX controller compiles but is not a complete visible
comparison. Implement both visible apps with the same seeded dataset, exact
workflow, simulated service delays/failures, progress capacity, and save
revision semantics. Report versions, machine, build flags and test data hashes.
Do not compare a polished Sirin view against an unfinished ScalaFX controller.

Freeze this checklist before recording results:

| Dimension | Shared task and evidence | Decision rule |
|---|---|---|
| Correctness | Dirty row refresh/reorder; selection deletion; move dirty editor; save r17 while editing r18; cancel blocked import; late results after close | Every law passes in both apps; missing behavior is visible and repaired before comparison. |
| Authoring | Independently implement one new validated field, one command binding and a persistent detail editor from the written spec | Review the public source, concepts and cleanup obligations. Count manual listeners, lifecycle cleanup, duplicated state, unsafe casts and escape hatches; report complete code, not selected LOC. Sirin must show a clear reduction in incidental coordination with equally legible code. |
| Editing/keyboard | Search, edit, invalid submit, recover, save, dock, scroll and cancel without pointer; composition and bidi/wrap selection | No lost text, unexpected focus, hidden refusal or inaccessible action. Record failures and task completion. |
| Visual/accessibility | Same content/theme intent, light/dark, scale and resize; VoiceOver checklist | Human visual approval and usable announcements required; golden images alone cannot pass. |
| Responsiveness | Input-to-present distributions during search/import/scroll, not just callback latency; quiet-window frame/CPU counters | Publish raw traces, p50/p95/p99/max and missed-frame counts for both. Choose and record numerical budgets before running on a named host. No responsiveness superiority claim from headless timings. |
| Discovery/repair | Fresh reviewer adds the same feature and diagnoses an intentionally refused command in each app | Record time, mistakes, documentation needed and resulting code. A syntax-only win does not meet the usability/elegance bar. |

Sirin's JVM position is a new UI system with explicit state, lifetime, work and
rendering semantics. Shipping requires a clearly better authoring experience
than ScalaFX while meeting the visible interaction, quality and tooling bar.
If that advantage is ambiguous, revise the API and repeat the matched tasks;
do not weaken the product requirement or declare superiority from compilation.

## Retained GPU integration checkpoint

The isolated `codex/retained-gpu-host-20261028` candidate consolidates the newer
batch renderer with the responsive host and presents retained tile textures on
one shared device/instance. It preserves input/focus and Work policies. See the
[contract](2026-10-28-gpu-host.md) and
[evidence](../evidence/gpu-host-20261028/README.md). Four-scale integration pixels,
retirement/cancellation/resize, actual packaged-window controls and 735 Scala
cases passed. The final diagnostic meets frozen action/scroll percentiles, but
quiet qualification remains open after the one refused preflight. Editing, IME,
accessibility and full visual approval remain separate gates.
