# Sirin: design direction

**Status:** draft design direction, 2026-10-27. Nothing here is implemented.
Lifecycle stage once a build exists: `prototype`.

> This is pre-release development software. APIs, semantics, and package
> boundaries may change. No stable support or binary-compatibility promise
> should be inferred unless the package explicitly states one.

This document merges and revises two earlier drafts: "a new Scala UI engine,
not a GPUI port" and "a general-purpose Scala Native UI platform". It keeps
the second draft's general-purpose scope and the first draft's engineering
details for high-throughput surfaces. The review notes at the end list what
changed and why.

---

## 1. Thesis

> An interface is an incrementally maintained program. Typed state and
> commands become layout, interaction, accessibility, and GPU rendering
> through explicit stages that can be inspected.

Sirin is a desktop UI engine and standard toolkit for Scala Native. Scala owns
the interface semantics: state propagation, component identity and lifetime,
layout, input routing, text editing behavior, the accessibility model, scene
construction, and rendering strategy. Native libraries supply the lower-level
services: windows and events, GPU access, font shaping and rasterization,
Unicode data, and accessibility transport.

The project has three goals that can be tested:

- **Local reasoning.** A reader can tell who owns a piece of state, what an
  update affects, and when a resource is released.
- **Predictable work.** Every propagation has a recorded reason. A color
  change does not shape text, and a dataset revision does not copy the
  dataset without saying so.
- **Semantic transparency.** Users, tests, scripts, and authorized agents can
  reach the same meaningful operations through typed commands.

**Correctness alone does not meet the ambition.**
- The public API must make sophisticated interfaces straightforward to
  compose.
- Extension mechanisms must support first-class components without private
  hooks.
- The default toolkit must show deliberate visual and interaction design.

Engineering, authoring experience, and aesthetic quality are developed
together and judged against explicit references: the authoring corpus
(§4.1) and the visual reference suite (§8.1). Regression prevention only
establishes competence. An advantage has to come from a simpler programming
model, better extension mechanisms, better interaction quality, and measured
efficiency.

**Non-goals for now:** a browser engine, CSS compatibility, mobile, a web
backend, a universal application architecture, a required effect system
(Cats Effect and ZIO integrations are optional modules), native hot reload,
and a comprehensive rich-text editor.

**Claims we must earn rather than assume:** that Sirin is faster or uses less
memory than GPUI. Clear semantics and useful diagnostics can be shown first;
performance claims require published workloads.

## 2. Position in the workspace

Sirin is an independent project built from the ground up. It has no
dependency on, or migration obligation to, any other workspace UI effort.
MeridianFX is a JavaFX design system and stays separate.

| Neighbor | Relationship |
|---|---|
| Intaglio (renderer-neutral `Scene`) | Possible downstream adapter (`sirin-intaglio`). The core never depends on it. |
| ravel / gale / image4s | Possible downstream adapters to the surface and collection-source APIs. They get no privileged core abstractions. |

Toolchain follows the workspace defaults: Scala 3.7.4, sbt 1.12.14, and Scala
Native 0.5.12, the version pinned elsewhere in the workspace. Register the
package in `packages.toml` as `prototype` once a build file exists.

## 3. Learning from GPUI without caricaturing it

GPUI already combines retained state with declarative views and a staged
element lifecycle: request layout, prepaint, paint. gpui-kit already separates
unstyled behavior (`gpui-base`) from styled components, integrates AccessKit,
and runs headless integration tests. These are prior art, not things Sirin
differentiates on.

The lessons register (`docs/lessons/`) records documented upstream defects as
a reproduction, the invariant behind it, and our regression test. These are
fixed upstream defects, not claims that GPUI is broken:

| Upstream case | Invariant for Sirin |
|---|---|
| Text-selection registration republished shared state on every registration. Work grew quadratically and unrelated views redrew (gpui-kit #3626, fixed in #3686, 2026-10-26). | Collecting state during a frame is separate from publishing it. Publication is batched once per frame. A test asserts zero work in unrelated subtrees. |
| Dropped text views kept shaped paragraphs alive until later cache activity. | Release is owner-aware. Caches have budgets. Cleanup must not need another frame. |
| A focused button that became disabled lost its dispatch path. | Focus ownership, activation eligibility, and tab-stop eligibility are three properties, and transitions between them are tested. |
| A focus-trap traversal could loop on an unexpected short cycle. | Every graph traversal detects repeated nodes and is bounded. |
| v0.8.1 had to separate Kit-owned APIs from re-exported upstream GPUI APIs. | The stable API never re-exports a dependency's types. |

Process: observed defect, then minimal reproduction, then invariant, then
regression test, then implementation.

## 4. Programming model

| Concept | Meaning |
|---|---|
| `View` | An immutable, composable description of structure. A plain function returning a `View` needs no scope. |
| `Component[P]` | A unit mounted with an identity (owner, key, type) and a `Scope`. It receives its **live inputs** as `Inputs[P]`, a read-only signal of its props. |
| `Signal[A]` | A current value that changes. It carries an equality or revision contract. |
| `Cell[A]` | A `Signal` that can be written inside a `UiTxn`. |
| `Event[A]` | An occurrence, not a persistent value. |
| `Scope` | Owns subscriptions, tasks, and resource leases. Disposal is immediate on the UI side. |
| `Command[A, R]` | A typed operation with an enablement `Signal[Boolean]`, validation, a completion policy (§5.4), and an asynchronous result `R`. Permission classes come in Stage 3. |
| `CollectionSource[K, A]` | A keyed, revisioned source with key↔index lookup, range access, and change deltas. Large lists use it instead of `Signal[Seq[A]]`. |

Illustrative only; this is not implemented code:

```scala
def preferences(model: PreferencesModel): Component =
  Component.scoped { scope =>
    val filter = scope.cell("")
    Column(
      Text("Preferences").style(Typography.title),
      TextField.bind(filter).label("Find a setting"),
      // each row receives Inputs[Section]: same key keeps row state, new values still arrive
      ForEach(model.sections.filtered(filter.signal), key = _.id)(SettingsSection(_)),
      Row(
        Button("Restore defaults").command(model.restoreDefaults),
        Spacer(),
        Button("Save").primary.command(model.save)  // enablement comes from the command
      )
    ).padding(Spacing.large)
  }
```

Rules:

- The scoped body runs once per mount, not once per frame.
- **Identity and inputs are separate.** When a parent supplies a new value
  under the same key, the mounted component keeps its local state and its
  `Inputs[P]` signal updates. Remounting would lose drafts; capturing the
  original value would show stale data, so neither is allowed.

  Law: *same owner, key, and type preserves local state, and changed input
  values still reach the mounted component.* Literal props remain plain
  values (`Text("Gain")`), and only dynamic props are signals.
- Structure that can change is marked explicitly with `Switch` and keyed
  `ForEach`. Everything else is static.
- Writes need a `UiTxn` context parameter. Scala contextual parameters do not
  work like a borrow checker, so thread-owner checks, disposed-scope checks,
  and handle-generation checks stay enforced at runtime. We do not depend on
  capture checking, which is experimental in Scala 3.7.
- No compiler plugin and no macro DSL in the core. Derivation for command
  schemas, inspectors, and forms is an optional convenience.
- Opaque types represent identities, handles, and units: `Lp` for logical
  pixels, `Px` for device pixels, and `Utf8Off`, `Utf16Off`, and `GraphemeIx`
  for text positions.
- Externally mutable data must publish revisions or snapshots. Mutating an
  object stored inside a cell is not a supported way to notify.

### 4.1 Authoring corpus: an executable API specification *(new)*

Before the core API is frozen, `examples/authoring/` holds six programs that
compile against skeletal interfaces:

1. a validated form,
2. an editable keyed list whose rows keep their drafts across reorders and
   value replacement,
3. an asynchronous search with stale-result handling,
4. a popup with focus restoration,
5. a document command shared by menu, shortcut, and button,
6. a custom interactive surface, built only on public APIs.

**Review checklist:**
- ownership is clear,
- there is no unnecessary framework context,
- no subscription needs manual cleanup,
- nothing remounts accidentally,
- nothing uses privileged internals.

Line count is not the measure. Explicit lifetime and update semantics count
for more than brevity. **The core API is not approved because its laws
pass; it must also express these programs cleanly.**

## 5. Kernel semantics (specified before widgets)

### 5.1 Transactions and propagation

Each transaction passes through four phases:

1. **Stage.** Writes are staged. Reads inside the transaction see the staged
   values, including reads of derived signals (see below).
2. **Evaluate the candidate snapshot.** Affected derivations are recomputed
   against the staged state, **glitch-free**: in height order over a
   dependency topology that stays fixed for the pass. No derivation runs
   more than once in a committed propagation pass.
3. **Publish atomically.** The complete candidate snapshot of source cells
   and derivations becomes the committed state in one step. No observer ever
   sees new sources with old derived values.
4. **Notify, then run effects.** Bindings are notified and record their
   invalidation class: structure, measure, layout, paint, hit-test, or
   semantics. Effects then run after publication, in registration order.

*(Revised after review rounds 2–3.)*

**Derived reads inside a transaction** are evaluated speculatively against
the staged state and memoized until the next write. For `y = x.map(_ * 2)`,
the transaction `{ x.set(2); y.get == 4; x.set(3); y.get == 6 }` holds.
"At most once" refers to the committed propagation pass, not to speculative
reads made inside a handler. This is a contract test in C1.2b.

**Failure is data, and rejection is explicit.**
- A derivation that throws produces a `Failed(error)` value inside the
  candidate snapshot, which is still published atomically. This is a
  deliberate API choice: a text field whose parse fails must still accept
  keystrokes.
- A transaction is *rejected* only by an explicit guard (`txn.require`) or by
  an exception in handler code. Rejection discards every staged write.
- An exception **poisons the whole transaction**, even if an outer handler
  catches it. Nested transactions join the enclosing one, and v0 has no
  savepoints.
- Derivations are read-only; a write from inside a derivation is a runtime
  error.
- None of this rolls back external I/O.

**Topology changes take effect between passes.** Mount, dispose,
`Switch`, and `flatMap` record their graph edits during a pass. The edits
are applied at the pass boundary, and the newly attached nodes are
evaluated in the next pass of the settle loop. Observers see only the
settled state, so no glitch is visible. Incremental height adjustment, in
the style of Jane Street's Incremental, is a later optimization and must
preserve the same observable semantics.

**The settle loop** runs inside phase 2 of the frame pipeline (§5.3),
before structural updates. An effect that writes stages a follow-on pass.
Each run ends in exactly one of three outcomes:

| Outcome | Condition |
|---|---|
| `Settled` | No writes are staged and no topology edits are pending. |
| `CycleDetected(trace)` | A repeated (node, value-revision) state was observed. This is proof of a cycle. |
| `BudgetExceeded(trace)` | The pass budget ran out without a detected cycle. This is not proof of divergence; the budget is configurable. |

Bindings and observers see only `Settled` states. On either failure outcome
the last settled state stays committed and the diagnostic is surfaced.

**Equality** must be lawful. The defaults reject `Array`, and
floating-point equality is defined by `equals`, which treats NaN as equal
to NaN. A derivation whose new value equals its old one does not propagate
further.

Dependencies are explicit through signal composition. We do not discover them
from arbitrary reads. The inspector can answer "why did this frame happen" by
walking the recorded causes.

A **reference evaluator** recomputes everything from scratch. Property tests
assert that the incremental engine and the reference evaluator agree on
randomized programs.

### 5.2 Identity and lifetimes

- Identity is the triple (owner, stable key, component type). It is never
  an allocation address.
  - Keyed dynamic children (`ForEach`) never use their list index.
  - Static children are positional, and that is safe because static
    structure does not change.
  - Moving a component to a new owner through ordinary reconciliation does
    not carry its state.
  - An explicit `reparent` (or portal) operation with a stable **global key**
    does preserve state, for docking and tear-off panels. *(added after
    review)*
- Duplicate keys fail in development builds.
- Handles are (slot, generation) pairs. A stale callback cannot reach a new
  occupant of the same slot.
- *Mounted*, *visible*, *realized* (inside a virtualized viewport), and
  *disposed* are distinct states. A virtualized row can be unrealized while
  its selection persists. A popup is placed elsewhere visually but still
  belongs to its originator.
- Disposal disconnects the UI immediately and blocks new publications. Native
  and GPU resources are freed later, once outstanding work retires. Native
  cleanup never relies on finalizers, which Scala Native does not run.

### 5.3 Frame pipeline

The pipeline runs these phases in order:

1. events and completed work
2. transaction
3. structural updates
4. measure and layout
5. interaction and semantics snapshot
6. scene preparation
7. GPU submission

Measurement and paint cannot write application state. Observations that
depend on geometry produce deferred requests, which are handled on the next
turn. Input routes against a complete interaction snapshot, and before
activation the runtime re-checks that the target still exists and is enabled.
**Activity-driven scheduling.** If there are no invalidations, active
animations, or pending deadlines (caret blink, scheduled transitions,
application timers), Sirin parks the event loop and schedules no frame. It
never polls periodically just to discover that nothing changed.

**Re-entrant paint path** *(new)*. Live resize on macOS and Windows runs a
modal tracking loop inside the event pump. During that loop the engine may
only re-layout and repaint the **last committed state** at the new size.
Transactions are forbidden there; events received during the loop are
queued until it exits. Stage 0 decides between SDL main callbacks and an
event watch for this path.

### 5.4 Threads and background work

- **The UI owner is the process main thread.** macOS AppKit and SDL require
  this, and the Scala Native `main` runs on it. *(made explicit)*
- Workers publish through bounded, synchronized channels and never touch
  mounted state.
- **Foreign callbacks never execute Scala code on foreign threads.** They
  are marshalled in the C shim (§6.1). The Scala Native embedding docs
  describe automatic entry from foreign threads as "in development", so the
  pinned 0.5.12 runtime cannot be assumed to support it. This is enforced by
  a hard gate (P0.7). It is not merely documented.
- **`@blocking` is about the GC, not asynchrony.** It lets the collector
  proceed while a thread sits in native code. It does not make a call
  asynchronous, and it does not stop a blocking call from stalling the UI
  thread. Anything slow belongs on a worker.
- **Every blocking extern is annotated `@blocking`.** Without the
  annotation, a blocked call stalls stop-the-world collection for every
  allocating thread. The list is explicit and reviewed:
  - `SDL_WaitEvent` and `SDL_WaitEventTimeout`, which the idle loop depends
    on,
  - `wgpuInstanceWaitAny`, and `wgpuDevicePoll` with `wait=true`,
  - `wgpuSurfaceGetCurrentTexture`, since under FIFO the vsync wait can
    happen at acquire,
  - `wgpuSurfacePresent`,
  - file dialogs,
  - font enumeration.

  Re-entry into Scala from inside a `@blocking` call is not assumed safe.
  wgpu callbacks fire inside `ProcessEvents` and `WaitAny`, so they are
  shimmed to record results only, and Stage 0 tests re-entry directly.
  Publication between threads uses explicit synchronization, because Scala
  Native's default final-field semantics are weaker than the JVM's.
- **Task results answer four separate questions.** *(revised after review
  round 3)*

  | Question | Contract |
  |---|---|
  | Does the operation still have a valid owner or handle? | **Lifetime validity.** Always enforced: a result for a disposed owner or a stale generation is never written to UI state. |
  | Should a newer request supersede this one? | **Freshness policy**, chosen per operation. |
  | How are its messages delivered? | **Delivery policy:** coalesce-latest (pointer, progress), ordered (text edits), or complete-stream (freehand paths), each with bounded queues. |
  | What does completion mean for the current domain state? | **Application reconciliation**, an explicit completion handler. |

  **Freshness policies:**
  - *latest-only*, for previews and search. A result whose request
    generation or input revision is out of date is dropped. A *progressive*
    variant accepts it but marks it stale. Liveness law: once the inputs
    stay unchanged for longer than the worker latency, a current result is
    accepted.
  - *every-invocation*, for independent downloads. No invocation supersedes
    another.
  - *serial*, where one invocation runs at a time and the rest queue.
  - *snapshot-associated*. The result is tied to the input revision it was
    computed from and is never discarded for being older. For example,
    saving revision 17 while the user edits revision 18 updates the
    saved-revision marker to 17 and does not mark 18 as saved.

  Cancellation only saves work. These rules prevent stale UI writes without
  erasing legitimate outcomes.
- Ownership follows the application. Closing a panel cancels its preview tasks
  and subscriptions, but not a workspace-scoped job the panel was only
  observing.

## 6. Native stack (all choices validated in Stage 0)

| Subsystem | Initial choice | Stage-0 risk to falsify |
|---|---|---|
| Windows, events, clipboard | SDL3 behind `PlatformShell` | IME quality: SDL's composition events compared with native `NSTextInputClient` and TSF. There is no native menu bar. Each gap gets a small per-OS adapter. |
| GPU | `wgpu-native`, with a pinned release and pinned `webgpu.h` | Header and ABI churn (the `webgpu.h` standardization changed many signatures). Struct-by-value and callback ABI from Scala Native. |
| Shaping | HarfBuzz | Low risk. |
| Font loading, rasterization | FreeType for rasterization. Fallback discovery through CoreText, DirectWrite, or fontconfig. | Fallback lists differ per OS. Color emoji (COLR/CBDT/sbix). |
| Bidi, segmentation, line breaking | **ICU4X through its C FFI** is the lead candidate. ICU4C remains an option. | Compare equivalent capabilities in **trimmed** deployments: ICU4X custom data against ICU4C filtered through its data build tool. Neither untrimmed size is a fair comparison. ICU4X C API coverage of segmenters and bidi is confirmed. |
| Accessibility | AccessKit C bindings, with the semantic model owned in Scala | Attaching to SDL windows (NSView, HWND, AT-SPI). Which threads action callbacks run on. Behavior with real assistive technology. |

### 6.1 The foreign-function boundary *(revised after review)*

- **A pointer-only C shim is the default, not a fallback.** Scala Native
  externs cannot pass or return C structs by value; the interop table lists
  `struct { int x, y; }` by value as "Not supported". `webgpu.h` uses
  by-value structs throughout (`WGPUFuture`, `*CallbackInfo`, and
  `WGPUStringView` in callbacks), and so does the ICU4X Diplomat API.
  - Each by-value function gets a shim wrapper that takes and returns
    pointers.
  - Each callback type gets a C trampoline that passes pointers into a Scala
    `CFuncPtr`.
  - Shims start out hand-written, until enough patterns repeat to justify a
    generator.
  - Shim C sources build as part of the Scala Native link, from
    `resources/scala-native/`.
- **ABI checks.** The shim exports the `sizeof` and `offsetof` of every
  struct that Scala builds, and a test compares them with the Scala layouts.
  By-value round trips go through the shim and run in CI on all three ABIs
  (AArch64, SysV x64, Win64).
- **Scala never runs on a foreign thread.**
  - Callbacks that can come from threads Scala Native did not create go
    through the shim: AccessKit handlers on Unix and some on Windows, SDL
    event watches. The shim enqueues into a C-side queue and wakes the loop
    with `SDL_PushEvent`, which is thread-safe.
  - The AccessKit activation handler on Unix must return a tree
    synchronously. It answers from a **native-side copy of the latest
    semantics snapshot** and never blocks on the UI thread.
- **`userdata` is a handle-table index with a generation**, never a pointer
  to a Scala object. C-held pointers are not GC roots. Inputs that C borrows
  across calls, such as ICU4X segmenter text, live in native memory.
- **One Rust static library.** wgpu, AccessKit, and ICU4X each bundle Rust
  std. Once a second Rust dependency arrives, they are combined into a
  single `sirin-native` crate that produces one library. That keeps one
  `target/`, one rpath, and one copy of std. Until then, Stage 0 uses the
  pinned wgpu-native prebuilt dylib.

What Scala owns: line layout, the editing model, selection, caret, the
compositor policy, batching, the glyph atlas, shaders, and resource tracking.

The text pipeline produces **one layout result**. Drawing, caret placement,
hit testing, selection, and accessibility geometry all read from it.
Committed text and IME composition are kept as separate state. Physical keys,
logical keys, and text input are three separate input channels.

## 7. Renderer

**Primitives.** The initial set is rectangles, rounded rectangles with
borders, analytic SDF shadows, images, glyphs, clipping, transforms, and
group opacity. Paths are tessellated on the CPU at first. A compute-based path
renderer is a later option, adopted only if measurements justify it.

**Color and pixels.** These need early decisions because they are
user-visible and hard to change later. *(new)*

- The framebuffer is sRGB and blending happens in linear space. Text gets a
  gamma or contrast adjustment so it does not look thin.
- Fractional display scales (1.25 and 1.5) are supported from the start,
  with explicit device pixels.
- **Geometry and text follow different snapping rules.** Hairlines and
  rectangle edges snap to device pixels. Text keeps its shaped advances and
  fractional pen positions. Glyphs use **subpixel positioning**, with a
  small number of horizontal subpixel bins in the atlas, and snap only the
  baseline vertically. Subpixel *positioning* is a separate matter from LCD
  subpixel *antialiasing*: rasterization starts in grayscale, and LCD
  antialiasing is deferred.

**Compositing contract** *(new)*. Premultiplied alpha is used everywhere.
- **Group opacity is isolated.** A group with opacity is composited into an
  offscreen target and then blended once. Two overlapping children at 50%
  must not produce 75% coverage where they overlap.
- Isolation is required for correctness. Persistent *cached* layers are a
  separate performance question that still waits for benchmarks.
- An elementwise fast path is allowed only when the children provably do
  not overlap.
- Clips under transforms, and transformed content inside isolated groups,
  have golden tests.

**Retention.** The CPU keeps the prepared scene, and the GPU redraws the whole
window. Damage regions and cached layers must be justified by benchmarks.
Batching must preserve painter order, clipping, and blending.

**Data layout.** The public API uses small immutable values. Internally the
renderer uses compact node tables, primitive arrays, reusable buffers, and
generation-checked handles. The target is bounded allocation during
steady-state interaction, not "zero allocation".

**Copies are named, not wished away.**
- Scala Native can avoid an extra managed-to-native marshalling copy by
  borrowing suitable primitive-array storage for a synchronous native call.
  This relies on the current collectors not moving objects, which the
  documentation does not promise.
- Borrowing does **not** remove wgpu's staging copy, because
  `write_buffer` copies before it returns, and it does not remove the GPU
  transfer. A Rust caller can borrow memory the same way, so this is not an
  advantage over Rust.
- The borrowing API must establish reachability, bounds, and whether
  mutation is allowed for the entire borrow.
- The data path runs from application storage, through optional
  marshalling, to staging, to the GPU resource. Each hop has a measured byte
  count and an ownership rule (probe P0.3c).
- Data that C keeps after a call returns lives in native memory.

**Surfaces.** A `Surface` can contribute GPU work with declared inputs, an
output texture, bounds, clipping, and resource dependencies, and the
compositor places it alongside ordinary controls. It declares its transfer
policy explicitly: CPU-shared, native-borrowed, or uploaded. Large data is
held as a view plus a revision, never as one component per element.
Backend-specific escape hatches live in `sirin.unstable.*`.

## 8. Layout, toolkit, design

**Layout.** The model is Flutter-style box constraints: constraints go down,
sizes come up, and the parent positions its children. It also supports
baselines, logical start and end, and intrinsic sizes with explicit cost
warnings, because intrinsic passes can be quadratic. The vocabulary is rows,
columns, overlay, grid, scroll, split, and virtualized collections. The
measurement-cache key includes the constraints, the content revision, the
font-metrics epoch, and the scale. The layout debugger can answer "why is
this 418 px wide?"

**Interaction.** Focus, hover, pointer capture, activation, tab navigation,
and window activation are modeled separately. Dismissal, placement, focus
restoration, and modality of overlays are shared machinery, not something
each widget reimplements.

**Accessibility** is part of every interactive component's contract: role,
name, value, state, actions, relationships, and keyboard behavior.
Virtualized collections expose set size and position in set. Exposing a
control to accessibility does not grant automation privileges.

**Design system.** The design aims to be restrained, typographically strong,
and canvas-first. It defines semantic color roles, coordinated density modes,
a type scale, visible focus, high contrast, and light and dark themes.
Behavior and skin can be separated, but the default skin must be complete.

### 8.1 Visual reference suite *(new)*

`design/reference/` holds three authored specimens, drawn as specifications
before the toolkit grows:

1. a typography-rich form,
2. a compact, information-dense workspace,
3. a spacious canvas with floating controls.

Each specimen fixes actual values, not just token names: typefaces and
sizes, line heights, spacing, alignment, control proportions, contrast,
focus treatment, and every interaction state. One vertical slice is
implemented against them in Stage 2.

**Approval and regression are separate jobs.** A golden image shows
consistency with an accepted image; it does not show that the image is
good. Visual approval is a human review against the specimens. GPUI
comparisons use matched tasks and content, and judge polish, readability,
keyboard use, resize behavior, and motion separately from rendering speed.

### 8.2 Motion *(new)*

Motion uses one deterministic animation clock, which testkit can control.
- **Ownership.** Each animation belongs to a scope.
- **Interruption.** A new target retargets from the current value and
  velocity; it never snaps or queues.
- **Visibility.** Hidden or unrealized content does not animate and
  schedules no frames.
- **Reduced motion.** Under the reduced-motion preference, transitions
  become instant or cross-fades.
- **Hit testing.** Animated geometry is hit-tested at its presented
  position, so a control stays clickable where it appears.

By default, motion animates presentation properties. Layout animation is
allowed but explicit, and it is charged to layout. A few well-defined
transitions, not an animation language.

**Admission rule.** A widget enters the standard library only when its
behavior, accessibility, styling, and tests are good enough to serve as a
reference implementation.

## 9. Module layout (one repository)

```text
sirin-core      public types, View/Component, signals, commands, geometry, service interfaces
sirin-engine    transactions, dependency graph, mounting, layout, input routing, semantics, scene prep
sirin-desktop   PlatformShell (SDL3 + per-OS adapters), wgpu backend, text services, AccessKit (Native only)
sirin-widgets   behaviors + default design system (an ordinary client of core/engine)
sirin-testkit   controlled scheduler, headless host, interaction drivers, reference evaluator, conformance
sirin-tooling   inspector, traces, packaging helpers
```

`sirin-core`, `sirin-engine`, `sirin-widgets`, and `sirin-testkit` are
cross-built for the JVM and Native, with the JVM used for fast tests only.
`sirin-desktop` is Native only. The engine must not depend on the widgets.

There are three API surfaces with separate stability levels:

- the application API,
- the component-author API,
- the backend-author API, which may evolve faster than the other two.

## 10. Developer iteration (new section)

Scala Native link times are the biggest risk to developer experience, and the
drafts only touched on it. Linking is whole-program, so it scales with total
code, and the engine plus widgets will pass 20k lines by themselves. Line
count is a weak proxy, though. Stage 0 measures a synthetic program of
about **100k lines** that is shaped like real UI code: reachable code,
closures, and generic composition. It uses Scala Native incremental
compilation and is measured in debug and release-fast modes.

Three kinds of change are timed separately:
- an edit to an application body,
- an edit to a public API,
- a dependency bump.

Warm iteration is reported separately from clean builds. The 20 s figure
below is an **escalation threshold, not a target**.

**Decision gate:** if an incremental edit-to-window cycle takes more than
about 20 seconds, evaluate a **JVM development host**. It would bind the
same native libraries through Panama FFM on JDK 25, and is a development
aid, not a shipped target. On macOS it needs `-XstartOnFirstThread`. The
idea of generating both bindings from one description is deferred, because
it is a project of its own. Even then, a shared binding description would
not guarantee the same memory-lifetime or callback semantics on both hosts,
so the fallback needs behavioral parity tests. Regardless of that decision,
the platform interface stays independent of Scala Native from the start.
Until then, the iteration story is restart with restored state.

## 11. Validation

**Structural performance contracts** (testable now):

- With no invalidations, animations, or deadlines, the loop is parked and no
  frame is scheduled.
- **Realization is bounded.** The number of realized components never
  exceeds the visible range plus overscan, and updating realization never
  scans the whole dataset. Indexing and delta-processing costs are reported
  separately. v0 is synchronous and fixed-height; variable heights and
  asynchronous ranges are extensions with their own cost models. The tests
  cover replacement, insertion before the viewport, deletion of the
  selected item, reordering, and stale range responses.
- A local update does no work in unrelated subtrees unless a documented
  dependency requires it.
- After a component closes, caches return to within their budgets.

**Named workloads**, with CPU preparation, GPU time, missed frames, and
input-to-display latency recorded separately, including tail percentiles. At
120 Hz the frame budget is 8.33 ms.

1. Edit text while a surface animates.
2. Scroll a 10⁶-row `CollectionSource`.
3. Resize a docked layout.
4. Change parameters rapidly against slow workers.
5. Open and close panels 10⁴ times.
6. Draw a dense custom surface.

GPUI comparisons use two or three of these workloads, implemented in both
frameworks against current, corrected GPUI.

**Workloads aimed at Sirin's own overheads.** These find dependency-graph
overhead, retained closures, equality costs, and notification
amplification:
- a large static interface,
- 10⁵ small bindings,
- high fan-out updates,
- mount and dispose churn,
- frequent changes whose derived values stay equal.

**Observability has a budget.**
- In normal operation the engine keeps cheap counters and compact
  cause IDs.
- When tracing is on, it uses bounded ring buffers.
- Full dependency-graph capture happens only on request.
- Traces never retain closed components or their application data.
- *Engine-accounted* object counts are kept distinct from total
  runtime and native memory measurements.

**Profiling on Scala Native** has no JFR equivalent. We rely on OS profilers
plus the engine's own counters for object counts by kind, invalidations, and
cache occupancy. These counters are part of the design from the start.
Scala Native has no portable allocated-bytes API, so the counters count
engine objects, not bytes.
- Instruments and perf use DWARF debug info.
- On Windows, ETW tooling expects PDB symbols, so Windows profiling
  support is unverified.

**Platform matrix and CI:**

- macOS arm64 runs locally and in CI.
- Windows x64 and Linux x64 run in CI, using software GPU adapters for
  conformance (WARP on Windows, lavapipe or llvmpipe on Linux).
- Real IMEs, screen readers, and display-scale transitions are tested by hand
  against a checklist on each release candidate. Headless tests cannot
  certify those behaviors.

**Reference applications:**

1. A notes and document app, which stresses text, forms, menus, lifecycle,
   keyboard use, accessibility, and persistence.
2. A diagram editor, which stresses pointer capture, transforms, zoom, custom
   graphics, selection, undo, and large scenes.

Domain workbenches come later, as downstream stress tests.

**Windows status.** No Windows hardware is available. Windows is limited to
CI: build, link, and headless rendering through WARP. IME, Narrator, and
per-monitor scaling stay unverified, and therefore unsupported, until
hardware or a VM is available. Docs must say so plainly.

---

## Review notes: what changed relative to the drafts

**Kept.** The general-purpose scope, the GPUI lessons process, the six
concepts plus `Command`, transaction semantics, identity and generations, the
frame pipeline, the single UI owner, the result-acceptance rules, the native
choices, the reference applications, and the staged gates.

**Verified.** The gpui-kit repository (three layers, AccessKit, headless
tests), the v0.8.1 release of 2026-10-26 that separated Kit-owned APIs from
re-exported upstream APIs, and PR #3686 (quadratic selection republication;
unrelated renders dropped from 324 to 0 at 164 views, measured on Linux only)
were all checked against their sources on 2026-10-27.

**Revised or added:**

1. **Glitch-freedom and a bounded settle loop** are now explicit. "Recompute
   affected derivations" left open both the evaluation order and what happens
   when effects write.
2. **`CollectionSource`.** `ForEach` over `Signal[Seq[A]]` costs O(n) per
   update and cannot support the million-row workload. Large collections need
   a keyed, revisioned, delta-producing protocol.
3. **Main thread and foreign-callback threads.** macOS requires that the UI
   owner be the main thread, and AccessKit and SDL can call back on threads
   Scala did not create. Draft 2 covered GC coordination but not either of
   these points.
4. **Text stack.** HarfBuzz plus ICU is not enough. We also need font
   loading and rasterization, per-OS fallback discovery, color emoji, and the
   glyph atlas. ICU4X is preferred over ICU4C because of distribution size.
5. **Color, gamma, and fractional scale** are decided early, because changing
   them late changes how every pixel looks.
6. **Developer iteration.** Native link time is a measured gate, with a
   possible JVM/Panama development host as the fallback. Draft 2 deferred hot
   reload but never measured the underlying problem.
7. **Platform matrix staging.** Draft 2 required all three operating systems,
   plus both Wayland and X11, in Stage 0. For one maintainer working with
   agents, the revised plan is: macOS fully in Stage 0, Windows and Linux in CI only
   (build, link, software-GPU render), manual Windows and Linux checks once
   hardware or VMs exist, and X11 before beta.
8. **Kill and pivot criteria** were added to Stage 0, so a failed feasibility
   probe changes the plan instead of being worked around.
9. **Workspace position.** Sirin is stated to be independent. MeridianFX
   (JavaFX) is separate, and Eidolon is not a driver.
10. **Command enablement** comes from the command itself (`.command(cmd)`),
    not from parallel `.enabled(...)` and `.onActivate(...)` calls that can
    drift apart.
11. **Non-moving GC.** Passing managed arrays directly avoids one staging
    copy. That was later corrected from "zero-copy", see round 2.
12. **Profiling gap.** Scala Native lacks JVM-grade profilers, so engine
    counters are a requirement, not optional tooling.

**Removed.** Brain-visualization examples in the core API (Draft 1). That
material now belongs to the downstream adapters.

### Round 3: external design review (2026-10-27)

**Adopted:**
- the ambition paragraph, with authoring and visual quality as gates
  (§1, §4.1, §8.1),
- the four-phase transaction (§5.1):
  - atomic publication of a candidate snapshot,
  - speculative derived reads, with "at most once" scoped to the committed
    pass,
  - failure as data plus explicit rejection,
  - topology edits applied between passes, which replaces the earlier
    mid-pass height adjustment,
  - three settle outcomes,
- live component inputs, `Component[P]` with `Inputs[P]` (§4),
- task results split into lifetime, freshness, delivery, and
  reconciliation, with four freshness policies (§5.4),
- activity-driven idle scheduling,
- a hard foreign-thread gate and the `@blocking` clarification,
- more precise copy claims and a copy-path probe,
- the snapping rules split for geometry and text, with subpixel
  positioning,
- isolated group opacity,
- motion semantics,
- the bounded-realization contract,
- an observability budget,
- benchmarks of Sirin's own architectural costs,
- a fair ICU4X/ICU4C comparison,
- a better iteration experiment.

**Already handled in round 2** (the review read an earlier draft):
- the zero-copy wording,
- removing MeridianFX and Eidolon,
- the stale GitHub-owner decision.

**Roadmap changes** are listed in the roadmap.

### Round 2: fresh-context review (2026-10-27)

**Adopted:**
- the pointer-only C shim as the default (§6.1), confirmed against the
  Scala Native interop docs,
- no Scala code on foreign threads, with a native-side accessibility
  snapshot,
- an explicit list of `@blocking` externs and re-entry probes,
- a re-entrant paint path for live resize,
- a single Rust static library,
- the corrected copy claim and handle-index `userdata`,
- the specified transaction semantics: derived reads against staged state,
  height adjustment, poisoning, `Failed` values, settle-loop placement,
  lawful equality,
- `reparent` with a global key, positional static children,
- the progressive delivery policy with its liveness law,
- the 100k-line link benchmark and `-XstartOnFirstThread`,
- counters that count objects, not bytes.

**Plan changes** are in the roadmap:
- measurable pass criteria,
- split probes,
- Windows marked CI-only,
- deferred scope.

**Not independently verified:**
- SDL3's surrounding-text and reconversion support,
- whether `wgpuSurfaceGetCurrentTexture` blocks on every backend,
- how duplicate Rust std symbols behave when several Rust libraries are
  linked.

These stay as probe questions.
