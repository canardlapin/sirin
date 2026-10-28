# Frozen latency quiet-run attempt — 2026-10-28 20:22 UTC

Owner requested continuation after checkpoint `3fb9b8e`. This was one new
preflight of the unchanged packaged app, not a repeat-until-pass loop. Every
package hash matches the prior recorded diagnostic. No code or targets changed.

| UTC sample | CPU idle | Load1m |
|---|---:|---:|
|20:22:56.938|64.11%|4.46|
|20:23:04.045|65.54%|4.23|
|20:23:11.163|70.55%|4.29|

The fixed gate requires all three idle samples at least85% and load1m at most5
on this10-CPU machine. Load passes; idle fails. The runner exits2, preserves
`status=not_run`, `quiet_preflight_passed=false`, `qualified=false`, and **does not
launch the benchmark**. There are no new latency samples and no new passing
performance claim. The prior diagnostic action p95=52.427833ms remains a failed
50ms result. Its scroll p95=40.456292ms meets100ms.

[Invocation and package hashes](attempt-202254/invocation.json),
[three-sample receipt](attempt-202254/preflight/receipt.json) and raw samples are
preserved together. The earlier [implementation/test evidence](../responsive-latency-20261028/README.md)
is unchanged, including its manifests. No source changes occurred, so the prior
633 Scala cases and six receipt controls are not rerun or reported as fresh here.

A separate later process observation at20:24:09 UTC found a macOS virtual-machine
process at190.8% CPU (about1.9 cores), with aggregate68.5% idle. No active Sirin
benchmark/compiler process was found. A15-day-old Java process was at0% and was
not established as belonging to this candidate. Nothing was stopped. The
[structured process observation](process-observation.json) is transcribed from
the tool response; it is not a saved raw `top` capture and does not establish a
causal explanation for the earlier preflight.

Latency Mote `bd-01M61Y6MTGD458A4MYNW92PG6G` and the responsive parent remain
open. The owner has been asked to choose an idle local host, another identified
quiet runner, or deferral of the latency gate while development continues.
The existing numerical gate is unchanged. Reproduction after making a runner
quiet: `python3 -B desktop/tools/run-latency.py` from the isolated worktree.
