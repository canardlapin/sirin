# Standalone Scala Native allocation reproducer

This independent sbt build uses only Scala Native and JDK APIs. Copy this
folder outside Sirin to reproduce without SDL, wgpu, Sirin core, a mailbox,
custom C code, or the parent build. Pins: Scala 3.7.4, Scala Native 0.5.12,
sbt 1.12.14. The default build is release-fast with Immix and multithreading.

```sh
cd probes/gc-repro
sbt nativeLink
GC_MAXIMUM_HEAP_SIZE=128M python3 run.py --out target/run-w4 --timeout 75 -- \
  target/scala-3.7.4/gc-repro 60 4 2048 true
```

Arguments: seconds, worker count, survivor slots per worker (default 2048),
and whether to run a 1 ms sleeping ticker (default true). Each allocator
retains at most `slots` byte arrays of 16–1039 bytes and creates temporary
lists and strings. The arrays remain reachable through each Worker and
are read and checked after join, preventing optimized dead-store removal.
The main thread sleeps 100 ms between deadline checks. Samples every 5 s
report GC heap capacity, collection count, and completed allocations.
`heap_bytes` is the runtime heap capacity counter, **not** live bytes or RSS.
The final result reports the actual retained payload size.

Use 1, 2, and 4 workers with the same binary and runtime environment.
Use `0` survivor slots or `false` for the ticker as diagnostic controls.
Successful completion does not establish bounded long-term growth or the
50 ms UI pause criterion; this reproducer does not measure UI latency.

`run.py` uses an external timeout independent of the Scala runtime. Every
run gets a new output directory with stdout, stderr, exit status, timeout
status, binary hash, GC environment settings, elapsed time, and system load.
A nonzero exit is preserved; the runner does not convert crashes into passes.
Run cases serially and keep the host idle when measuring performance.

Build variants (save each executable before cleaning):

```sh
sbt -Drepro.mode=debug clean nativeLink
sbt -Drepro.gc=commix clean nativeLink
SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0 sbt clean nativeLink
```

The last command disables trap-based yieldpoints **during linking**. Merely
setting the variable when running an existing binary does not change its
code. A clean build avoids stale incremental artifacts. Verify trap presence
with `nm target/scala-3.7.4/gc-repro | rg yieldpoint_trap`.

These are diagnostic variants, not a change to Sirin's runtime policy.

## Isolated runtime experiments

`runtime/variant.py` copies the pinned nativelib JAR into a new private JAR,
patching only embedded C resources. It never modifies the dependency cache.
It refuses existing output JARs and records a unified patch plus input/output
SHA-256 hashes. These variants are experiments, not a complete upstream fix.

```sh
# From this directory; set SOURCE to the cached 0.5.12 nativelib JAR.
python3 runtime/variant.py "$SOURCE" /absolute/path/runtime-diag.jar
python3 runtime/variant.py "$SOURCE" /absolute/path/runtime-retry.jar \
  --retry-collection --no-diagnostics
python3 runtime/variant.py "$SOURCE" /absolute/path/runtime-alloc-retry.jar \
  --retry-allocation --no-diagnostics
SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0 sbt \
  -Drepro.runtimeJar=/absolute/path/runtime-alloc-retry.jar clean nativeLink
```

The diagnostic variant counts collection epochs and logs allocator misses.
The collection-retry variant forces the caller to acquire collection ownership;
it is a causal control and can perform redundant collections. The narrower
allocation-retry variant reports a lost collection race to the allocator,
which retries instead of interpreting it as post-collection exhaustion.
Neither patch implements upstream's allocation reservations or complete
recovery protocol. Default builds still use the published runtime.

See `../../docs/evidence/stage0/P0.6-allocator-race-macos.md` for the bounded
results and upstream PR #5128, merged after the pinned release.

## Complete pinned upstream toolchain

`runtime/upstream-pin.json` records the evaluated upstream merge commit and
its commit-tagged artifact version. This evaluates the entire compiler/plugin/
library stack, with no `repro.runtimeJar` substitution. It does not change
Sirin's default 0.5.12 pin.

The checked-out source and built artifacts are local in
`../../target/upstream-evaluation/`. To rebuild in a fresh evaluation directory:

```sh
# Use an absolute path, outside any directory you plan to clean.
sirin_eval=/absolute/path/to/upstream-evaluation
mkdir -p "$sirin_eval/scala-native"
git -C "$sirin_eval/scala-native" init
git -C "$sirin_eval/scala-native" remote add origin https://github.com/scala-native/scala-native.git
git -C "$sirin_eval/scala-native" fetch --depth 1 origin fa4a9e6ac9dab73ab4d476d6cc8c0cdc422c25f7
git -C "$sirin_eval/scala-native" checkout --detach FETCH_HEAD
cd "$sirin_eval/scala-native"
CI=1 sbt -J-XX:ActiveProcessorCount=4 -Dsbt.ivy.home="$sirin_eval/ivy" \
  'publish-local-dev 3.7.4'
```

`CI=1` selects upstream's commit/date version, exactly
`0.5.13-20261026-fa4a9e6-SNAPSHOT`, instead of a mutable generic snapshot name.
The isolated Ivy home keeps these local artifacts separate from other builds.
Copy the reproducer's sources/build files into an evaluation directory, change
only its plugin version to that value, and run:

```sh
sbt -J-XX:ActiveProcessorCount=4 -Dsbt.ivy.home="$sirin_eval/ivy" nativeLink
GC_MAXIMUM_HEAP_SIZE=128M SCALANATIVE_GC_SYNC_TIMEOUT_MS=15000 \
  python3 run.py --out target/upstream-w4 --timeout 135 -- \
  target/scala-3.7.4/gc-repro 120 4 2048 true
```

Unset build-time yieldpoint overrides for the default trap-based comparison.
The same plugin replacement and Ivy setting can be applied to an isolated
Sirin checkout; reuse its pinned `native/vendor` artifacts and run the existing
JVM/Native/ABI/mailbox tests before `probeThreads/nativeLink`. See
`../../docs/evidence/stage0/P0.6-upstream-pinned-macos.md` for exact evaluated
configuration, results, and artifact hashes.

## Idle-host preflight

Before a pause-qualification run on macOS, record readiness:

```sh
python3 probes/gc-repro/check-idle.py --out target/idle-preflight-NEW
```

Run this command from the Sirin root. It takes six samples roughly ten seconds
apart, retaining both raw `top` readings per sample and using the second CPU
reading. Exit 0 means every sample had at least 80% idle CPU and one-minute
load no greater than half the logical CPU count. Exit 2 means busy; exit 1
means measurement failed. Existing output directories are refused.

These conservative thresholds are a **preflight**, not a new P0.6 acceptance
criterion. The ten-minute probe must still pass its original assertions, and
a preflight does not prove absence of contention later in the run. Preserve
host-load observations and arrange a quiet window; do not stop unrelated jobs.
