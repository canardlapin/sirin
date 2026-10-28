# Responsive keyboard-operable workbench

Authorized 2026-10-28. Implement in isolated checkout; preserve the graphics
track. VISION is authority. Exit: packaged macOS launch, Tab and reverse Tab,
Enter/Space and pointer command activation, wheel/Page virtual scrolling, focused
retained-panel move, actual resize and clean close. Editing/IME/AX follow.

1. Freeze the combined graphics/host inputs. Extend only the shared raster-cache
   seam needed by the host. Package a distinct app and exercise native startup
   timeout/late-completion controls and real displayed output.
2. Keep render services alive on one worker. Bound text/font/glyph/tile caches,
   preserve generation checks and native inventories, retire deterministically.
   Record cold frame separately. Freeze warm targets before measurements:
   focus/button p95 <=50ms and p99 <=100ms; virtual scrolling p95 <=100ms and
   p99 <=200ms. Use the fixed 100-record workbench at 1200x720 physical pixels,
   a warmed process, at least100 samples/class, raw input-to-present timings;
   report machine/load, misses/max and any failed target. These are acceptance
   targets for this development fixture, not broad product performance claims.
3. Add stable mounted controls and refs, owner-held bindings, presented-epoch hit
   routing, focus/traversal, shared action/command dispatch and scroll inputs.
   Revalidate current identity/enablement, test rollback/disposal/stale targets,
   pressed cancellation and retained focus. Record semantics without claiming
   native accessibility integration.

Required validation: full JVM/Native suites and authoring corpus, desktop ABI/
resource/cache/image equivalence tests, native event/main-thread scenarios, OS
screenshot/resize evidence and frozen benchmark receipts. Land only an owned
integration checkpoint after reconciling shared source; do not stage unrelated
concurrent changes. Graphics qualification, GC pause and Native UI launch remain
separate existing gates.

Measurement clarification after the initial misses: the executable now performs
20 action and20 scroll turns as a fixed process warm-up before measuring the
unchanged100 samples/class. Every warm-up observation is retained alongside the
measured rows, with an explicit phase column. The earlier no-warm-up runs and
failed targets remain evidence; they are never relabeled as passing warm runs.
The original percentile thresholds and fixture dimensions are unchanged.
