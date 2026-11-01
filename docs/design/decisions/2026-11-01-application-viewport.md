# Application viewport and exported texture budgets

The original development host deliberately stops at 1280 by 800 physical pixels.
On a 2x display this leaves only 640 by 400 logical content pixels. The packaged
persistent workbench reaches that cap when zoomed or dragged larger. A usable
application needs a separate viewport profile, coordinated across native window
constraints, tile cache admission and complete-frame transport.

The initial 5120-wide proposal failed the maximum-size regression because the
shared graphics target has an existing 4096-pixel axis limit. Preserve that
shared contract and align the application profile to it; the failed run remains
evidence.

Application.run selects a profile allowing at most 4096 by 3200 physical pixels
(13,107,200 pixels). This covers a 2048 by 1600 logical workspace at 2x, including
portrait orientations that fit those explicit dimensions; arbitrary display sizes
are not promised. The bounded development host retains 1280 by 800. Native scale
changes recompute the logical constraint from the selected physical ceiling.

The application cache allows 2048 active tiles and 4096 adaptive visits. A regular
128-pixel grid needs 800 tiles at the maximum viewport, leaving headroom for
content-driven splitting. Completed variants occupy at most the current viewport
plus 1,048,576 spare pixels, and 2304 entries (2048 active plus 256 spare).
Partition metadata is bounded at 2048. Default caches keep their existing 256
entries, 256 active tiles, 512 visits and 1,048,576 pixels. Per-tile program,
geometry, image, glyph and preparation limits remain unchanged. Refusal is still
explicit when content cannot fit; there is no downsampling or fallback bypass.

Complete native packets admit up to 2048 tiles and the application dimensions,
while preserving exact coverage, one-device ownership and the per-device three /
process-wide 48 packet inventories. The native ABI does not change. Packet arrays
are fixed and bounded (roughly 32 KiB per packet on this platform).

Exported immutable output textures now have a per-device/generation limit of
161,480,704 bytes: three maximum viewport footprints plus 1,048,576 spare RGBA
pixels. The process limit is twice that, 322,961,408 bytes. These are admission
ceilings, not preallocations or a promise of 16 simultaneous maximum-size windows.
They include textures kept alive by displayed, obsolete and retiring frames.
The counters count each export once, not each reference. Atomic reservation rolls
back the device count on process refusal and both counts on allocation failure;
retirement releases accounting before the last device reference. Other batch
buffers retain their existing independent bounds; these figures are not a cap
on total process or driver memory. Default development workloads retain their
cache limits, but all exported textures share this new native accounting policy.

Rejected alternatives: raising only the window cap immediately violates cache
budgets; raising all cache limits globally erases the bounded probe profile;
scaling a small framebuffer blurs text; unbounded allocation loses refusal and
retirement guarantees. Restricting the application to a 640 by 400 logical window
does not satisfy the persistent workbench goal.

Acceptance includes a negative control on the old cap, large retained frames
through replacement and resize, independent pixel expectations, cache/default
refusals, atomic output-budget boundary/rollback/concurrency checks, zero resource
counts after retirement, shared JVM/Native suites and Native batch checks, and
real packaged resize/edit/save. Existing performance, real trackpad, IME,
VoiceOver, P0.6 and Native provider gates remain separate. No kernel, command,
focus, scheduler or Work semantics change.
