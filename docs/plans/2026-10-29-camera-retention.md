# Retained GPU camera motion — 2026-10-29

Continue accepted08d9837 in an isolated candidate. Preserve kernel semantics,
earlier receipts and the owner's available-host latency acceptance.

Retain at most two exact-scale compiled scene variants. Geometry and paint live
in canonical device coordinates; a small GPU camera record translates them.
Scale changes select a cached exact-scale variant or rebuild, including glyph
masks and curve tolerance. Never magnify resolved pixels to approximate zoom.
Tessellation intersects actual clips before camera motion, without prematurely
clipping geometry to the initial viewport. Existing edge, pair, point, mesh,
program, texture and frame inventory bounds remain enforced. Unsupported sizes
and camera precision cases rebuild through the existing clipped tiled path;
other preparation failures remain explicit refusals.

Dense admission follow-up: normalize offscreen meshes where their relative range
fits the existing coordinate bound. Canonical f32 relative inputs must fit
tolerance/64 before tessellation; translated copies then share builds rather
than spending the same intersection budget repeatedly. Keep16million pair
admission,64million request work and all output precision checks unchanged.
Preserve the original failed dense receipts and verify the full100-path corpus.

Immutable output snapshots survive later camera submissions, eviction and loss.
Integer-device-pixel pans copy valid overlap and shade exposed coverage; fractional
pans shade the viewport at the exact new sample grid. Same-camera edits copy the
previous output and shade conservative changed-fill bounds, or the whole target
if a bounded dirty region cannot be proved. Cold/novel scales remain separately
measured. Cancellation cannot publish partial output; recovery discards GPU
variants and reopens them on the persistent worker.

Freeze before execution: 100 original retained cubic paths, gradient strokes and
image, 320x180 logical at scales1/1.25/1.5/2. Worker diagnostic retains the previous
184-sample workload for comparison. New warm measurements use20 warmups/class
then100 samples/class for fractional pan and alternating exact zoom1/1.25. Warm
worker p95<=50ms and p99<=100ms per scale/class. Native input-to-successful-present
is measured separately at the actual window scale, with the same20/100 counts
and50/100ms targets. Available-host conditions are recorded; no idle precondition
is reintroduced. No retry-until-green or changes to targets after measurement.

Require original four-scale <=2-byte reference comparisons (original boundary
policy), including integer/fractional pan, zoom, edited fills/strokes, clips,
images, glyphs and isolated opacity; inspect all-byte saved readbacks as well.
Require zero geometry/asset uploads on cached camera motion, finite counters and
two variants through100 edits/scales, selective coverage, snapshot immutability,
cancelled/stale retirement, controlled loss/recovery and zero owners after close.
Run required JVM/Native aliases, Native GPU and desktop suites and package controls.
All failures and measured misses stay in the evidence. This slice does not qualify
spontaneous hardware loss, other platforms or browser parity.

Completed bounded implementation and available-host measurements: see
[the receipt](../evidence/camera-retention-20261029/README.md). Warm worker and
actual input-to-present targets pass; novel scales still rebuild. The original
dense-admission failure and all intermediate controls remain preserved.
