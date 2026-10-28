# Retained outlets, virtual collections and host contract review

Base checkpoint: `541a697`. Scope: headless retained attachment, synchronous
filtered revisions/batches, tagged range windows, fixed-height realization and a
concrete next-host/ScalaFX acceptance plan. VISION was updated before semantics.
The continuing independent reviewer performed source review only, without builds
or edits. This is not full Stage 1 acceptance or interactive qualification.

Findings corrected and re-reviewed:

1. Filtered reads originally used old materialized storage inside handlers.
   Private speculative provenance now follows staged source/query values and
   revisions, including chains and ordinary failure recovery. Delivered backing
   lifetimes remain validated; escaped provisional sources cannot serve lookups.
2. Virtual range/row maps originally dropped supplied equality. Explicit keyed
   sequence and value equality now preserve signed zero and custom equality when
   Scala equality is coarser. Retained Inputs regressions exercise both paths.
3. Rolled-back refs/panels could escape registration. Undo now invalidates them
   permanently; ordinary attachments and missing/live outlets reject leaked
   handles. Failed publication clears candidate claims while preserving committed
   attachments.
4. Scrolling compared entire materializations through binding and zipped range
   equality. Identical immutable Value/vector shortcuts avoid those comparisons.
   The 20,000-row regression asserts no filter calls and fewer than 5,000 value
   comparisons over five viewport moves. No full snapshot path is used.
5. The full-state oracle found premature row retirement when query/viewport
   changed together. Projections settle before realization, and unequal retained
   Input delivery defers descendant decisions. Cross-sibling and separate-root
   permutations additionally resolve the final requested query before mounting.
6. Requested projections that throw fresh exceptions could starve delivery by
   changing Failed identity every pass. Per-publication dependency-keyed caching
   includes failures. Dependency-aware staged memo invalidation preserves query
   failures across unrelated materialization writes while real writes retry.
   Cache-hit, dynamic branch, backing validation and revision reads record edges;
   source writes invalidate the reverse closure; recomputation replaces obsolete
   edges. Rollback/disposal/view changes clear values and dependency metadata.

Final bounded source verdict: **no remaining blocker**, conditional on final
frozen-source JVM/Native verification. Main-session validation is recorded in the
[structural receipt](../../evidence/c1-structural-20261028.md).

The independent collection oracle qualifies projection revisions, interval
membership, row identity/drafts and root refusal. Deterministic controls cover
staged reads, nested rollback, failures/recovery, capacity preflight, source
rebinding, per-node/stale viewports, stale range rejection, retained work and
registration lifetimes. The oracle is not a model of rendering, focus, IME,
accessibility, batch replay, arbitrary worker interleavings or remote paging.
Existing batch construction retains its separate tests and full synchronous
costs; this tranche does not claim an optimized million-row diff.

The host plan uses current pure graphics/text bridges, keeps missing native/JVM
adapters explicit and treats JDK 25 FFM as a development spike. Native-first
shipping and the separate human-visible ScalaFX superiority gate remain intact.
