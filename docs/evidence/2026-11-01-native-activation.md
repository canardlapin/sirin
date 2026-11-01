# Native trampoline activation and mask ownership

Scope: owned worktree `/private/tmp/sirin-workbench-20261031`, parent `31866d1`.
Tracking issue: `bd-01M5Z3K47RQTRDXEY7Y2P8TMJ3`. This is a private Darwin ARM64
Immix investigation. No Sirin kernel, runtime pin, native dependency cache or
qualification threshold changes.

## Method

The prior source review identified a gap between signal-handler exit and the
trampoline's resume branch. The new diagnostic tracks that interval using a
per-mutator activation identifier, active flag and phase. It compares the saved
PC and mask around yield and checks ownership again before loading the resume PC.
A labelled assembly PC interval covers the active-flag clear and register-restore
tail through the final branch. A new redirect counts as overlapping when the
flag is set or its interrupted PC lies in that interval. Linked ARM64 instructions
were inspected for both variants. Diagnostic observations do not repair a nested
activation or replace the original single resume-PC slot.

The observation-only variant and the saved-mask variant have identical
instrumentation. Only the latter restores the interrupted mask before yield,
using the same operation as the frozen October 29 control. Signal-handler work
uses saved-context reads and lock-free atomics. Mask queries and restoration run
in normal context and preserve errno. Output occurs at mutator disposal or the
existing collector warning path. No Scala code runs from a foreign thread.

Five synthetic checks pass for each generated variant. They compile the actual
modified C and ARM64 assembly against a minimal mutator fixture and check:
ordinary activation, an injected second redirect during yield that overwrites the
PC, the restore-tail interval boundary, counter wrap, and a SIGBUS mask mismatch
while preserving blocked SIGUSR2. The positive overlap test confirms that both
owner mismatch and PC overwrite are detected, while the original overwritten PC
is still returned. These tests demonstrate detector behavior, not the reachability
of nested redirects in a running Scala Native program.

The protocol was frozen before execution: original, diagnostic-original,
saved-mask control, three saved-mask diagnostic trials, original. Each uses
`60 1 2048 true`, a 128M heap cap, the original 15-second GC synchronization
timeout and a 75-second external timeout. The runner blocks SIGUSR2 before exec
and immediately restores its own mask; every child and its threads inherit that
unrelated bit. No SIGUSR2 signal is delivered. All cells use this intervention.
This is a shared-host causal comparison, not a latency qualification.

The original binary matches its historical SHA-256. The old saved-mask control
executable was missing at its historical path, so its JAR was recreated byte for
byte from frozen resources and a new control binary was linked. Its new binary
hash is recorded separately. All three new builds use byte-identical frozen Scala
source, build files and the pinned compiler. The two generated diagnostic JARs
also replay byte for byte. The generator rejects changed source JARs, unexpected
anchors and existing output paths.

## Results

| Cell | Outcome | Elapsed | Observed completed activations | Entry-mask mismatches |
|---|---|---:|---:|---:|
| original-before | GC synchronization abort | 23.06 s | Not sampled at disposal | Not instrumented |
| diagnostic-original | GC synchronization abort | 38.85 s | Not sampled at disposal | See failure snapshot |
| saved-mask-control | Completed | 60.50 s | Not sampled at disposal | Not instrumented |
| restore-diagnostic-1 | Completed | 60.41 s | 2656 | 2 |
| restore-diagnostic-2 | Completed | 60.08 s | 2747 | 2 |
| restore-diagnostic-3 | Completed | 60.15 s | 2877 | 2 |
| original-after | GC synchronization abort | 54.71 s | Not sampled at disposal | Not instrumented |

All seven predeclared runs were retained; none hit its external timeout. Both
uninstrumented original controls and the observation-only diagnostic reproduced
the GC synchronization abort. The uninstrumented saved-mask control and all three
instrumented saved-mask trials completed.

At the diagnostic-original warning snapshot, one mutator had completed 965
activations with matching entry/return/load counts, no overlap, no owner/PC
mismatch and one full-mask mismatch. Its saved mask was `0x40040000`; entry,
post-entry and post-yield masks were `0x40040200`. The extra `0x200` is the SIGBUS
bit. Blocked SIGUSR2 remained present. Another mutator was still inside yield
with 18 entries and 17 returns. This strengthens the mask-mismatch evidence
without claiming a newly observed nested redirect or establishing why Darwin
produced the extra blocked bit.

The three instrumented saved-mask trials observed 2,656, 2,747 and 2,877 completed
activations in retiring mutators. Each observed two entry-mask mismatches and
zero mismatches after restoration or yield. All observed ownership, PC, overlap,
tail-overlap, wrap, query-error and missing-SIGUSR2 counters were zero. Each trial
emitted two disposal records, one with no redirected activations. The main
mutator was not included in these final disposal records.

After the short trials, a separate prospective protocol specified one 120-second
four-allocator survivor follow-up with a 135-second external timeout, conditional
on all three short diagnostic trials passing their checks. It uses the same
instrumented saved-mask binary, inherited SIGUSR2 bit, 128M cap and 15-second GC
timeout. It is an adaptive follow-up, not an additional predeclared primary cell.
It completed in 120.07 seconds with 60,925 collections, 1,501,732,535 allocator
iterations and 4,309,816 retained payload bytes. Every post-startup heap sample was
35,790,848 bytes. Five retiring mutators recorded 24,846 completed activations
with matching entry/return/load counts and no overlap, ownership/PC mismatch,
mask mismatch, query error or missing SIGUSR2. The main mutator again has no final
disposal snapshot. This extends the observed population to four allocators plus
the ticker without proving arbitrary nesting safety or production latency.

## Limits and next gate

The detector changes code layout and adds atomics, mask queries and a native
call; printing can also affect execution. Zero observed overlap is not proof that
nesting is impossible. Stuck-thread records cover then-registered mutators;
disposal records cover the retiring mutator and do not necessarily include the
main mutator. They are not a globally atomic snapshot. An active mutator parked
inside yield is expected and is not itself evidence of overlap.

The original failures remain authoritative. The unchanged idle-host 600-second
event/50 ms gate is not run in this experiment. P0.6, independent runtime review,
the Native application provider and the owner delivery-order decision remain
open. The candidate is not adopted. No upstream message is sent.

## Reproduction and retained artifacts

The generator, synthetic checks and serial runner are documented in
[the activation diagnostic guide](../../probes/gc-repro/runtime/ACTIVATION.md).
Raw artifacts are retained at `/private/tmp/sirin-native-activation-20261101`:
protocol, source/JAR/binary receipts, synthetic checks, build logs, linked
assembly, every run and the original failures. Initial ambiguous-anchor refusal,
the header-prototype review correction and the absent historical control are
recorded rather than omitted. Large binaries, JARs and full disassembly remain
outside Git.

The 119-file artifact manifest has SHA-256
`d60f5cd1b5f37af79fef32d0a5e38dbc7b7efe9d5ef6da5fa52acd2799468c5a`. It includes retained failures, source
snapshots, diagnostic and control JARs/binaries, and the exact run receipts. The
existing original binary is identified by its historical hash in `inputs.json`.

Required `sbt testJVM` and `sbt testNative` runs passed 470 tests on each
platform using the pinned JDK 25. Desktop tests were not repeated because this
continuation changes only private standalone probe tools and evidence. The two
synthetic C/assembly harnesses passed all ten check groups, both diagnostic JARs
replayed byte exactly, and generator refusal checks passed. The shared checkout's
tracked diff remains unchanged at SHA-256
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
