# Draft: macOS arm64 trap-safepoint stall with one allocator and sleeping ticker

Local draft only; not posted. Reproduced with Scala Native 0.5.12 and a full
source build pinned at fa4a9e6ac9dab73ab4d476d6cc8c0cdc422c25f7,
version 0.5.13-20261026-fa4a9e6-SNAPSHOT. This report concerns those exact
artifacts; it makes no claim about a later upstream revision.

Environment: macOS 15.1.1 (24B91), arm64, 10 logical CPUs, shared host.
Scala 3.7.4, release-fast, Immix, multithreading enabled, no LTO, default
trap-based safepoints. Executable hashes match preserved build receipts.

The complete independent build is copied under `reproducer/`. It allocates
bounded byte-array survivors plus temporary lists/strings in one worker.
A ticker sleeps 1 ms, and main sleeps 100 ms between deadline checks. There
are no user native callbacks, custom C implementation, SDL, Sirin or mailbox.
The only declared externs are runtime GC counters.

```sh
cd reproducer
sbt nativeLink
GC_MAXIMUM_HEAP_SIZE=128M SCALANATIVE_GC_SYNC_TIMEOUT_MS=15000 \
  ./target/scala-3.7.4/gc-repro 40 1 2048 true
```

Use an independent 55 s external timeout and a new directory for each attempt.
Three serial attempts/runtime produced one SIGABRT on stock and two on the
pinned evaluation; every other trial completed. These are observed counts,
not failure-rate estimates. Both runtimes also stalled in separate one-worker
SDL-wait controls. All 12 logs and return codes are retained.

Standalone failures print `Waiting for 1 thread(s) to reach safepoint` with
`state=Managed, alive=yes`, then abort after the configured 15 s GC timeout.
No external timeout was needed. Heap capacity after startup remains
8,947,712 bytes in the sampled output, so this is not the earlier reproduced
concurrent-allocation growth failure.

A native sample attached after the ten-second warning in a pinned standalone
failure shows main in Thread.sleepNanos -> Synchronizer_yield -> sigwait,
the allocator/collector in Synchronizer_acquireGeneration, and the ticker in
PosixThread.sleepInterruptible at +336. The frozen ARM64 disassembly maps
that ticker address to the trap-poll load `ldr xzr, [x8]`. The pre-attach
warning, full sample and symbolized disassembly are retained. The other five
failed trials were unprofiled, so instrumentation does not explain their
initial stalls. A lost signal, compiler defect or admission race is not yet
established.

Question for runtime investigation: why does this ticker remain a managed,
non-safepoint thread while stopped at its poll load, with other threads
waiting for GC? A separate preserved stock conditional-yieldpoint binary completed three of
three identical 40-second standalone controls, with no safepoint warnings.
This is consistent with the trap path being relevant, not proof of a fix.
Inspect trap handling and thread admission without changing application
blocking annotations speculatively.
