# Unsent successor: Darwin Immix saved-mask restoration control

This succeeds the negative SA_NODEFER control in 970cd41. No report has been
posted upstream, no dependency cache changed and no runtime default adopted.
Host: macOS 15.1.1 build 24B91,arm64. Full Scala Native toolchain pinned to
fa4a9e6ac9dab73ab4d476d6cc8c0cdc422c25f7,
0.5.13-20261026-fa4a9e6-SNAPSHOT; Scala 3.7.4,release-fast,Immix,multithreading.
These are explicit historical inputs, not claims about latest upstream state.

## New observation

The private phase diagnostic extends the previous query-only mask experiment.
It records lock-free last observations at eight hooks: handler entry,saved
ucontext,trampoline entry,yield return,resume helper,admission entry/exit and
thread registration. The handler performs no allocation,printing or managed
callback; signal-mask queries have NULL new-mask pointers. Assembly and signal
handler flags are unchanged. Counters at recognized handler exit count reaching
that exit hook,not a direct kernel sigreturn observation. Snapshots are not
atomically coherent across hooks and instrumentation affects scheduling.

In the frozen three 60-second diagnostic attempts,one completes and two abort
on the unchanged15s GC synchronization timeout. Both surrounding unmodified
pinned controls abort. No external 75s deadline fires. After a warning,native
samples again show main yielding/sigwait,allocator acquiring the collection
generation,and ticker in sleepInterruptible; attachment metadata is retained.

Both failures have mask sequence `5,4,5,5,5,5,5,4`,where BUS=1,SEGV=2,CONT=4.
Registration and saved ucontext have SIGBUS unblocked. Handler entry and all
normal-context trampoline/resume/admission hooks have it blocked. The first
five hook counters match 1698 in one failure and 913 in the other; recognized
handler exits match those values,active=0,nested=0. These are last-observed
values. They narrow the earlier historical admission-mask lead to an observed
difference between saved context and trampoline execution.

Apple describes `uc_sigmask` as the blocked-signal set in its
[ucontext manual](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man3/ucontext.3.html),
and documents NULL-set queries as non-mutating in its
[sigprocmask manual](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/sigprocmask.2.html).
The observations do not identify the exact Darwin delivery/return mechanism.

## Frozen causal successor

Start from the original pinned nativelib JAR,not the instrumented diagnostic.
`runtime/saved-mask-control.patch` changes only Immix/MutatorThread.h and
shared/SafepointPollTrampoline.c. On Apple+Immix it copies the full uc_sigmask
in prepare_redirect, then restores that mask in the normal-context run_yield
helper before Synchronizer_yield. Failure to restore aborts; there is no hidden
fallback. No handler flags,assembly,Scala ownership or Sirin scheduling changed.
The private JAR and executable hashes are frozen; all original files remain.

The protocol was frozen before execution: original pinned baseline, three
saved-mask attempts,original pinned baseline. Each has argv60 1 2048 true,
GC_MAXIMUM_HEAP_SIZE128M,SCALANATIVE_GC_SYNC_TIMEOUT_MS15000 and 75s independent
external deadline. Both originals abort; all three candidates complete with
survivor assertions. No external timeout occurs. This supports the restoration
as a causal remedy for the observed bounded standalone stall; it does not
establish universal progress or a failure probability.

A separate 120s four-worker2048-slot candidate check completes39,523 collections
and 973,246,353 allocator iterations. All surviving array endpoints validate;
live payload=4,351,848bytes. Sampled runtime heap capacity stays 35,790,848bytes
after startup. This counter is not RSS or live heap and establishes no unbounded
long-term growth claim. The earlier failed diagnostic compilation (missing
signal.h declaration) is preserved as a build failure,not an allocation trial.

## Reproduce and remaining validation

Use unchanged standalone source `probes/gc-repro` and the pinned plugin. Fresh
builds and exact commands are in builds/. `phase-diagnostic.py` generates the
query-only successor from the previous frozen mask JAR. `replay-runtime.py`
reconstructs all three private JARs byte for byte from hash-checked inputs and
frozen changed resources; the original dependency cache is never patched.
Run fresh trials using the existing run.py with60 1 2048 true (75s external)
or120 4 2048 true (135s external),128M cap and 15s GC synchronization deadline.

An original event-probe build from1fc105e links the same private JAR with traps
enabled and passes its31 compatibility checks. It is a prepared candidate,not
a long-event pass. The original 600-second event/ticker 50ms acceptance still
requires the unchanged idle preflight. No platform/collector generalization,
nested-fault correctness,shipping acceptance or production adoption follows
from these short controls. Mask ownership across nested/asynchronous signals
and other collectors/platforms still needs upstream review and independent
confirmation. The strict gate's outcome is recorded separately in this receipt.
