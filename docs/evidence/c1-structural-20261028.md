# Retained outlets and virtual collections, 2026-10-28

Base checkpoint: `541a697`, macOS arm64. Authorized tranche: retained panels,
filtered/virtual collections, and the next interactive-host/ScalaFX contract.
Concurrent graphics/text/native edits remain preserved and outside this checkpoint.

## Implemented and bounded

`Scope.ref`, `DockOutlet` and `Scope.reparent` now have headless attachment and
placement semantics. One runtime-global key owns stable content independently of
its outlet. Moving, hiding or detaching keeps drafts, subscriptions and work;
owner disposal retires them. Duplicate refs/keys/placements and containment cycles
reject atomically. Provisional registrations are permanently invalid on rollback.
Candidate claim buffers clear after every publication attempt. Focus/input APIs
on NodeRef remain explicitly deferred.

Synchronous sources expose revision-tagged `CollectionWindow` values from reactive
bounds, without implementing range access through a full snapshot. Filtered
sources own materialization, revisions and readable-success change batches. Staged
reads follow source/query edits and chained projections; failure/recovery and event
capacity preflight preserve rollback. Projection updates precede row-lifetime
choices, including queries supplied by retained Inputs across siblings/roots.
Private requested results include stable failures. Staged read dependency tracking
invalidates the written source and reverse dependents on every set; unchanged
unrelated inputs do not repeatedly manufacture fresh query exceptions. Disposal,
rollback and description changes clear that cache and its dependency metadata.

`VirtualRows` realizes fixed-height visible rows plus overscan. The headless host
supplies a root default or an individual mounted node's viewport; zero height
realizes nothing. Overlapping keyed rows retain Inputs/drafts; leaving the range
retires their scopes. Compatible source rebinding retains keys, failed sources
retire rows and show Unavailable, and stale revision/bounds windows reject.
Signed-zero and custom value equality survive both range and row maps.

The 20,000-row source regression uses a 10,000-row filtered projection. Five
viewport-only moves perform **zero predicate calls** and fewer than **5,000 value
comparisons** in total; an accidental full-projection equality scan fails the test.
A separate source wrapper forbids both full snapshot methods while rendering
ranges. This establishes the bounded scroll path, not a production timing result.
Source/query updates may scan/rebuild, and existing change-batch construction
retains full synchronous vector-diff costs. No optimized million-row diff, async
paging, variable heights or scroll anchoring is claimed.

The public structural workbench uses a 1,000-row filtered source, retained outlets
and a virtual list. The [host contract](../plans/2026-10-28-interactive-host.md)
specifies the first visible implementation and the matched ScalaFX experiment.
It names actual graphics/text bridge inputs, missing layout/focus/editing/IME/
accessibility pieces and a bounded JDK 25 FFM development spike. Native-first
shipping order remains unchanged. Neither specimen nor contract is a visible UI.

## Verification

Final command exited **0**, no failures or ignores:

```sh
sbt testJVM testNative examples/compile probeWindow/test probeThreads/test \
  workbenchJVM/run workbenchNative/run
```

Java 21.0.12.1, Scala 3.7.4, sbt 1.12.14, ordinary Scala Native **0.5.12**.
The separate pinned-upstream GC experiment and pause gate were not rerun.

| Owned checks | JVM | Native |
|---|---:|---:|
| Existing core | 7 | 7 |
| Existing engine | 145 | 145 |
| RetainedVirtualSuite | 25 | 25 |
| Existing reference/model suites | 29 | 29 |
| VirtualCollectionAgreementSuite | 1 | 1 |
| Workbench including structural specimen | 7 | 7 |
| ABI/mailbox | — | 27 |

**455 owned test executions**, zero failures. Loaded aggregate settings also ran
**108 concurrent-track checks** (graphics 44, core TextApiSuite 2, textUI 2 and
graphicsUI 6 on each platform), giving **563 observed executions**. Those checks
do not qualify that track's isolated native text/Unicode/paragraph components or
its renderer, and its source/build edits are excluded from the checkpoint.

The independent full-state oracle runs **200 × 40 = 8,000 steps per platform**.
It checks projection revisions, visible interval membership, keyed generation/
draft retention and root refusal. Deterministic checks separately cover nested
rollback, provisional lifetimes, staged reads, failures/recovery, event capacity,
source rebind, per-node viewports, stale ranges, hidden work, duplicate placements
and cycles. Existing batch replay tests remain in the earlier event suite; this
oracle is not a new batch/event/renderer model.

Both executable runs print:

```text
search=Result: sirin
imported=6 rows=8
dirty=false channel=Drained
```

The independent source reviewer found and re-reviewed the fixes described in the
[review decision](../design/decisions/2026-10-28-retained-virtual.md), with no
remaining bounded source blocker. Main-session frozen-source hashes verified
after the full run. Raw [final log](c1-structural-20261028/final-matrix.log),
[owned source hashes](c1-structural-20261028/owned-source.sha256), observed
uncommitted build hashes and two pre-fix failure logs are preserved beside this
note. Observed build hashes record the shared checkout; they do not claim those
uncommitted graphics/text modules are part of this commit.

Next: the bounded JVM FFM/window development spike, followed by committed layout,
prepared text and ordered presentation for the visible host. Full Stage 1 review,
Stage 2 interaction, visual approval, accessibility and the ScalaFX usability bar
remain open.
