# Scroll and VirtualRows on the public seam, 2026-10-31

## Claim

`Scroll` is built from public parts only:
- a component that owns the offset;
- a `Layout.reporting` layout that clips and places the content;
- a focusable public element that handles the wheel and keys.

`VirtualRows` places its rows with a public `LayoutPolicy`, and its realized
range follows the public viewport report. `View.live` lets a component wrap
content its caller supplies without remounting it. The host's private scroll and
virtual paths are deleted. `DockOutlet` has no private host code. The design is
in [the decision](../design/decisions/2026-10-31-viewport-controls.md).

## Checks and results

Run on macOS arm64 with JDK 25.0.4.1 and the pinned Scala and Scala Native
toolchain, from task branch `viewport-port-20261031`.

- **`ScrollSeamSuite` (host): 4 new tests.** A headless host loop lays out,
  writes viewport reports back until they settle, then installs input geometry.
  - In a 100 by 80 viewport, a 100-row virtual list with 20-unit rows reports
    its full 2,000-unit height and an 80-unit visible part, and realizes 4
    rows. The scroll reports content of 2,000.
  - Page Down moves the visible part to 72 and realizes rows 3 to 7. The first
    of those rows is placed at -12. A wheel step of -2 moves it to 152. End
    clamps to 1,920, a further arrow stays at 1,920, and Home returns to 0.
    These are the old host's numbers.
  - An empty list reports no visible height and realizes nothing.
  - The wheel over a block that ignores it scrolls the scroll beneath it. Over
    an element that handles the wheel, the scroll does not move.
  - A new description of the scroll's content keeps the offset and the mounted
    content. The same scroll moved into a new position remounts with offset 0
    and new content.
  - A ref on a scroll focuses its surface, Page Down clamps to 150 - 80, and a
    chord the scroll does not use falls through to shortcuts.
- **`LiveContentSuite` (engine, JVM and Native): 3 new tests.**
  - A live child keeps its component state when it gets a new description of
    the same kind, and remounts on a new key or kind.
  - Failed live content is `Unavailable`, its state is disposed, and recovery
    builds fresh state.
  - A reporting layout's cell receives the host's report. A report after
    unmount is refused, and negative geometry is rejected.
- **`InteractionSuite` (host): 1 new test, 1 adapted.** A ref on a view that
  cannot take focus focuses the first focusable view inside it, and a direct
  ref still wins. The remount-focus test now uses element identities.
- **Existing behavior.**
  - `RetainedVirtualSuite` passes unchanged except for the new snapshot field.
    It covers realization bounds, overscan, retained drafts, stale windows and
    per-node viewports. So do the testkit virtual-collection agreement checks.
  - `LayoutClippingSuite` checks the scroll surface's focus mark against the
    same clip as before.
  - The desktop public-workspace render matches the committed reference
    `application-alpha/workspace/workspace.png`, including the scrolled list,
    apart from the timing-dependent search status text.
- **Removed tests.** The host-only scroll test in `HostSuite` and the
  `TargetKind.Scroll` key test in `InteractionSuite` tested deleted code. Their
  numbers and properties are checked by `ScrollSeamSuite` through the public
  scroll. `EditorControllerSuite` no longer checks that a focused host scroll
  reserves its keys: elements receive keys before shortcuts, and
  `ScrollSeamSuite` checks what falls through.

Suite results:

- Full `sbt testJVM`: 439 passed (433 before, plus 8 new, minus the 2 removed).
- Full `sbt testNative`: 439 passed.
- `desktopJVM/test`: 113 passed. `WindowRenderRecoverySuite` now runs the
  viewport feedback loop before presenting.
- `examples/compile` passed.

## Changes in observable behavior

- Scroll offsets are component state, changed in transactions. A remounted
  scroll starts at 0, as before.
- Every `Scroll` reports its viewport. The first frame of a scroll, and any
  frame whose report changes, takes a second layout pass before presenting.
- A virtual row gets a tight row height, not its natural height.
- `NodeRef.focus()` on a scroll focuses its surface.
- The wheel reaches a scroll through content that ignores it, including
  buttons.

## Limits

No native window was run, so scrolling has not been reviewed at real frame
rates or with a trackpad. `DesktopWorkbench`'s scripted scenario was not run;
its Page Down step now goes through element routing. Scroll remains vertical,
with no anchoring, chaining or momentum, and exposes no semantics.
