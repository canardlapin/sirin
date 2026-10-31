# Text, Row and Column on the public seam

`Text`, `Row` and `Column` now use only public APIs, following the VISION §8
public-seam rule. `Text.Description` is a `View.Proxy` over a public `Element`.
`View.Stack`, behind `Row` and `Column`, is a `View.Proxy` over a public `Layout`.
The private paths for them are deleted from engine and host: `StackSlot`,
`MountedSnapshot.Group`, the `Text` leaf capture and `Content.Label`, with its
measure and paint code. `Content.Stack` remains only for `ForEach` rows, which
are not on the ledger.

## Text

`TextElement` measures its content at the offered maximum width and paints it at
the measured width, as Button does with its label. Within the widest measured
line, greedy wrapping breaks the same way at both widths. Layout depends on
content and style. Paint also depends on color, so a color change does not
reshape text.

Text now has semantics. It exposes one `Role.Label` node named by its content.
The old host label had none, which left static text invisible to assistive
technology. Native transport is unchanged and still publishes button and text
input nodes only. The `Label` nodes appear in headless semantic snapshots.

## Row and Column

`StackLayout` is the old host stack algorithm as a `LayoutPolicy`:
- fixed children are measured first;
- `.grow` children share what remains;
- cross-axis alignment and stretch work as before;
- collapsed children get no gap;
- baseline alignment is still refused.

The policy needs two facts about children that public layouts could not
previously see. Two members are added to `LayoutChildren`:

- `grows(i)` reports whether the child is marked `.grow`, which is a public
  decoration.
- `collapsed(i)` reports whether the child shows nothing: it is empty, or hidden
  with `.visible`.

These are reads of public decorations, so they are available to any layout and
not only standard controls.

## Identity: keyed layout children

Stacks have always identified a child marked `.keyed` by its key, so the child
keeps its state across reorders. `ReconciliationSuite` covers this. Public
`Layout` children were identified only by slot name. Moving stacks onto
`Layout` would have broken keyed reorders, so `Layout` now follows the same
rule:
- a keyed child is identified by its key, and can move between slots;
- an unkeyed child is identified by its slot;
- duplicate keys are refused.

This states one identity rule for every static child. VISION §5.2 previously
said only "static children are positional". It now records the keyed exception
explicitly. No other transaction, lifetime, scheduling or command-policy
semantics change.

## Costs and limits

- Every `Text`, `Row` and `Column` is now a mounted element or layout with its
  own scope and frame. The host's global text-unit capture budget no longer
  covers `Text`. Each element is instead bounded by the 1,024 text requests per
  element limit. Dense label-heavy views have not been profiled after the port.
- Node identities for these controls change. They are now
  `("element", instance)` and `("layout", instance)` instead of tree-path
  positions. Focus and caches key on these identities, which are now stable
  across reorders.
- `Scroll`, `VirtualRows` and `DockOutlet` remain on host paths. They involve
  viewport feedback, realization and retained placement, and are the next
  tranche.
