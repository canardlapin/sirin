# Scroll, VirtualRows and DockOutlet on the public seam

This note closes the VISION §8 public-seam ledger for controls. It follows the
[layout-controls port](2026-10-31-layout-controls.md).
- `Scroll` is now a public composition.
- `VirtualRows` lays out its rows with a public `LayoutPolicy`, and it learns
  its range from public viewport feedback.
- `DockOutlet` turned out to have no private host code. It is kernel structure,
  so it leaves the ledger.

The host's private scroll and virtual paths are deleted: `Content.Scroll`,
`Content.Virtual`, `TargetKind.Scroll`, and the host-owned scroll offsets in
`FocusState`, `FramePayload` and `LayoutResult`.

## Two new public capabilities

**Viewport feedback.** `Layout.reporting(name, state, policy, viewport)` declares
a layout whose host writes a `Viewport` into the `viewport` cell. Each
`Viewport` holds the layout's size, the part left visible after clipping, and
the size of the box around its placed children, all in the layout's own
coordinates.

This is the feedback loop that virtual rows already used, made public. VISION
§5.3 keeps its rules:
- the host writes the reports in a transaction of its own;
- the host lays out again until the reports stop changing;
- only then does it present.

So a report never reaches the screen paired with stale layout. A stale report
for an unmounted layout is refused. `MountedView.reportViewport(instance,
viewport)` is the one entry point for virtual rows and reporting layouts, and
the desktop hosts call it.

**Live content.** `View.live(content: Signal[View])` is a child whose description
comes from a signal. Without it, a component could not wrap content its caller
supplies. A component builds its body once, and built children never see a new
description. A live child is reconciled exactly like a static child:
- a new description of the same kind and key updates the mounted child in
  place and keeps its state;
- a different kind or key remounts it;
- a failed signal makes the child `Unavailable`, and recovery mounts fresh
  state.

The live child adds no node to the snapshot.

## Identity and focus rules

These are explicit design updates. Both are recorded in VISION:

- **§5.2 identity.** `View.live` is a static child whose description comes from
  a signal. It adds no lifetime model of its own.
- **NodeRef focus.** A ref on a view that cannot take focus itself, such as
  `Scroll`, which is now a layout, focuses the first focusable view inside it.
  The ref contains focus but is not itself focused, so `hasFocus` stays false
  and `containsFocus` becomes true. A ref attached directly to a focusable view
  still wins.

No transaction, lifetime, scheduling or command-policy semantics change.

## Scroll

`Scroll(content)` is a component (`core/src/main/scala/sirin/ScrollControl.scala`):
- **State.** The component owns the offset cell and the viewport cell.
- **Layout.** A reporting layout measures the content at unbounded height, clamps
  the offset against it, and places the content at that offset. A layout already
  clips its children to its bounds.
- **Input.** A focusable element sits under the content. It handles the wheel,
  arrows, Page Up, Page Down, Home and End. It clamps against the last reported
  viewport, as the host did before. It paints the same keyboard focus mark.

Two host input changes support this:
- The wheel is offered to every element under the pointer, topmost first,
  until one handles it. This happens in one transaction. Without it, the wheel
  over a button inside a scroll would have stopped at the button.
- The bounded development workbench (`DesktopWorkbench`) now routes wheel and
  key input to elements first, as the application host already did.

The offset is component state, so a scroll keeps it across new descriptions of
its content and loses it on remount. Before, the host held offsets by instance,
outside transactions. Each scroll step is now a transaction.

## VirtualRows

Realizing rows stays kernel structure, like `ForEach`. VISION §5.2 lists
*realized* as a lifetime state. The kernel slot guarantees several things:
- stale windows are refused;
- the full source is never snapshotted;
- retained descriptions are kept;
- viewport, rows and revisions publish or roll back together.

Its geometry is now public. Rows are placed by the public `VirtualRowsLayout`
policy, which the slot instantiates as a reporting layout frame. Its viewport
report becomes the slot's row viewport. The policy gives each row a tight row
height, where the old host gave the row's natural height and then overrode it.
Outside a scroll, the policy reports a height clamped to its constraints rather
than overflowing them.

An application can build the same thing from public parts: a component, a
viewport cell, `CollectionSource.window`, `ForEach` and `Layout.reporting`.
`MountedSnapshot.VirtualRows` carries the frame as a new last field.

## DockOutlet

The host only passes an outlet's retained content through, as it does for
components and switches. It has no outlet-specific layout, paint, hit testing
or input. Reparenting and outlet placement are §5.2 kernel identity rules. So
`DockOutlet` was never a control on a private host path, and it leaves the
ledger without code changes.

## Costs and limits

- Every `Scroll` now asks for feedback. Each scroll adds one transaction for the
  scroll step. A frame whose reports change, the first frame for example, adds a
  second layout pass. While scrolling, the scroll's own report is unchanged, so
  only a virtual list inside it pays a second pass, as it already did.
- Hosts and test harnesses that render a `Scroll` must run the feedback loop.
  The application and workbench hosts do. The editor host shows no `Scroll`.
- Scroll is vertical only, as before. There is no scroll anchoring, nested
  scroll chaining or momentum.
- Scroll exposes no semantics of its own, as before.
