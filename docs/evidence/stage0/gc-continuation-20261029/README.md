# Safepoint continuation receipt — 2026-10-29

See [the report](../P0.6-continuation-20261029.md). The twelve serial trials
retain all successes and failures: six safepoint aborts and six completions.
`summary.json` contains outcomes and denominators. `preflight/` is failed
idle-readiness evidence, not a failed latency experiment or qualification.

- `stall-*`: one-worker SDL-wait probe, zero user events, 40 s requested.
- `standalone-*`: one-worker Scala Native-only allocation/sleep program,
  2,048 survivor slots, 1 ms ticker, 40 s requested.
- `stock`: preserved Scala Native 0.5.12 executable.
- `pinned`: preserved complete fa4a9e6 evaluation executable.

The one native profiler attachment occurred only after an actual ten-second
safepoint warning. Its pre-attach log and command are preserved in the case.
All other attempts are unprofiled. Host load is recorded for every trial;
none of these short correctness controls qualifies the 600 s event/pause gate.

`reproducer/` copies the existing independent sbt sources and pins; it has no
Sirin/SDL dependency. The main repo's original source is unchanged. See the
existing `probes/gc-repro/README.md` for isolated full-upstream builds.
`upstream-report-draft.md` is a local unsent report.

Check frozen files and outcome accounting without rerunning the experiments:

```sh
python3 -B docs/evidence/stage0/gc-continuation-20261029/verify.py
```

Replay the same preserved executables in a fresh output directory:

```sh
python3 -B docs/evidence/stage0/gc-continuation-20261029/run-controls.py \
  --suite standalone --out target/gc-continuation-NEW
```

The replay verifies binary hashes before starting, preserves all three attempts
per runtime, and exits nonzero if any attempt fails. It performs correctness
controls on the recorded host; it does not launch a pause-qualification soak.
The profiler sidecar is retained as `capture-stall.py`; the recorded command
used `target/gc-editor-continuation-20261029/capture-stall.py` from the repo root.
Its directory selection assumes it resides alongside the trial directories.

A separately labeled supplemental control, `conditional-stock-w1-*`, ran after
the required test suites: three of three completed. `summary.json` keeps those
three cases apart from the twelve primary attempts. The replay supports
`--suite conditional`; `--suite all` runs all fifteen cases. The conditionally
linked binary is stock 0.5.12 and is hash-checked before execution. This is a
bounded diagnostic comparison, not an adopted workaround.

Required regression logs are in `required-checks/`: 299 JVM and 299 Native
cases passed. The verifier checks those recorded command outcomes too.
