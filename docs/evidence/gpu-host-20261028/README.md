# Retained GPU host candidate — 2026-10-28

Implemented in `codex/retained-gpu-host-20261028`, isolated at
`/private/tmp/sirin-gpu-host-20261028`, from responsive-host `f4acc6c` plus the
newer graphics batch/stroke checkpoint. The shared root and original host
worktree were preserved. [Contract](../../plans/2026-10-28-gpu-host.md),
[consolidation hashes](consolidation.json), [final sources](sources.json).

The window and render worker share the actual batch device and instance. The
main thread owns the surface; the Scala-created worker owns preparation and
cache mutation. Immutable frame references keep completed GPU tiles alive
through eviction, resize and presentation. Normal frames use no CPU RGBA
readback/re-upload. Explicit capture remains a separate readback path.
Unchanged requests bypass preparation, compilation, uploads and raster dispatch.
Focus/press overlays reuse prepared base commands and resolve in painter order.
Changed programs submit in groups of at most four; default Native probe calls
keep their synchronous behavior.

Bounds remain enforced: existing 640x360/16-program/192MiB batch inventory;
256 cache variants and 1,048,576 output pixels; 16MiB exported texture payload
across cache/frame references and three outstanding frame packets. Complete
packets require one device and non-overlapping full-target coverage.

## Pixels and ownership

[PNG/raw corpus](pixels/) and [independent byte comparisons](pixels.json) cover
the reference renderer, exported tiles and the actual presentation shader on an
offscreen BGRA8-sRGB target. All pixels were checked, with no exclusions, on
this integration fixture: clipped gradient, image, shaped text and isolated
opacity. Maximum byte errors at scales 1/1.25/1.5/2 were **0/1/0/0** for both
tiles and presentation. The existing broader graphics corpus keeps its original
boundary exclusion policy; this fixture does not expand its qualification.

Tests exercise cache-hit counters, focus/press pixels at four scales, eviction
and resize with an older displayed frame alive, foreign devices, incomplete or
overlapping frame coverage, frame count limits, pending-group cancellation,
stale/unpolled result retirement and zero-ink scroll invalidation. The real
packaged scenario passed input/focus/retained move, scrolling, bounded resize and
native close. Ten partial-init/late-callback controls passed. Frozen geometry
drift refused with exit1 and zero measured samples. [Final controls and package hashes](packaged-controls-v2.json),
[visual observation](visual-check.txt).

## Latency: diagnostic passes; quiet qualification remains open

The workload and targets were not changed: 600x360 logical/1200x720 physical,
100 records, 20 action and 20 scroll warmups, then 100 samples/class. Action
p95/p99 limits are 50/100ms; scroll limits are 100/200ms.
[Protocol hashes](latency-protocol.json), [all run results](latency-history.json).

| Final diagnostic, 100/class | p50 ms | p95 ms | p99 ms | Maximum ms |
| --- | ---: | ---: | ---: | ---: |
| Action | 16.521 | 16.985 | 22.917 | 30.743 |
| Scroll | 16.556 | 17.060 | 17.637 | 25.263 |

[Final run](latency/20261028T210413.617949Z/invocation.json) exited0; independent
receipt validation verified frozen geometry, sample counts and all percentiles.
Of 100 measured scroll frames, 99 compiled/uploaded nothing. Every normal frame
reported zero readback bytes. Main-entry to first presentation was 893.456ms
for this one run, excluding pre-main JVM startup; it is not a cold percentile.

**This is not a qualified responsiveness result.** The single qualification
attempt at 20:52 UTC refused the machine: idle25.69/29.42/39.33% and load1m
10.57/9.95/9.96 versus fixed85% idle and load<=5. It launched no benchmark,
has no samples and remains `not_run`, `qualified=false`. Both diagnostics are
explicitly unqualified, regardless of their own preflight. No quiet retry was
made. An identified quiet runner must execute the unchanged packaged protocol.

## Failures retained and corrected

- The first all-pixel integration comparison found a 94-byte maximum error at
  an exact image bottom edge at1.25x. The shader now uses the matching half-open
  edge orientation and checks inverse UV admission. The same test and original
  <=2-byte tolerance pass. [Original readbacks](boundary-failure/) and failed
  test logs remain.
- The first real-window launch aborted because an adapter queried a surface
  created on another wgpu instance. Startup now recreates the surface on the
  batch instance before querying/configuring it. [Crash](scenario-1-instance-mismatch.log)
  retained; actual packaged scenarios and close pass.
- The first diagnostic passed action p95 at34.975ms but failed scroll p95 at
  165.090ms. Zero-ink spaces remained in every tile signature, invalidating all
  60 tiles on scroll. Shared mask-based empty-glyph filtering fixed that error;
  a new disjoint-tile regression passes. The failed receipt was preserved and
  the successful diagnostic uses a new package hash.
- An added cancellation test initially used MUnit `intercept`, which excludes
  `InterruptedException`. It now explicitly catches and asserts that exact
  cancellation, like the existing tests. The failing harness log is retained.

## Verification and limits

**735 distinct Scala test cases** passed across required `sbt testJVM` and
`sbt testNative`, font/Unicode/paragraph/render probes and the final25 desktop
tests. Final Native GPU refresh passed33/33. Python receipt tests passed6/6;
13 authoring examples compiled. [Validation summary](validation-summary.json),
[full matrix](validation/full-validation-1.log),
[final desktop](validation/desktop-validation-2.log),
[final Native GPU](validation/final-native-gpu-validation.log).

This qualifies a bounded macOS arm64/M2 Pro integration. It does not qualify
other platforms, device-loss recovery, arbitrary pan/zoom or editing, IME,
accessibility, browser parity, all G-2/G-3/G-4 operations or dense-scene cold
preparation performance. The earlier dense benchmark is preserved as historical
evidence; its performance run was not repeated for this host tranche.

Verify the retained source/artifact hashes, pixel errors and final diagnostic with
`python3 -B docs/evidence/gpu-host-20261028/verify.py`. Integrity verification
preserves the refused qualification and failed diagnostic; it does not turn
those outcomes into passes.
