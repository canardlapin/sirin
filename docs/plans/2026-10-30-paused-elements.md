# Public elements: paused WIP, 2026-10-30

Implementation paused at the owner's request for a design and worktree stocktake.
Base main: `1d5edcc5152e9c895f01cafefc041fbe8737b8c8` (all nine CI jobs passed).
This archive preserves unfinished source; it is not a merge candidate.

Implemented provisionally: mounted Element/Layout descriptions, guarded immutable
phase snapshots, phase-specific revisions, prepared-text request declarations,
affine Canvas bridge, constrained named children, bounded size cache, host hit /
capture / semantic routing, desktop input wiring, and a public diagram model/view.

Observed checks: five engine ElementSuite JVM tests and four host ElementHostSuite
JVM tests pass. Earlier focused compile/test failures remain in `/tmp/sirin-elements-*`.
`examples/compile` passed before the later wheel/cache/desktop/diagram additions.
The diagram compile currently FAILS under -Werror: generated main `diagramEditor`
collides by case with `DiagramEditor`. No full JVM/Native suite or desktop compile
has run on this WIP. No diagram model/runtime tests or real client validation exist.
Do not infer those checks from the base main CI.

Resume only after reconciling the concurrent design review. In particular, a
working diagram does not prove that built-in controls use the public seam. The
next acceptance step must include a real built-in port, complete drawing access
through the graphics adapter, lifecycle/input failure tests and the mandatory
JVM/Native suites. Constant Presentation is not animation; native semantic part
transport, global epoch invalidation and pointer queue policy remain open.
