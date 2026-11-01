# Visible inspector and copied frame diagnostics

The optional JVM development inspector is opened from the Developer menu when
`sirin.inspector=true`. It uses public controls and an ordinary keyed window.
Capture, search, five-row paging, bounded scalar previews and command/signal/
scope/cause links operate on one copied snapshot. Closing clears the snapshot
and cancels pending capture; application quit decisions remain authoritative.
The design is recorded in
[the decision](../design/decisions/2026-11-01-visible-inspector.md), with usage in
[the inspector guide](../tooling/runtime-inspection.md).

The shared tooling module is included in both required test aliases. Eight model
tests cover post-commit/coalesced capture, rollback, search/navigation, omission
messages, paging/budget caps, close/reopen, native-style close, quit preservation,
unique navigation keys and distinct frame generations. An initial navigation
fixture omitted its Save button, so its enabled signal was correctly absent from
the committed graph. Mounting the real control made that diagnostic path testable.
A separate regression reproduced duplicate links for a signal with repeated
inputs; deduplicating navigation targets repairs it. The original failure log is
preserved, and the graph's actual dependency edges are unchanged. Another failing
regression found malformed scalar UTF-16 and NUL passed unchanged into rendered
rows. The browser now escapes them (and distinguishes literal backslashes), with
bounded labels/details and an explicit truncation marker. JVM/Native tests and a
retained-GPU render show the literal escapes; the original failure is retained.

Three offscreen desktop checks render the inspector at 640×400 and 1000×720 on
the CPU readback/upload path, and at 640×400 on the retained-GPU path. They check
public control semantics and bounded worker cache summaries. The first small
render left too little room for details; a compact fixed header now exposes the
Save refusal at default size, with further details and links scrollable below.
The images were visually inspected. GPU test image export adds an explicit
readback outside the production frame's recorded zero-readback path.

Render workers populate a bounded cache-summary string only in inspector mode.
The owner copies numeric frame metadata and bounded failure receipts; snapshots
never retain GPU frames or read worker caches from the owner thread. Requested,
rendered and last presented generations are separate, and worker/queue time is
not reported as end-to-end input latency. No per-frame filesystem writes or idle
polling were added. The default application package leaves tooling disabled.

Initial compilation failures (incorrect API spellings, capture field name and
an unused test import under fatal warnings) are retained with the subsequent
passing logs. No assertions or acceptance thresholds were weakened.

The full run passed 469 JVM, 469 Native and 154 desktop tests, examples compilation,
packaging and the Native ABI probe. After the malformed-value repair, all eight
tooling tests passed on JVM and Native, all three desktop inspector tests passed,
and the final package rebuilt successfully. There are now 470 distinct shared
tests on each platform. No full-suite pass is inferred from those final focused
checks; their exact scope is retained in the logs.

The default package passes portable dependency/font/signature/library-loading
checks. A separate image at
`/private/tmp/sirin-inspector-acceptance-20261101/SirinWorkbench.app` enables the
inspector and uses only its sibling `documents` directory, copied from the isolated
workbench fixture. Deep/strict signing passes, and the Native probe reaches all
nine ABI entries in that exact image; the loader path is recorded. The earlier
controlled popup image and its pending approval remain unchanged.

The inspector image has not been launched. Its action-time launch approval is
pending, so this note does not claim real keyboard, pointer, VoiceOver, IME or
packaged-window acceptance. Complete per-phase invalidation causality, performance
qualification and Native application delivery stay open.

Artifacts are retained in `/private/tmp/sirin-visible-inspector-20261101`. The
35-file source/log/render/package-receipt manifest has SHA-256
`b11fdc63b62297677175f905d3f1c7b6eb2029917a1ce564a6659e3ac55e7c44`.
The separately retained app bundle is identified by its jar/native/configuration
hashes in `acceptance-package.json`; its final tooling classes match the compiled
classes byte-for-byte. Alpha5 remains open under
`bd-01M65371PMXY6DG0YZNVJ15R00`.
