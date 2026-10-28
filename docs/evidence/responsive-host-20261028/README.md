# Responsive JVM host candidate — 2026-10-28

The keyboard/pointer workbench implementation is complete and reviewed, and the
packaged macOS app was exercised. **The tranche exit remains open: action p95
misses its frozen50ms target.** This is a local integration candidate on
`codex/responsive-workbench-20261028`, worktree
`/tmp/sirin-responsive-20261028`, based on `ae1817f`. No push or shared-checkout
source replacement occurred. The ongoing G2 batch track is excluded.

## Implemented behavior

- Immutable Button descriptions with transaction actions or existing Command
  bindings; owner-held callbacks and pure captured render trees.
- Presented-geometry pointer routing, matching press/release origin and epoch,
  current identity/enablement checks, bounded Tab/reverse Tab, Enter/Space,
  disabled focus retention and activation refusal, and rollback-safe focus.
- Stable control/scroll identity, transactional NodeRef focus intents/status,
  retained-panel focus, and wheel/Page/arrows/Home/End virtual scrolling.
- One parked render-owner thread with bounded request/result mailboxes, stale
  success/failure rejection, persistent fonts/Unicode/GPU and finite text/mask/
  tile caches. Unchanged base layout/tiles survive focus/press-only frames.
- Shared GPU image/paint composition of cached base tiles and focus/press
  overlays, primitive bulk pixel packing, and bounded native batch readback.
  Both caches share the existing64-program inventory (32/24MiB each); the
  original operation/storage/sample-work budgets remain unchanged.
- Identifiable local `Sirin.app`, native close handling, and deterministic
  adapter/device request-timeout/late-completion ownership controls.

The shared graphics compiler, shader, curved strokes, grayscale glyph paint,
CPU pixel cache and GPU asset arena were integrated as one coupled protocol.
Native `OffscreenGpu` and the shader preserve that track's implementation.
Host changes add an optional glyph provider and deferred inline retention;
the JVM bridge explicitly rejects shared-asset programs it cannot yet handle.
No managed code runs in native callbacks. Timeout tests defer shim completion
under controlled conditions; they do not qualify arbitrary hardware timeouts or
device loss.

## Verification

[Platform log](platforms.log): required `testJVM testNative`, authoring compilation,
`textNative/test probeUnicode/test probeParagraph/test probeRender/test` pass
**693 distinct cases**, zero failed/ignored. Native testkit suites were serialized
with their property counts/assertions unchanged.

| Suite | JVM | Native |
|---|---:|---:|
| graphics |63|63|
| core |9|9|
| textUI |2|2|
| graphicsUI |6|6|
| engine |175|175|
| testkit |30|30|
| workbench |7|7|
| host |15|15|
| textNative |—|7|
| Unicode probe |—|12|
| paragraph probe |—|40|
| renderer probe |—|20|

[Desktop acceptance](acceptance.log) passes another **12 cases**: ABI/native
services, cached/full and direct/composited image equivalence at1x/2x, cancelled
base restoration and resource retirement, sticky service failure/stale results,
and cleanup after diagnostic/constructor failure. Total: **705 cases**. All13
public authoring sources compile. An11-presentation native scenario checks
activation, retained move, scroll, resize, newly mounted programmatic focus
without another native event, and native close. Initialization stages1–10 pass;
forced stages9/10 each deliver and release one late native object with zero
remaining requests/windows.

The final packaged app was launched through CUA under the owner's explicit
approval. Tab/Shift-Tab, Enter/Space, pointer activation, disabled refusal,
retained focus, PageDown/wheel, actual drag-resize and native close were visibly
rechecked. See [OS observations](os-observations.md). Screenshots were inspected
in the session; no saved OS screenshot artifact is claimed. Renderer PNGs in
older receipts remain renderer captures.

## Frozen latency result — failed exit

Fixture:100 records,600x360 logical /1200x720 physical pixels, default128px tiles,
fixed20 action and20 scroll warm-up turns, then100 samples/class. Events enter
through the SDL native queue; each sample ends at its matching successful
presentation. No benchmark PNG I/O. Raw data retain warm-up and measured phases.
The warm-up protocol was made explicit after initial misses; earlier runs are
retained and are not relabeled as warmed successes.

| Class | p50 ms | p95 ms | p99 ms | max ms | Frozen p95/p99 | Result |
|---|---:|---:|---:|---:|---|---|
| action/focus |25.216|63.745|69.110|97.650|50/100ms|**p95 fails**|
| scroll |52.370|63.630|74.798|88.567|100/200ms|passes|

Sixteen action observations exceed50ms; zero scroll observations exceed100ms.
The [raw CSV](input-to-present.csv), [run-specific receipts](benchmarks/), and
[acceptance log](acceptance.log) preserve the failure. The executable rejects
incomplete counts and missed targets. A deliberately closed benchmark exits1
and writes a fresh partial CSV with `status=failed` and
`samplesComplete=false`; see [negative test](interrupted-benchmark.log).
Exceptions also pass through receipt persistence before propagation.

The machine is Mac14,12 / Apple M2 Pro,10 logical CPUs,32GiB, macOS Darwin24.1.0;
desktop uses Temurin25.0.4.1, Scala3.7.4. The aggregate sample taken during the
run showed5.94% CPU idle, load25.63/19.26/20.09 and137MiB unused physical memory;
see [machine snapshot](machine-at-acceptance.txt). This records load context;
it does not establish the cause of the action tail. No idle-run pass is claimed.
The first presented benchmark frame's worker render duration was544.009ms;
this excludes window/session initialization and prior viewport-feedback work,
so it is not a total cold-start measurement. Instrument that separately in the
remaining qualification.

Retained failures include original144ms+ action tails, expensive per-pixel
packing, the first coupled-graphics run (action61.520ms /scroll106.613ms p95),
and a192px tile experiment rejected by the unchanged per-sample work budget.
That experiment was reverted. Earlier in-flight test-harness timeouts are also
retained. The final test keeps its two-second waits, uses plain worker latch
control, and verifies release acknowledgement on the test thread; no timeout
was increased. Final source review reported no remaining bounded-scope blocker.

## Source identity and remaining work

`source.sha256` covers the archived source/fixtures, excluding generated targets,
vendor binaries, Mote and evidence directories. `tested-source.tar.gz` preserves
those exact inputs. `package.sha256` identifies the final launcher, class jar,
native library, runtime metadata and Scala jars. The app image is local and uses
absolute development fixture/native paths; it is not a redistributable package.
The candidate ignores the vendor symlink as a local dependency artifact.

Mote `bd-01M61Q51R1ZBB4ZGXGK6XN6HDC` remains open, blocked by action-latency
qualification `bd-01M61Y6MTGD458A4MYNW92PG6G`. Reproduce the same frozen source
and budgets once on an identified quiet runner; if the tail remains, profile
command/move frames, owner/present costs and GC/scheduling before optimizing.
Keep total startup timing separate. Preserve every run rather than repeating
opportunistically until green.

Monitor-scale/occlusion and quiet CPU/power acceptance remain open in the earlier
host follow-up. Text editing/IME/native control accessibility, Native UI launch,
Native GC pause qualification, Windows/Linux interaction and the matched ScalaFX
usability/elegance experiment are separate gates. This candidate establishes
operability and bounded ownership; it does not establish ScalaFX superiority.
