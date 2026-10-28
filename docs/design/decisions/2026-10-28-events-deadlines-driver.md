# Events, deadlines and bounded driver review

Base: `606f45d`. Scope: transactional scoped events and keyed-source change
batches, monotonic/manual debounce, fair unified owner driving, parking, counters
and bounded numeric causal diagnostics. VISION §5.3 was extended before code.
This is a bounded headless gate, not the full Stage 1 exit.

A continuing independent reviewer performed source review only, with no builds
or edits. The main session addressed these findings and re-reviewed the fixes:

1. **Completion starvation.** Repeated drain(1) could repeatedly select a failed
   bookkeeping ticket. The driver now rotates completed IDs and excludes IDs
   already attempted this turn. The regression proves an unrelated command
   completes while the failed ticket remains available for explicit later retry.
2. **Deadline retention.** A retained dead debounce Signal could keep the pending
   value through its binding closure. Committed disposal and provisional rollback
   now clear previous/candidate/scheduled metadata. An internal count test retains
   the binding and verifies zero retained inputs, beyond merely checking removal
   from the live deadline list.
3. **Clock ceiling overflow.** Adding 999999 before nanosecond-to-millisecond
   division can overflow. Division plus a remainder bit now handles a representable
   Long.MaxValue deadline without turning a bounded park into a zero wait.
4. **Exact rollback control.** Reading a provisional debounce before initial
   publication fails before its intended refusal. The test now avoids that read
   and asserts the exact Refused receipt, proving the intended path executes.

The main implementation also corrected initial collection-delta construction and
replaced obsolete deferred-event assertions with executable delivery checks.
WorkBench's wait condition now includes its typing quiet period; initial completed
search results must not prematurely finish the current-query trace.

Final bounded source verdict: **no remaining blocker**, conditional on main-session
frozen-source JVM/Native checks. Event routing and collection preflight preserve
rollback, overflow and lifetime semantics. Traces distinguish dependency cycles,
transaction pass exhaustion and driver-turn exhaustion without retaining application
objects. The pure 250×50 event/deadline oracle is independent; randomized mixed
progress/completion/effect interleavings are not claimed. Those classes have
separate existing models plus deterministic driver integration controls. Public
batch replay and minimum-move checks strengthen keyed collection qualification.

Filtered collection streams, rendered-frame behavior, full graph-inspector tooling,
interactive ScalaFX equivalence and full Stage 1 acceptance remain outside this
verdict. The initial Native run covered fewer controls; the final receipt must use
the complete run with all 22 EventDeadlineSuite checks on both platforms.
