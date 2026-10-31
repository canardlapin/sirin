# Text, Row and Column on the public seam, 2026-10-31

## Claim

`Text`, `Row` and `Column` use only public APIs: an `Element`, a `Layout` with a
`LayoutPolicy`, and the new `LayoutChildren.grows` and `collapsed`. The host's
private label and group paths are deleted. Keyed children keep their identity
through public layouts. The design is in
[the decision](../design/decisions/2026-10-31-layout-controls.md).

## Checks and results

Run on macOS arm64 with JDK 25.0.4.1 and the pinned Scala and Scala Native
toolchain, from task branch `layout-port-20261031`.

- **`StackLayoutSuite` (host): 4 new tests.**
  - A hidden child takes no space and no gap.
  - Grow children split the leftover space evenly.
  - Start, center, end and stretch alignment place children as before, and
    baseline alignment is still refused.
  - In a named `Layout`, keyed children move between slots without being
    rebuilt. An unkeyed child keeps its slot's identity. Duplicate keys are
    refused.
- **Existing behavior.**
  - `ReconciliationSuite`'s keyed-reorder test passes unchanged, now through the
    public layout: drafts survive `Column` reorders.
  - `MotionPresentationSuite`, `DiagramSeamSuite`, `DocumentWorkspaceSuite`,
    the window lifecycle, recovery and key-safety suites, and the testkit
    agreement suites all pass. Their snapshot walkers now visit
    `CustomLayout`, and `Text` content is read from the element's text
    requests.
  - The `HostSuite` capture test still follows live text, style and
    visibility.
- **Pixels.** A scratch render was inspected and not committed. It covered a
  heading, a `Row` with a grow spacer pushing accented text to the edge, a
  wrapped paragraph, and a spacer indent. All drew in the expected places.

Suite results:

- Full `sbt testJVM`: 433 passed (429 before, plus 4).
- Full `sbt testNative`: 433 passed.
- `desktopJVM/test`: 113 passed.
- `examples/compile` passed.

## Changes in observable behavior

- `Text` exposes a `Role.Label` semantic node named by its content. Headless
  `semanticParts` now include it. Native transport is unchanged.
- Captured node ids for these controls are element and layout instances, not
  tree positions.
- `Layout` honors `.keyed` on children (VISION §5.2 updated).

## Limits

No native window or real assistive-technology session was run. Dense
label-heavy views were not profiled after the port, which adds one mounted
element per `Text`.
