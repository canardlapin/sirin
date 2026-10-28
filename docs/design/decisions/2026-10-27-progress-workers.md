# Bounded progress and real workers: source review

Scope: C1.6a's Scala-created worker adapter, transactional progress queues,
independent queue oracle, and public-API workbench authoring specimen. Based on
`a821946`. Kernel semantics were recorded in VISION §5.4 before implementation.
Concurrent graphics/text/Unicode work is outside this decision.

A continuing independent reviewer read the implementation in fresh context,
without edits or builds. The main session addressed these findings:

1. **Atomic lease.** Coalescing between availability inspection and head selection
   must select the latest committed head atomically. Availability is only a hint;
   a changed head is not a receiver failure. Added forced-interleaving regression.
2. **Worker guard.** Caught wrong-thread UI access must still fail Work. Record the
   violation in the worker-local phase; do not mutate owner transaction state.
3. **Retention.** UI offers must not put a transaction/host into the producer
   transport. Reservation identity now uses a detached token, and commit clears
   the token and replacement history. A regression inspects that invariant.
4. **Rollback wakeup.** Releasing a failed receiver's lease restores coalescing
   capacity and must wake emitters. A monitor-ordered latch test blocks an emitter
   at the leased tail and proves refusal wakes it without another publication,
   cancellation or drain.
5. **Editor validation.** Ordinary command refusal must preserve the caller's draft
   and visible feedback. The workbench keeps the editor open, records the refusal
   and leaves the row unchanged. Invalid-title regression added.
6. **Competent baseline.** The ScalaFX comparison removes canceled queued searches
   and validates duplicate row IDs before refresh. It also guards stale results,
   failures and import generations. Runtime comparison remains unqualified.

The reviewer checked cancellation registration/notification ordering, per-reservation
rollback, absence of callbacks under transport locks, physical versus logical
capacity, nonblocking shutdown and bounded awaits, terminal/progress ordering and
bounded receiver failure handling. Final verdict: no remaining source blocker,
conditional on final JVM/Native verification. The slot-history oracle meaningfully
checks queue/accounting independently; threaded tests address races beyond its
sequential interleavings. Compilation alone establishes neither ScalaFX behavioral
equivalence nor interactive superiority. Foreign C producers and graphical
qualification remain outside this review.
