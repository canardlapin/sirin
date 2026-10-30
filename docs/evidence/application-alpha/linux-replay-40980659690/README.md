# Linux Native reproduction: three predeclared attempts

[Run 40980659690](https://github.com/canardlapin/sirin/actions/runs/40980659690)
executed the exact diagnostic source b0facfb8fb28c05229c731065df4c5287d785874.
All three full Native-suite attempts passed: 327 cases each, 981 executions.
Each includes the original randomized 300-program property, the additional
exact-seed suite, and the frozen 40-step counterexample. This is three executions
of 327 distinct cases, not 981 distinct tests. No attempt timed out. The runner
reported four logical CPUs. Raw logs, protocol, results and artifact metadata
are retained here, independently of GitHub artifact expiry.

The original Linux CI failure remains in ../ci-linux-baseline and is unresolved.
These passes do not identify its ClassCastException cause, repair main's failed
CI run, prove absence of a sporadic runtime fault, or qualify native UI/GC.
The fixed three-attempt protocol ran every attempt regardless of prior outcome.
Later window source changes are absent from this commit and are not tested here.
