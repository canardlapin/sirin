# Implementation and process review, 2026-10-30

Scope: the public API, desktop/host code and working practice on `main` at
`1d5edcc`. The review read source; it did not build or run anything. The
existing authoring review (`examples/REVIEW.md`) already disposes of 24 API
awkwardnesses, and this record does not repeat them.

Owner direction (2026-10-30): record these findings so that they steer
development, including VISION. The VISION amendments are listed at the end and
cross-referenced from VISION's review notes. No kernel semantics (VISION §5)
change.

## Findings and directives

Each finding gives the evidence, the directive that now applies, and the Mote
issue that tracks it. Directives are binding on new work. Existing code is
brought into line through the tracked issues, not through drive-by edits that
collide with reserved paths.

### D1. Built-in controls must be built on the public extension seam

**Evidence.** VISION §1 requires that extension mechanisms support first-class
components without private hooks, and §4 says standard widgets are expressible
with the public interfaces. On `1d5edcc` the public seam is unimplemented:
`Element.apply` and the three `Layout` constructors are `???`
(`core/src/main/scala/sirin/Element.scala:62,82-84`), as are `Checkbox`,
`Overlay` and `Popup` (`core/src/main/scala/sirin/View.scala:164,167,193,226`).
`TextField` works, but through host-internal code
(`host/src/main/scala/sirin/host/TextEditing.scala`,
`desktop/src/main/scala/sirin/desktop/EditorController.scala`).

**Risk.** Each control built on internal paths widens the gap between what
built-ins can do and what users can do. Retrofitting later is expensive, and the
gap is exactly what §1.1's ScalaFX comparison will expose.

**Directive.** VISION §8 gains a public-seam rule: a standard control is built on
`Element`/`LayoutPolicy`/semantics, or it is listed in a public-seam ledger with
the missing public capability and a tracking issue. No new control may add a
host-private hook without a ledger entry. Alpha4 completes the seam. A follow-up
issue migrates `TextField` and `Button`.

### D2. One drawing vocabulary

**Evidence.** Two canvases exist. Core `sirin.Canvas` (`Element.scala:23`) offers
rectangle fill/stroke, lines and text in `Lp`, with scopes named
`withClip`/`withOffset`/`withOpacity`. Graphics `Canvas2D`
(`graphics/src/main/scala/sirin/graphics/Drawing.scala:41`) offers paths,
paints, images, glyphs, `draw(drawing)`, and scopes named
`transformed`/`clipped`/`layer` in raw doubles. A custom `Element` cannot draw a
curve, gradient or image, nor embed a recorded `Drawing`. The diagram example
can therefore draw only straight edges.

**Risk.** VISION §7.1 says a handful of widget-painting primitives does not meet
the graphics bar, and that custom graphics are a first-class product. Two
vocabularies, two naming schemes and two unit systems make that bar unreachable
from the UI side and teach users two libraries.

**Directive.** VISION §7.1 now requires the UI paint surface to expose the
complete public drawing model: paths, paints, images, transforms, layers and
embedding a `Drawing`. Convenience operations stay, but as sugar over the same
model. Scope names and argument order follow `Canvas2D`. Logical units (`Lp`)
are the authoring unit on both, and a conversion is explicit where device units
are needed. The exact API belongs to Alpha4 and is reviewed against the
authoring corpus before Alpha6.

### D3. State the platform position honestly and decide delivery order

**Evidence.** VISION §1 says Native-first delivery. Everything runnable today
(windows, text editing, AccessKit, the workbench) runs on the JDK 25 FFM host.
The Native GC safepoint stall (P0.6, `bd-01M5Z3K47RQTRDXEY7Y2P8TMJ3`) has been
open at P0 with no causal remedy, and the most recent gate attempt could not
even run its idle preflight. VISION §1.1 still says the JVM desktop toolkit is
only a target. Native window bindings now exist both as Java FFM classes
(`NativeWindow.java`, `NativeApplication.java`) and as Scala Native externs,
which doubles the surface that the shim rules (§6.1) must cover.

**Directive.** VISION §1.1 records the actual position: the JVM host is the
current development and acceptance path, and Native delivery remains the
intended order contingent on P0.6. Before Alpha6 acceptance, the owner makes an
explicit delivery-order decision with the P0.6 evidence in hand. Both binding
layers share one C shim and header. Neither may add a native entry point that
the other cannot reach without a recorded reason.

### D4. A lightweight form of `Element`

**Evidence.** `Element[L, P, H, S, A]` takes five type parameters, and
`ElementInputs` requires five signals. Precise per-phase invalidation is
correct, but a small custom control pays the full cost.

**Directive.** The seam must offer a single-state form, e.g.
`Element.simple[S](state: Signal[S])`, that feeds one input to every phase and
declares that it invalidates all phases on change. The per-phase form remains
for surfaces that need it. Both appear in the authoring corpus.

### D5. Implement motion before the API freezes

**Evidence.** `Presentation.const`, `Presentation.map` and
`LayoutPresentation.from` are `???`
(`core/src/main/scala/sirin/Theme.scala:36,39,44`). The drag lift in the diagram
example and the reduced-motion program are shapes only.

**Directive.** Motion (§8.2) is implemented and tested within the alpha. It must
not be deferred past API review, because timing and transaction interactions
tend to force late API changes.

### D6. Make transaction-attempt boundaries visible in authoring

**Evidence.** In the validated form, the fields are marked touched outside the
dispatch's implicit attempt so that a refused submit keeps those writes. Only a
source comment explains this. It is correct under §5.1, and easy to get wrong
without noticing.

**Directive.** No semantic change. Authoring documentation and the corpus must
show the attempt boundary explicitly: either a named API (for example a
distinct "outside the attempt" block) or a documented, prominent pattern with a
negative test showing the failure mode. The choice requires a design note,
because it touches §5.1 vocabulary.

### D7. Bulk transfer across the native boundary

**Evidence.** Hot paths copy element by element:
`desktop/src/main/scala/sirin/render/BatchGpu.scala:58,68,70` (mesh positions
and image assets), `desktop/src/main/scala/sirin/graphics/unicode/NativeUnicode.scala:29,92,161`,
and a per-pixel `Array.tabulate` in
`desktop/src/main/scala/sirin/desktop/FrameWorker.scala:215`.

**Directive.** VISION §6.1 gains a bulk-transfer rule. Arrays cross the boundary
with one bulk copy (`memcpy`, `MemorySegment.copy` or equivalent) or a recorded
borrow. Per-element loops are allowed only off hot paths, with a comment.

### D8. The presentation round trip is a known latency cost

**Evidence.** The development host renders offscreen, reads back, and uploads a
texture for presentation. The README admits this. Measured action latencies
(p95 about 24 ms) include the round trip.

**Directive.** Latency receipts state whether they include readback/upload.
Direct presentation is tracked under the graphics programme
(`bd-01M5ZV4M9SB36V3BJACKJXDJFW`). Responsiveness claims compared against
ScalaFX use the direct path or state the handicap.

### D9. One code style

**Evidence.** Core reads cleanly. The desktop, host and probe code has many
lines over 160 characters, with `;`-chained statements. The worst files are
`EditorWorkbench.scala`, `FrameWorker.scala`, `DesktopWorkbench.scala` and
`NativeWindow.java`. That is where threading and ownership logic lives.

**Directive.** AGENTS.md now requires one statement per line and lines of at
most 120 characters in new or edited code, matching core. Existing files are
reformatted under a tracked issue, without behavior changes and outside
reserved paths.

### D10. Evidence weight and a human status page

**Evidence.** `docs/` held about 2,000 evidence files (49 MB) against about 49k
lines of main Scala source. VISION is 1,747 lines. Mote notes are compressed
until they are hard to read. No single page says what works today.

**Directive.** Evidence stays mandatory (AGENTS.md), but its weight changes:

- Commit a readable evidence note: what was claimed, how it was checked, and
  the result. Keep raw logs, tarballs, frame captures and source snapshots
  larger than 1 MB out of git. Store them as CI or release artifacts, or under
  `native/vendor`-style ignored paths, and record their hashes in the note.
- `docs/STATUS.md` states, in plain language, what works, what is partial and
  what is missing. Every task that changes capability updates it.
- Mote notes are written as sentences a person can read.

Existing committed evidence is not rewritten out of history.

### D11. Worktree lifecycle

**Evidence.** On 2026-10-29 eight worktrees and nine merged branches had
accumulated. Two held uncommitted state that existed nowhere else; it had to be
archived (`archive/gc-threading-wip-20261029`, `archive/editor-wip-20261029`)
before cleanup.

**Directive.** AGENTS.md now requires each agent task to run in its own
worktree, named in the task's Mote claim. When the branch merges, the agent
removes the worktree and deletes the branch. Uncommitted state is never left in
a worktree at hand-off: commit it, or archive it to a named branch and say so
in the hand-off note.

## VISION amendments made with this record

| Section | Change |
|---|---|
| §1.1 | Current platform position; delivery-order decision before Alpha6 (D3) |
| §6.1 | Shared shim for both binding layers (D3); bulk-transfer rule (D7) |
| §7.1 | UI paint surface exposes the complete drawing model; one vocabulary (D2) |
| §8 | Public-seam rule and ledger (D1); lightweight `Element` form (D4); motion before API freeze (D5) |
| §11 | Evidence weight and status page (D10); latency receipts state the presentation path (D8) |
| Review notes | Round 4 entry pointing here |

D6 (attempt boundaries), D9 (style) and D11 (worktrees) are working rules in
AGENTS.md and authoring guidance. They do not change VISION.
