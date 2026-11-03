# Alpha integration and acceptance priorities — November 2, 2026

The owner's project review changes the order of work. The selected delivery path
is macOS/JVM. Further macOS Native latency instrumentation and measurements are
parked after the completed fixed comparison at `8b00b89`; unresolved Native gates
remain open. This supersedes any earlier suggestion to start another late-mutator
or scheduler-instrumentation tranche immediately. No overnight launchd job or
upstream report publication is scheduled by this plan.

1. Preserve temporary raw evidence, generated inputs and private toolchains in
   durable storage, with hashes and a Git bundle. Retain original paths/receipts
   as provenance and publish a relocation map; do not rewrite old measurements.
2. Integrate the product checkpoint and JVM-first delivery decision. The branch
   `jvm-alpha-integration-20261102` starts at `1d3f667`: the 28-commit workbench
   checkpoint `c049658` plus that decision and its frozen qualification tools.
   Earlier Native evidence remains in history. The four later shared-host latency
   follow-ups through `8b00b89` and parser correction `0d1943e` stay on the Native branch. This is an ancestry
   split, not a claim that every earlier commit is product-only.
3. Give the Linux Native reconciliation ClassCastException the next engineering
   slot (`bd-01M65371ESG6B9B7S82RNE1R1W`). Local historical/container passes and
   the new captured-error guard are evidence, not a reproduction or repair of the
   original CI failure. Run the guarded tests on the real Linux CI environment
   after landing, retaining the exact seed, original program, stack and failures.
   Do not suppress ClassCastException or weaken the independent model.
4. Organize the actual alpha acceptance sessions: VoiceOver, IME, trackpad and
   pointer drag; owner visual/display-scale review; visible matched ScalaFX run;
   inspector and independent starter in real windows; delayed foreground
   presentation after background launch. Reuse the prepared isolated documents
   and exact package identities. Automated checks do not close these gates.

All Alpha1–6 remain open until their own acceptance criteria are met. The
shared-programming-model claim depends on JVM and Native laws, including Linux
failure visibility, even while initial application delivery is JVM first.
Kernel policies and production dependencies are unchanged. Main/origin state,
local integration validation and human acceptance must be reported separately.

Native remains experimental. Its original 50 ms limit, trap-defect review,
reviewed runtime choice and application-provider qualification remain required
for Native delivery. The latest diagnostic's region 0 is ambiguous; one early
sample cannot identify the last lagging thread, and two SDL region markers are
not complete foreign-call coverage. A future explicitly selected diagnostic may
add Mach thread-state/CPU observations and repeated samples, but is not the next
alpha task. A run-state sample alone would still not prove scheduling history.


The supported SDL push `@blocking` correction is carried forward separately in
`f757b35`, with its VISION entry, to avoid restoring the old boundary mismatch.
It adopts no runtime policy. Durable artifact relocation and validation receipts
are recorded in [handoff evidence](../evidence/2026-11-02-durable-product-handoff.md).

## Review-preparation checkpoint

Published `6a13aea` passes all nine CI jobs. Its Linux job passes both guarded
reconciliation suites on JVM and Native, on the same reported image version as
the original failure. The historical exception remains unattributed; no engine
change is justified by these passing runs. Keep its P0 issue open and preserve
the first underlying stack/seed/program if it recurs.

The next human review has four freshly prepared packages and isolated fixtures
in durable storage. The owner chose preparation for later review. Do not close
acceptance issues from signatures, headless tests or prior package observations.
Follow the [review guide](../guides/jvm-acceptance.md); current scope and artifact
hashes are in [preparation evidence](../evidence/2026-11-02-jvm-review-bundle.md).
Separate Native-provider work can continue without extending this JVM tranche.
