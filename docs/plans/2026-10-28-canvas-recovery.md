# Retained canvas and recovery — 2026-10-28

Continue `1ef2032` on `codex/canvas-recovery-20261028`. Kernel semantics and
application Work/command/focus policies are unchanged. The previous evidence
and refused quiet qualification stay immutable and separate.

Preparation owns a bounded CPU cache (256 entries, 4MiB declared source/point
payload). Keys use immutable path identity, stroke style, affine linear part,
tolerance and subdivision depth. Translation reuses device-relative geometry;
scale/shear/precision changes rebuild. Cache hits still charge the original
point/temporary-outline budgets. Completed entries may survive cancellation or
device replacement; partial geometry and partial frames never publish.

A worker-owned control counts work and checks cancellation during inspection,
path subdivision, stroke expansion and tessellation. An exhausted control
refuses the request, rather than silently returning partial output. Native font
and GPU calls remain bounded synchronous externs; cancellation occurs between
them. This is cooperative cancellation, not a hard real-time preemption promise.

Device callbacks record independently owned native health state and wake the
native event queue, without Scala upcalls. A lost device invalidates render
operations and old frame presentation. Main quiesces the render worker, retires
current frames, replaces the shared device/instance/surface and rerenders the
latest immutable request. Replacement failure fails closed. Repeated losses
without a successful presentation have a finite retry bound.

Add an interactive Drawing-based canvas driver for pan, zoom, edit and explicit
device-destruction controls. It uses the existing shader/tile/frame and worker
ownership model, with no second graphics vocabulary or application scheduler.

Freeze before execution: original four scales 1/1.25/1.5/2 and <=2-byte pixel
budget against the reference, with the existing independently identified exact
boundary exclusion policy where necessary. A retained 100-path workload edits
one path, pans by integer and fractional offsets, and changes scale. Record cold
and warm preparation, cache/work counters, cancellation latency, frame latency
and GPU transfers. Require no stroke rebuild on translation, selective rebuild
on an edit, correct rebuild on scale change, zero partial/stale publication,
and bounded caches over 100 edits. Cancellation is checked at geometry work
checkpoints and before native operations. Device destruction must invoke the
real native callback, reject old generations, rebuild equivalent pixels, and
leave zero owners/frames/health records after close.

Run required JVM/Native aliases, Native GPU and desktop checks, plus actual
packaged pan/zoom/edit/recovery controls. Record failures and source hashes.
Measured diagnostics do not close quiet-run or browser-parity gates.

Completed bounded implementation and controls: see
[the evidence receipt](../evidence/canvas-recovery-20261028/README.md).
The 100-path diagnostic records remaining pan/zoom raster cost; it is not a
50ms canvas qualification. A native destroyed-queue abort limits the recovery
claim to the tested controlled path. Spontaneous hardware-loss races, quiet
host qualification and broader graphics gates remain open.
