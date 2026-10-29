# Draft: Darwin arm64 Immix trap safepoint stall without SDL/Sirin

Unsent upstream packet; no issue or comment has been posted. Host: macOS 15.1.1
24B91, arm64. Scala 3.7.4, release-fast Immix, multithreading, sbt 1.12.14.
Compare stock Scala Native 0.5.12 with the complete pinned toolchain
`fa4a9e6ac9dab73ab4d476d6cc8c0cdc422c25f7`
(`0.5.13-20261026-fa4a9e6-SNAPSHOT`). The pin is historical and explicit;
this packet makes no claim about the latest upstream release or current PR state.

The standalone source in `../../../probes/gc-repro/` uses Scala Native/JDK only:
allocator threads retain bounded arrays, a ticker sleeps 1ms, and main sleeps 100ms.
It uses no Sirin, SDL, mailbox, custom native code or managed foreign callback.
Survivor endpoints are checked after join. Runtime heap capacity is sampled;
it is not RSS or the live payload.

## Reproduction

Copy `builds/pinned-conditional/` into an independent fresh directory. Its
plugin is pinned; use the isolated Ivy artifacts described by the repository's
`probes/gc-repro/README.md` and `runtime/upstream-pin.json`. For a pinned trap
build, **unset** `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS` and run the recorded sbt
command in a fresh directory. For a separate conditional build, set that
variable to 0 at link time; setting it at execution cannot change linked polls.
Do not overwrite original binaries or clean an earlier experiment generation.

Run each saved executable serially:

```sh
GC_MAXIMUM_HEAP_SIZE=128M SCALANATIVE_GC_SYNC_TIMEOUT_MS=15000 \
  python3 /path/to/probes/gc-repro/run.py --out /new/trial --timeout 75 -- \
  /absolute/path/to/saved-gc-repro 60 1 2048 true
```

Exact argv arrays and environment are frozen in each result.json.
The primary matrix has three 60-second trials per variant, ordered stock-trap,
pinned-trap,stock-conditional,pinned-conditional for each trial number.
All 6 trap attempts abort on the 15-second GC synchronization timeout; all 6
conditional attempts finish with survivor integrity assertions. There are no
external timeouts. The shared host and 12 outcomes are retained; these counts
are not estimated failure probabilities or a performance comparison.

## Diagnostic evidence

Private runtime JARs preserve all original resources except the explicitly
listed native GC files. `runtime/*.patch`, metadata and changed-resource
snapshots are included. `replay-runtime.py` recreates each JAR byte for byte
from its hash-checked input; diagnostic 2 consumes diagnostic 1. Original
artifact caches are unchanged. The handler adds only lock-free atomic stores/
counters (compile-time lock-free requirements); warnings print on the
collector, with no handler logging, allocation or managed callback.

Diagnostic 1 finishes 2/3 attempts and aborts 1/3. The failed warning reports
signals=recognized=redirects=yields=762,phase5,waiting0,stackTop0,Managed.
A native sample was attached **after** the first 10-second warning. Main is in
Thread.sleep -> Synchronizer_yield -> sigwait, allocator is acquiring the
collection generation, and ticker is at sleepInterruptible+336's trap poll.
The last captured fault PC is not the same as the current stalled PC. Handler
entry and redirection happened many times; a hypothesis that the handler never
ran does not explain these counters. Instrumentation perturbs scheduling and
the successful attempts are not evidence of a remedy.

Diagnostic 2 adds only signal-mask queries at admission/yield. It aborts 2/3
and finishes 1/3. Both failed collector snapshots retain `admitBus=1`,
`admitSegv=0`, `yieldBus=1`, with matched counters 2997 and 939. These are the
last observations at those hooks, not a synchronous read of a stalled thread's
current mask. They justify investigating signal-mask restoration across
redirection/admission, without proving it causes this stall.

Apple documents ordinary signal deferral during a handler and the SA_NODEFER
option in its [sigaction manual](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/sigaction.2.html).
A frozen causal control changed **only** Apple SIGBUS installation to add
SA_NODEFER. Its 3/3 attempts still abort; both surrounding original pinned
controls finish. This is a **failed remedy**, not an upstream fix or proof
that all signal-mask mechanisms are excluded. The scope and five outcomes are
retained in `nodefer-protocol.json` and `nodefer-control/`.

## Bounded conclusion and useful next inspection

The stock and explicit pinned trap runtimes can fail without any Sirin/SDL
boundary. Conditional yieldpoints avoid the observed failures in these bounded
controls. Four-worker pinned conditional validation also finishes 120 seconds
with a flat sampled capacity 35,790,848 bytes after startup and 4,350,976 retained
payload bytes. This is neither a long-term progress theorem nor UI latency
qualification. No runtime default has changed.

A next upstream diagnostic should relate saved ucontext signal masks, actual
post-trampoline masks, nested signal entry and the poll/admission generation
without making managed upcalls or allocating in the handler. A mask-correctness
patch needs a fresh causal control and independent survivor/event validation;
SA_NODEFER alone is already a negative control. The original 600-second event
probe with its50ms assertion remains governed by the unchanged idle preflight.
