# Responsive latency qualification follow-up

Continue isolated candidate `d7c4e48`; preserve the active G2 track. The fixed
fixture,20 warmups/class,100 measured samples/class and50/100ms action and
100/200ms scroll p95/p99 limits remain unchanged. A diagnostic run under load
cannot establish quiet-run qualification.

The saved complete benchmark has15 of16 >50ms action samples on Return while
Toggle activation is focused (control80 in that receipt). Tab samples stay below
50ms. This is a correlation, not proof that machine load caused the tail.

Implementation changes before another qualification:

- After native retention succeeds, the JVM retained handle stores only immutable
  target metadata. Native buffers already own uploaded data. It must not retain
  the compiled float array or the complete drawing/resource graph.
- Retain prior tile variants by complete position/signature/target. A valid prior
  image can satisfy a reverted state without compilation/upload/dispatch/readback.
  Keep both existing32-program/24MiB native inventories. Each cache admits at
  most256 snapshot entries and1,048,576 reserved output pixels, including inactive
  and incomplete entries. Inactive least-recently-used entries evict first;
  currently assembled output tiles stay protected. Cancellation removes incomplete
  entries before reuse. Size/scale/device change clears the cache.
- Base snapshot retirement invalidates every composite entry that references
  that source image, including its native program. No evicted base snapshot may
  survive through a composite cache key or retained full-scene graph. This keeps
  the original two-cache readback budgets meaningful. Rendered complete frame
  ownership and one-slot mailboxes remain unchanged.
- Keep one active entry per tile position. Only active snapshots compose a frame;
  inactive variants cannot appear in the visible image.

Verify pixel equality, reverted-state zero upload/readback, capacity eviction,
source invalidation, partial cancellation/restoration, resize and owner retirement.
Run required JVM/Native aliases and desktop checks. Record startup separately from
warm input latency, including total first-presentation and render-session setup.
A future quiet-run preflight uses three interval CPU samples, at least85% idle in
each and1-minute load no greater than half the logical CPU count. The previous
qualification had no numerical idle preflight; do not relabel its failure.

The benchmark refuses any event or presented frame outside the frozen600x360
logical /1200x720 physical fixture, including a1x display or a resize. Record
geometry in every phase row and the final receipt. The packaged runner validates
geometry, class counts, raw samples and unchanged percentile targets before it
can mark qualification. Preserve failed partial receipts. An explicit
`benchmark-resize-control` launch changes the native window after its first
presentation to prove refusal before lower-cost samples can be collected.

## Owner direction: proceed with the available host

On2026-10-29 the owner stated: “well, not gonna get much quieter. please
proceed”. This waives the numerical quiet precondition for continued development
and this tranche's available-host latency acceptance. Do not wait for85% idle
or ask for that permission again. Keep the same fixture, sample counts and
p95/p99 latency targets. Record host conditions and preserve every run.

Use the existing diagnostic runner so its `qualified=false` continues to mean
that formal quiet qualification was not established. A complete, independently
validated target pass may satisfy the owner-authorized available-host gate;
record that decision separately from quiet qualification. Prior failures and
quiet refusals remain unchanged. If targets miss, use the phase/profile evidence
to make a concrete bounded improvement before another measurement.
