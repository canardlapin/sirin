# Private trampoline activation diagnostic

These scripts investigate the frozen Darwin ARM64 Immix runtime used in P0.6.
They do not select a runtime for Sirin or qualify its latency. The generator
requires the original pinned nativelib JAR by SHA-256 and refuses an existing
output. It never edits the input JAR or dependency cache.

`activation-diagnostic.py SOURCE OUTPUT` produces an observation-only variant.
Adding `--restore-mask` applies the same normal-context mask restoration as the
October 29 saved-mask control, plus identical diagnostics. Both write the changed
resource patch and a hash receipt beside the private output JAR.

`check-activation.py JAR NEW_DIRECTORY [--restore-mask]` compiles the actual
generated trampoline C and ARM64 assembly against a minimal mutator fixture.
The fixture injects a second redirect during yield and tests the restore-tail
interval, sequence wrap, mask mismatch and preservation of blocked SIGUSR2.
Its synthetic redirects do not demonstrate that nested redirects occur during
real allocation. The fixture deliberately verifies that diagnostics observe,
and do not repair, an overwritten resume-PC slot.

## Observed interval

The redirect helper sets an atomic active flag before overwriting the saved PC.
The yield helper captures an activation identifier and the saved PC/mask in
automatic locals, checks them after yield, and returns the identifier to the
resume-PC loader. The loader checks ownership again. The added native helper
returns the address of the active flag while all caller registers remain saved.

ARM64 assembly clears the flag with a 32-bit release store immediately before
register restoration. A labelled PC interval includes that store and every
instruction through the final branch. A subsequent redirect counts an overlap
if the flag is set **or** its interrupted PC lies in this interval. Thus clearing
the flag does not leave the register-restore tail unobserved. The linked assembly
must be inspected as part of a new experiment.

The signal-context path only reads the saved context and updates fields with
always-lock-free atomics. Signal-mask queries and optional restoration occur in
normal trampoline context and preserve errno. Diagnostic printing occurs during
ordinary mutator disposal or the existing collector warning path. It never runs
in the signal handler. Only telemetry fields are read by that warning path.

Counters are per mutator. A disposal snapshot covers that retiring mutator; a
stuck-thread snapshot covers the then-registered mutators. Neither is a globally
atomic snapshot. An active mutator waiting in yield is normal and is not itself
evidence of overlapping activations. Final disposal records may not include the
main mutator; report the observed population rather than assuming coverage.

## Running controls

Link private copies of the frozen `GcRepro.scala` with the pinned Native compiler
and `-Drepro.runtimeJar=PRIVATE_JAR`, using the existing standalone build recipe.
Preserve build logs, exact source and JAR/binary hashes. Keep original controls
separate from the diagnostic builds.

`run-activation.py PROTOCOL INPUTS NEW_DIRECTORY` runs the predeclared cells
serially. The protocol supplies the cell order, arguments, heap cap, GC timeout
and external timeout. Each inputs entry supplies `binary` and `sha256`. The
runner checks all binary hashes before starting, blocks SIGUSR2 before launching
each child, and restores its own mask immediately afterwards. It delivers no
SIGUSR2 signal. Every child inherits the same unrelated blocked bit, including
the controls. Each result records its outcome, timeout, load and environment.
An expected abort is retained and does not prevent the following declared cell.

Extra atomics, mask queries, a native call, changed code layout and diagnostic
output can affect timing. A zero overlap count in these bounded runs is not a
proof of impossibility. The original idle-host 600-second event/50 ms gate and
runtime adoption decision remain separate.
