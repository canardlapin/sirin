# A-0 authoring corpus: review

## A-0b revision, 2026-10-27

**Status:** owner accepted the amended R1–R16 package. VISION and the provisional
interfaces have been revised; twelve positive programs plus `Neg.scala` compile.
The current-session audit below was followed by an independent fresh-context
review, eight fixes and bounded re-review. **A-0 provisional-authoring exit is
met**; see `docs/design/decisions/2026-10-27-a0-independent-review.md`. Framework operations are still abstract or
`???`; only pure value helpers and the pre-existing handle table are executable.

New programs: `VirtualizedRows.scala`, `DockingWorkspace.scala`,
`StreamingImport.scala`, `FailedBinding.scala`, `MultiWindowDocument.scala`,
and `ReducedMotion.scala`. They specify later behavioral tests through source
comments; compilation does not establish realization, lifetime, delivery,
failure, save ordering, or motion laws.

### Current checklist

| Criterion | Current finding |
|---|---|
| Ownership | Models use workspace scopes; component dependencies are explicitly bound; windows have separate scopes; invocation observation requires a scope. Streaming endpoints and reparented panels retain explicit owners. |
| Context | Stable dependencies no longer travel through Inputs or Switch factories. Unit components use `.view`. Handler reads use UiTxn; paint/layout use Committed and declared inputs. |
| Cleanup | No manual subscription teardown in the corpus. Scope disposal owns tasks/channels/observations; physical retirement still needs engine tests. Explicit channel close/cancel are user actions. |
| Remounting | Stable definition values, live keyed Inputs, and explicit dependency-change remounting replace factories. Docking contrasts explicit retained ownership with intentional ordinary remounting. Arbitrary callers can still recreate definitions; diagnostic is best effort. |
| Public APIs | Every example imports public APIs only. Shared TextLayout/TextServices and focus parts exist as shapes. TextField-on-public-APIs, complete text/IME, hover/cursor and scroll integration remain Stage 2 obligations. |

### Disposition of the original 24 awkwardnesses

| # | Current disposition |
|---|---|
| 1 | Explicit status callback supplies running count and lastResult and lastRefusal. Form has no duplicate submitting cell; its validation action uses submit enablement. |
| 2 | Stable `Component.withDeps` and `.bind`; dependency reference identity changes remount. Dynamic values use Inputs. |
| 3 | Unit placement `.view`. |
| 4 | Independent Concurrency/Acceptance, bounded save preset, dispatch-time snapshots and monotonic saved marker. QueueLatest deferred until needed. |
| 5 | Separate layout/paint/hit/semantic inputs; diagram lift is Presentation and no longer enters its model Signal. |
| 6 | Argument enablement is a declared Signal of a predicate; bindings and dispatch recheck it. Small todo example explicitly uses O(n) snapshots; virtualized example does not. |
| 7 | Modifiers preserve a facade only with explicit ViewRebuild capability; a real immutable custom final-view test and an unsupported-subtype negative fixture cover the reconstruction boundary. |
| 8 | Public `.activate` supplies keyboard/focus behavior with explicit ListItem role. |
| 9 | Every explicit dispatch matches Started/Refused. Refused rename/add keeps the editor or draft intact and displays the reason. |
| 10 | Small literal/signal overloads retained. Union Prop deferred to avoid ambiguous nested signals. |
| 11 | Cell lenses deferred until projected equality/revision laws are specified. Split cells remain explicit. |
| 12 | TaskState shown/pending/stale/error projections simplify search. Catalog is a stable dependency, query is input. |
| 13 | Work's lexical NotGiven guard retained with ownership documentation; positive controls establish that negative fixtures resolve. Runtime thread ownership remains authoritative. |
| 14 | Latest and command remain; synchronous action shares dispatch. Standalone launcher removed from provisional API because no corpus scenario requires it. |
| 15 | Form marks fields touched outside dispatch's implicit attempt; popup also exercises explicit attempt and refusal handling. |
| 16 | Reasoned dismissal can veto outside-click loss of a draft. Default still closes. |
| 17 | NodeRef has a part-aware SemanticTarget; single attachment/settled focus rules are in VISION. |
| 18 | Focused-part, active-descendant and focus-ring shape added; diagram uses part focus. Full control behavior remains Stage 2. |
| 19 | Declared theme/font/display-scale phase dependencies with cause IDs. No dependency discovery through arbitrary reads. |
| 20 | Shared TextLayout/TextServices shape added; shaping, offset-unit refinement and complete IME services still require Stage 2 design/implementation. |
| 21 | Named layout slots added; diagram leaves its toolbar unplaced without an anchor. Indexed children remain for homogeneous layouts. |
| 22 | Concrete mapN arities 2–4; form uses four. Generic tuple composition deferred until a larger arity is needed. |
| 23 | Show is Boolean keyed-switch sugar, exercised in motion. |
| 24 | Keyed Windows with independent scopes and explicit close action; two views share one workspace document/save. Static single-window convenience uses the same lifecycle. |

### Validation and next review

`testJVM testNative examples/clean examples/compile probeWindow/test
probeThreads/test` passed: 6 JVM, 6 Native, 14 ABI and 13 mailbox tests.
Final changed authoring sources and compiler positive controls also compile.
Seven forbidden snippets fail with valid positive fixtures: missing transaction,
worker transaction capture, array equality, Committed write, undeclared
Committed read, unsupported latest policy, and Presentation-as-Signal.
See `docs/evidence/a0b-20261027.md` for receipts and limits.

The completed independent review challenged authorability and lifecycle details,
especially transactional channel capacity reservations/terminal drain order,
type-preserving modifier implementation for arbitrary custom View subtypes,
reparent outlet removal, and the planned public text/IME contract. The eight findings were resolved and independently re-reviewed. Final review-fix
checks pass: 7 JVM + 7 Native tests and 13 corpus sources, now including eight
negative assertions. These remain provisional interfaces, not engine claims.

## Historical first-draft review (retained verbatim below)

The original review below describes the earlier skeleton and records the
reasons for the revision. Its snippets and open questions are historical;
the accepted package and current disposition table above control current work.

**Status:** first draft, 2026-10-27. The corpus type-checks against a
provisional skeleton of `sirin-core`. Nothing is implemented. Every
signature is meant to be challenged.

The programs live in `examples/authoring/sirin/examples/`:

| # | Program | File |
|---|---|---|
| 1 | Validated form | `ValidatedForm.scala` |
| 2 | Editable keyed list (drafts survive reorder and replacement) | `KeyedList.scala` |
| 3 | Async search with stale-result handling | `AsyncSearch.scala` |
| 4 | Popup with focus restoration | `PopupFocus.scala` |
| 5 | Document command shared by menu, shortcut, and button | `DocumentCommand.scala` |
| 6 | Custom interactive surface (diagram canvas) | `DiagramCanvas.scala` |
| – | Compile-time negative cases | `Neg.scala` |

Verify with `sbt coreJVM/compile coreNative/compile examples/compile`. The
`examples` project is JVM-only and is not aggregated by `root`.

## The API in brief

```scala
type Action = UiTxn ?=> Unit
trait Signal[+A]   { def get(using UiTxn): A; def map[B: Equality](f: A => B): Signal[B]; def zip; def flatMap; def attempt: Signal[Value[A]] }
trait Cell[A] extends Signal[A] { def set(a: A)(using UiTxn): Unit; def versioned: Signal[Versioned[A]] }
trait Inputs[+P] extends Signal[P]
final class Component[P]  // Component[P](name) { (scope, inputs) => View }; placed as comp(props)
trait Scope { cell, keyed, events, ref, effect, on, debounce, latest, launcher, action, command, animate, child, onDispose }
trait Command[-A, +R] { enabled: Signal[Boolean]; running: Signal[Boolean]; def dispatch(a: A)(using UiTxn): Dispatched[R] }
scope.command[A, S, R](id, label, freshness, enabled)(start: A => UiTxn ?=> Job[S, R])(complete: (S, Outcome[R]) => Action)
scope.latest[I, O](input: Signal[I], freshness)(work: I => Work[O]): Signal[TaskState[I, O]]
trait CollectionSource[K, +A]; trait KeyedCell[K, A] extends CollectionSource[K, A]
ForEach(source)(row: A => View);  ForEach(items, key)(row);  Switch(signal)(branch: A => View)
trait Element[S] { paint; measure; hitTest; semantics; pointer(using UiTxn); key(using UiTxn); action(using UiTxn) }
trait LayoutPolicy[S] { def layout(state: S, c: Constraints, children: LayoutChildren): Size }
```

Choices the skeleton makes that VISION leaves open are marked as open
questions below, not as settled semantics.

## Checklist (VISION §4.1)

| Criterion | Verdict | Evidence |
|---|---|---|
| Ownership is clear | **Mostly.** Every cell, command, task, deadline, and animation is created through a `Scope`, and the owner is visible at the call site. Workspace-owned models (`Document`, `Diagram`, `TodoModel`) take an explicit child scope of the application scope. | Weak spots: a `NodeRef` is owned by one scope but attached by a modifier anywhere (#17). An `Element` instance captures model cells, and nothing records that dependency. |
| No unnecessary framework context | **Partly.** `UiTxn` appears only where writes or reads happen, and the editor in program 5 is a plain function with no scope. | Static dependencies (a model, a service) have to travel through `Inputs`, a factory closure, or `Switch(inputs)` (#2). Model helpers carry `(using UiTxn)` (`Diagram.moveSelected`). A prop-less component still needs `(())` (#3). |
| No subscription needs manual cleanup | **Yes, in the corpus.** None of the six programs calls `onDispose`. Effects, debounces, tasks, and animations are scope-owned. | The claim is only as good as the engine. `Element` and `LayoutPolicy` instances hold captured cells, and the engine must not retain them after unmount (VISION §11 trace rule). |
| Nothing remounts accidentally | **No, not by construction.** The corpus avoids remounts only by following a rule the type system does not check. | A component factory called inside a row function compiles and silently remounts every row on every change (#2). Whether `Switch` reconciles or remounts is a skeleton choice (Q5). |
| Nothing uses privileged internals | **True for program 6, but the API is too small to claim first-class extension.** | `Element` has no text-input/IME channel, hover, cursor, focus-visible state, scroll participation, or text measurement. `TextField` could not be written on these interfaces (#18, #20). |

Readability: programs 1, 4, and 5 read top to bottom as "state, commands,
view". Programs 2 and 6 are noticeably harder because of static
dependencies (#2) and the single-state `Element` (#5). Program 3's
task-state pattern matching (#12) is the least readable code in the corpus.

## Awkwardnesses

Each entry has (a) the snippet, (b) why it is awkward, and (c) a proposed
change or a rationale for keeping it. They are ordered roughly by severity.
The `REVIEW #n` comments in the sources refer to these numbers.

### 1. A command cannot refer to its own busy state

(a) `ValidatedForm.scala`
```scala
val submitting = scope.cell(false)
val submit = scope.command[Unit, Account, AccountId](..., freshness = Freshness.Serial,
    enabled = account.map(_.isRight) && !submitting) { _ => submitting.set(true); ... }
  { (_, outcome) => submitting.set(false); ... }
```
(b) "Disabled while submitting" is the most common submit policy. The
command's own `running` signal does not exist yet while `enabled` is being
built, so the author duplicates it by hand. That copy can drift: the
completion handler must reset it on every outcome. `Serial` is also wrong
here, because it *queues* a second submit instead of refusing it. None of
the five VISION policies means "refuse while running" (exhaust).

(c) Two changes. First, give `enabled` access to the command's status:
`enabled: CommandStatus => Signal[Boolean]` where `CommandStatus` has
`running` and `inFlight`. Second, add a concurrency mode that refuses
while running. That second change is a policy change, so it is recorded as
Q11 for the owner of §5.4.

### 2. Static dependencies have no home; misuse silently remounts

(a) `KeyedList.scala`, `DiagramCanvas.scala`
```scala
def todoRow(model: TodoModel): Component[Todo] = Component("TodoRow") { (scope, todo) => ... }
def todoList(model: TodoModel): Component[Unit] = Component("TodoList") { (scope, _) =>
  val row = todoRow(model)            // must be here, once per mount
  ... ForEach(model.todos)(row(_)) ... // `ForEach(model.todos)(todoRow(model)(_))` also compiles
}
val DiagramView: Component[Diagram] = Component("DiagramView") { (scope, diagram) =>
  Switch(diagram) { d => ... }        // unwraps a static prop
}
```
(b) A row needs its model while its view is being built, and that happens
outside any transaction, so `inputs.get` is unavailable. The corpus uses
two workarounds, and both are traps:
- The factory closure is correct only if it is called once per parent
  mount. Calling it in the row function creates a new component type for
  every value change. Under instance identity (Q13) that remounts every
  row, and drafts are lost: the exact failure VISION §4 forbids. It
  compiles without a warning.
- `Switch(inputs)` is correct but obscure. It also makes every derived
  signal inside the branch depend on a branch that is expected never to
  change.

Passing the model inside the props (`RowProps(todo, model)`) is worse.
Props that contain closures or models compare by reference, and a view
that needs the model must still unwrap it.

(c) Split dependencies from inputs. Add `Component.withDeps[D, P](name)
{ (scope, deps: D, inputs: Inputs[P]) => View }`, placed as
`comp(deps)(props)`. A change to `deps` by `Equality` remounts, and that
is documented. Add a development-mode diagnostic: "component `TodoRow`
was defined N times under the same owner and key; state was discarded."
Keep `Inputs` for values that change.

### 3. Components without props

(a) `todoList(model)(())`, `layerPanel(layer)(())`, `header(())`.

(b) The `(())` is noise, and it pushes readers toward factories (#2).

(c) Add `extension (c: Component[Unit]) def view: View`, or a
`Component0` type. Cheap. Adopt it once #2 is settled, because `withDeps`
would absorb most of these uses.

### 4. One freshness enum conflates acceptance and concurrency

(a) `DocumentCommand.scala`
```scala
scope.command[Unit, Versioned[String], Unit](..., freshness = Freshness.SnapshotAssociated, enabled = dirty)
  { _ => val snapshot = text.versioned.get; Job(snapshot, store.write(snapshot.value)) }
  { (snapshot, outcome) => ... savedRevision.update(_.max(snapshot.revision)) ... }
```
(b) *Snapshot-associated* says how a result is **accepted**: tie it to its
input revision and never discard it as old. It does not say how
invocations **interleave**. A save of revision 18 can finish before the
save of 17, which leaves 17's text on disk under a marker that says 18.
The marker uses `max`, so the corpus does not even notice. The correct
policy is serial *and* snapshot-associated. Better still: while 17 is in
flight, keep only the newest of 18 and 19 queued. The single
`freshness: Freshness` parameter cannot express any of this.

Second bug: `enabled = dirty` stays true while revision 17, the current
revision, is being saved, so pressing Save again writes 17 twice.

`scope.latest` accepts the whole enum but supports only two of its values.
Passing `EveryInvocation` there fails only at runtime.

(c) Split the policy into two parts:
- `Concurrency`: `Parallel`, `Serial`, `SupersedePrevious`,
  `RefuseWhileRunning`, `QueueLatest`
- `Acceptance`: `CurrentOnly`, `MarkStale`, `Always`

Keep the five VISION policies as named presets. Give `scope.latest` a type
that admits only its two presets. Expose
`command.inFlight: Signal[Seq[S]]`, so enablement can say "dirty and not
already saving this revision". This changes how policies are composed, not
what they mean, but it belongs to the owner of §5.4 (Q11).

### 5. A custom element has a single state: every change invalidates everything

(a) `DiagramCanvas.scala`
```scala
final case class Scene(nodes: IndexedSeq[Node], edges: Seq[Edge], selected: Set[NodeId],
                       dragging: Option[NodeId], lift: Double)
val scene = (d.nodes.snapshot, d.edges, d.selection).mapN(...).zip(drag.map(...).zip(lift)).map { ... }
```
(b) The element declares one state `S`, so the engine has to treat any
change to `S` as invalidating measure, paint, hit testing, and semantics
together. That breaks VISION §1 ("a color change does not shape text").
`lift` animates every frame during a drag, and each frame then:
- rebuilds `Scene`,
- runs a structural `Equality` over the whole node vector,
- recomputes semantics, which can churn the accessibility tree at 120 Hz.

Every pointer move also copies the node list through `snapshot`, which is
O(n). The element cannot consume `CollectionSource` deltas.

(c) Let elements declare per-phase inputs:
`Element.inputs(layout = ..., paint = ..., hit = ..., semantics = ...)`,
with each phase method receiving only its own input. Presentation-only
values such as `lift` would then be paint-only. Allow
`Equality.reference` for large scene values. For large data, let an
element read a `CollectionSource` through `range` and `changes` at paint
time, from the committed state. This needs a read capability for the
committed state outside a transaction (Q15).

### 6. Enablement cannot depend on the argument

(a) `KeyedList.scala`: `Button("Move up").command(model.moveUp, id)`.
"Move up" is enabled on the first row, where it does nothing.

(b) `Command.enabled` is one `Signal[Boolean]` per command. Per-row and
per-selection commands are common.

(c) Add `def enabledFor(arg: Signal[A]): Signal[Boolean]`, defaulting to
`enabled`, and have `withArg` and `.command(c, arg)` use it. Keep one
`enabled` for argument-free bindings such as menus.

### 7. Refined builders are lost after a generic modifier, with a misleading error

(a) The corpus first wrote
`TextField.bind(draft).label("Title").ref(field).onCommit(commit)`. The
compiler reported:
```
value onCommit is not a member of sirin.View
No given instance of type sirin.UiTxn was found for parameter of sirin.Action
```
(b) `ref`, `padding`, and `semantics` return `View`, so modifiers that
belong to one control must come first. The second error is a cascade: once
`onCommit` is unresolved, the context-function argument is typed in a
context without a `UiTxn`. It points the author at the wrong problem.

(c) Make the generic modifiers extension methods that preserve the
receiver type: `extension [V <: View](v: V) def ref(r: NodeRef): V`. This
needs a small `Modifiable[V]` given per control type. No macros are
needed.

### 8. A plain view cannot be activated

(a) `AsyncSearch.scala`
```scala
Button(s"${book.title} — ${book.author}").command(open, Signal.const(book.isbn))
  .semantics(selected.map(s => Semantics(Role.ListItem, ...)))
```
(b) Only `ButtonView` has `command` and `onPress`. A selectable list row
therefore has to be a Button with its role overridden, which gives the
wrong default semantics and keyboard behavior. `Signal.const` is needed
for a static argument.

(c) Add a generic `.activate(command, arg)` modifier that carries focus,
Enter/Space handling, and an `Activate` semantic action. Add an overload
`.command(c, arg: A)` for static arguments. Better: a `Selectable`
behavior for list rows, which also owns `selected` and `positionInSet`.

### 9. `dispatch` results are discarded everywhere

(a) `model.rename.dispatch((todo.get.id, draft.get.trim)); editing.set(false)` and
`Checkbox(...)(_ => model.toggle.dispatch(todo.get.id))`.

(b) Every call site drops `Dispatched[R]`. With `-Wvalue-discard` each one
would warn. A refusal is invisible to the user, and the handler continues
as if the command had run. In `KeyedList.commit` the editor closes even
when the rename was refused.

(c) Three options:
- Make `dispatch` return `Unit` and surface refusal through the command's
  diagnostics and an `Event[Refusal]`.
- Keep the result and make refusal reject the caller's transaction.
- Keep the result and mark it `@nowarn`-safe.

The choice depends on Q1.

### 10. Literal-or-signal overloads multiply

(a) `Text(String)` and `Text(Signal[String])`; `Button(String)`,
`Button(Signal[String])`, and `Button(Command)`; `Checkbox.bind(cell, "…")`
alongside `Checkbox(signal, Signal.const("Locked"))(…)`; `Window(String)`
and `Window(Signal[String])`.

(b) Each dynamic prop doubles the overloads, and the gaps force
`Signal.const`. VISION §4 wants literal props to stay plain values.

(c) Use `type Prop[A] = A | Signal[A]`, a union matched once inside the
engine. The caveat is `A` itself being a `Signal`, which is rare and can be
rejected with `NotGiven[A <:< Signal[?]]`. Keep `bind(cell)` as the
two-way form.

### 11. No cell lenses

(a) `TodoRow` keeps `editing`, `draft`, and `base` as three cells, where
the model is really `Option[Draft]`.

(b) `TextField.bind` needs a `Cell[String]`, so editing a field of a larger
value means splitting state or writing a manual derived cell.

(c) Add `cell.zoom(get)(set): Cell[B]`. The zoomed cell's revision and
equality semantics need defining (Q6).

### 12. Task state is pattern-heavy, and dependencies leak into its type

(a) `AsyncSearch.scala`: `ResultsView.of` spends 20 lines matching
`TaskState[(String, Catalog), Seq[Book]]`.

(b) Everyday questions are pattern matches:
- What should I show?
- Is it pending?
- Is it stale?
- Did it fail?

The catalog has to be part of the input in order to be a dependency, so it
appears in every pattern (`Pending((q, _), _)`).

(c) Return a `Latest[I, O]` with derived signals: `shown`, `pending`,
`stale`, `error`, and `forInput`. Accept `scope.latest(input, deps = catalog)`
so that dependencies trigger re-requests without joining `I`. Keep
`TaskState` for authors who need everything.

### 13. Worker code could capture a transaction (fixed in the skeleton)

(a) A `Work { cell.get }` literal inside a command's `start` compiled,
because a `UiTxn` was in lexical scope.

(c) **Adopted:** `Work.apply` now requires `NotGiven[UiTxn]`, and
`Neg.scala` checks that this case is rejected. The guard does not stop a
helper method from capturing a transaction, so the runtime thread-owner
check in VISION stays authoritative. The compiler message
(`No given instance of type NotGiven[UiTxn]`) is poor. A dedicated
evidence type with `@implicitNotFound` would be better.

### 14. Four entry points for asynchronous work

(a) `scope.latest`, `scope.launcher`, `scope.command`, and `scope.action`.

(b) `command` is `launcher` plus a label, enablement, and the dispatch
boundary. `launcher` is never used in the corpus.

(c) Keep `latest`, which is input-driven, and `command`, which is
dispatched. Drop `launcher` until a program needs work that is neither a
command nor input-driven. Express `action` as `command` with synchronous
completion, so there is one dispatch boundary.

### 15. A form cannot reveal errors and refuse in one press

(a) Program 1 disables Submit until the form is valid, because
enablement-from-command makes that the easy path.

(b) Many accessible-form guidelines prefer an enabled submit button that
marks every field touched and moves focus to the first error. Under §5.1,
`start` cannot do that. Rejection (`require`) discards every staged write,
including the "mark all touched" writes. The pattern can only be written
with `enabled = always`, writing first and then returning a refusal
without rejecting, which is a third outcome the API does not have.

(c) Add a separate `validate: A => UiTxn ?=> Option[Refusal]` phase whose
writes commit even when it refuses, distinct from `require`, which rejects
everything. This touches §5.1 semantics, so it is recorded as Q2.

### 16. Popup dismissal cannot be vetoed or explained

(a) `Popup(open, anchor = renameButton, ...)`: dismissal writes `false` to
`open`.

(b) There is no hook for "discard your draft?", and no dismissal reason
(Escape, outside press, or anchor gone). The program cannot tell a cancel
from a commit except by its own writes.

(c) Add `onDismiss: DismissReason => Action`, defaulting to
`open.set(false)`. Keep the `Cell[Boolean]` binding for the common case.

### 17. One `NodeRef` for focus, anchoring, and relationships

(a) `scope.ref()` attached with `.ref(r)`, and used by `Popup(anchor = r)`,
`initialFocus = Some(r)`, and `Semantics(describedBy = Some(r))`.

(b) A single concept is good. However:
- attaching the same ref to two nodes is only a runtime error;
- a relationship cannot target a part inside a custom element (an edge
  labelled by a node in program 6);
- nothing says when a ref is attached relative to transactions (Q3).

(c) Keep one concept. Add
`SemanticTarget = NodeRef | (NodeRef, PartId)`. Define attachment timing
in C1.5. Give duplicate attachment a development-mode failure, like
duplicate keys.

### 18. A custom element has no focus model for its parts

(a) `DiagramElement.key`: the arrow keys move the *selection*. Selection
doubles as keyboard focus.

(b) Assistive technology moves focus between semantic nodes. With no
"focused part" (active descendant), the element cannot reflect that, and
it cannot tell focus apart from selection. VISION §3 lists exactly that
mistake among the GPUI lessons. Nothing defines who paints the focus ring
for an element or its parts.

(c) Add a per-element `focusedPart: Cell[Option[PartId]]` managed by the
runtime, with `Focus` semantic actions routed to it. Add
`Canvas.focusRing(rect)` so focus treatment stays consistent with the
design system.

### 19. Theme reads in paint are an undeclared dependency

(a) `canvas.theme.color(ColorRole.Surface)` inside `paint`.

(b) Dependencies are supposed to be explicit, but a theme switch has to
repaint every element without any declaration.

(c) Keep it, and document it. Treat the theme like the font-metrics epoch:
a global paint-class epoch, recorded as a cause in traces. Requiring every
element to declare the theme as an input would be ceremony with no
benefit.

### 20. Canvas text cannot be measured

(a) `canvas.text(n.label, r.origin + Point(12.lp, 14.lp), TextStyle.Label, ...)`.

(b) Nodes have a fixed size because the element cannot shape or measure
text. The baseline offset `14.lp` is a guess. VISION §6 requires one text
layout result for drawing, caret, hit testing, and accessibility, but the
public API does not expose it.

(c) Add a `TextLayout` value obtained from a `TextServices` capability in
`measure` and drawn with `canvas.text(layout, at)`. This is required
before `TextField` could be written on the public API (checklist row 5).

### 21. Custom layout addresses children by index

(a) `children.measure(0, c)` and `children.place(1, at)` in `FloatAbove`.

(b) The layout is coupled to the order of the varargs, so swapping two
children silently breaks it. It cannot see child keys.

(c) Add named slots: `Layout(name, state, policy)(slot("canvas") -> ...,
slot("toolbar") -> ...)`. Keep index access for homogeneous children.

### 22. `mapN` stops at arity 3

(a) The diagram scene needed
`(a, b, c).mapN(...).zip(d.zip(e)).map { case ((..), (..)) => ... }`.

(c) Define `mapN` over a tuple of signals generically with `Tuple.Map`,
using inline and compiletime only, with no macros. Keep explicit
dependency lists. Do not add an auto-tracking `Signal { a.get + b.get }`,
because VISION §5.1 rules out discovering dependencies from reads.

### 23. Optional content is verbose

(a) `Switch(submitError) { case Some(m) => Text(m)...; case None => View.empty }`,
repeated four times.

(c) Add `Show(opt: Signal[Option[A]])(a => View)` as sugar over `Switch`.
Minor.

### 24. Windows are a fixed sequence

(a) `Application.run(build: Scope => Seq[Window])`.

(b) Opening a second document window, the document app in VISION §11,
cannot be expressed.

(c) Give windows a keyed set: `Application.run(app => Windows(source)(w => Window(...)))`,
using the same identity rules as `ForEach`.

### What worked and should be kept

- **`Inputs[P]` as a `Signal`.** Program 2's draft survives replacement and
  reordering with no special code. `changedUnderneath` falls out of
  comparing the draft base with `inputs`. There is no way to read a stale
  captured initial value, because no `initial` exists. Keep this
  deliberately.
- **`Action = UiTxn ?=> Unit`.** Handler bodies read as ordinary code. The
  `NotGiven` guard (#13) and the write guard (`Neg.scala`) come almost for
  free.
- **Enablement from the command.** In program 5 the menu item, the chord,
  and the button share one declaration, and none of them can drift.
- **Snapshot plus completion.** `Job(snapshot, work)` with
  `complete(snapshot, outcome)` makes the revision-17-during-18 law
  something the author can read, in addition to something a test checks.

## Open semantic questions the corpus forced

VISION does not answer these. They are recorded here rather than edited
into VISION. `Q` numbers are cited from the sources.

- **Q1. Dispatch inside a handler.** Is a refused `dispatch` (disabled)
  part of the caller's transaction outcome? If a command's `start` calls
  `require(false)`, nested transactions join the caller (§5.1), so the
  caller's own writes are also discarded (`editing.set(false)` in
  `KeyedList.commit`). Is the command boundary meant to be a transaction
  boundary?
- **Q2. A refusal that commits.** Can an operation refuse while keeping
  some writes (#15)? §5.1 has only "commit everything" and "reject
  everything".
- **Q3. Focus and topology timing.** `field.focus()` is called in the same
  handler that opens a `Switch` branch that attaches `field`, and the
  attachment happens in a later pass. Is a focus request a staged write
  that is resolved after settling? What happens if the ref never attaches?
- **Q4. The restoration target goes away.** The popup was opened from a
  button that the lock then disables (program 4). Possible orders:
  nearest focusable ancestor, the originator's container, or the window.
  Which applies? §3 names focus, activation, and tab-stop eligibility as
  three properties but does not define restoration over them.
- **Q5. `Switch` reconciliation.** Does a re-evaluated branch reconcile
  positionally, as the skeleton assumes, or always remount? Does the
  `ForEach` row function re-run on value change, or does each row get a
  per-key `Signal[A]`? Both affect "no accidental remount" and the cost
  model.
- **Q6. Revisions.** What is the revision of a new cell? Is it per cell or
  a global transaction revision? Does writing an equal value advance the
  revision, given that equality stops propagation? If text is typed and
  then undone back to the saved text, is the document dirty? The
  snapshot-associated save law depends on the answers.
- **Q7. Effect timing.** Does `scope.effect(signal)` run once at mount with
  the current value? Does it run when a value changes and changes back
  within one transaction?
- **Q8. Pointer delivery and transactions.** Is each coalesced pointer move
  its own transaction? Who chooses the delivery policy for an element's
  pointer stream?
- **Q9. Unplaced children.** In a custom layout, is a child that is never
  `place`d hidden, excluded from hit testing, and excluded from semantics?
  Can `measure` be called twice on the same child within one layout, given
  the intrinsic-cost warnings?
- **Q10. Command status under concurrency.** What do `running` and
  `Invocation.outcome` mean under `EveryInvocation`? Who owns an
  `Invocation`'s signal, and when is it released?
- **Q11. Policy composition.** Is snapshot-associated meant to imply serial
  execution (#4)? Should "refuse while running" be a VISION policy? Should
  snapshot-associated completions be delivered in dispatch order?
- **Q12. `Failed` in bindings.** What does `Text(signal)` show when the
  signal holds `Failed`? Does a `Failed` value in `enabled` disable the
  command? The corpus used `Either` for validation, and `Value.Failed` was
  never the natural tool. That suggests `Failed` is for defects, not
  domain errors, which VISION should state.
- **Q13. "Component type".** Is a component's type its definition instance
  (as in the skeleton), its name, or its class? This decides whether #2's
  factory misuse remounts.
- **Q14. Shortcut precedence.** In what order do these apply: window-wide
  menu chords, a subtree's `.shortcut`, and a focused control's own keys?
  Examples are Enter in a `TextField` versus `.shortcut(Primary+Enter)`,
  and Backspace for "delete node" versus text editing. Without a native
  menu bar on Windows and Linux (VISION §6), where do menu chords live?
- **Q15. Committed reads outside a transaction.** Element handlers receive
  `s`, the committed state from the interaction snapshot, but they read
  `diagram.selection.get`, the staged state. When two events arrive in one
  frame, these can disagree. Which one is authoritative? Paint and layout
  currently get values only. Is there a read-only committed-state
  capability?
- **Q16. Animation and the transaction system.** `scope.animate` returns a
  `Signal[Double]`. Is each animation tick a transaction and a settle
  loop? If it is not, how does a presentation value enter a derived signal
  such as `Scene`, given that §8.2 says motion animates presentation
  properties?
- **Q17. `setAll` deltas.** When a key is both moved and replaced in one
  `setAll`, are the delta order and the row re-evaluation order defined?
- **Q18. Streaming results.** `Delivery` (coalesce-latest, ordered,
  complete-stream) has no public shape for workers that emit progress. No
  program needed it, so it remains unexercised.

## Not yet exercised

The six programs do not exercise these, so they are unreviewed:
- virtualized `ForEach` and realization,
- `reparent` and `GlobalKey`,
- `Event` combinators beyond declaration,
- `launcher`,
- `Delivery`,
- `Value.Failed` in a program,
- multiple windows,
- IME,
- reduced motion.

A program that uses each should be added before the API is frozen.
