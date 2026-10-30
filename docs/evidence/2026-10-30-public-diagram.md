# Public diagram proof, 2026-10-30

## Claim and scope

A new application client in `demo.diagram` exercises the accepted public Element,
named Layout, drawing, input, command and semantics APIs together. Nodes use
curves, gradients and prepared labels. Selection tools are placed by public layout.
Application state owns graph/history, gesture previews and camera state. No
kernel policy or native C boundary changed. This is a bounded Alpha4 proof.

## Method and results

Local validation uses macOS arm64, JDK 25.0.4.1, Scala 3.7.4, Scala Native 0.5.12
and sbt 1.12.14, with the existing pinned native dependencies.

- `sbt testJVM` and `sbt testNative`: 422 tests passed on each platform.
- `sbt examples/compile`: passed.
- Final `sbt desktopJVM/test`: all 98 tests passed. CI now runs this full suite.
- `desktopJVM/desktopDiagramPackage`: passed. The final image passed native
  closure, bundled-font hashes, deep strict code-signature and library-load checks
  without launching UI.
- Diagram model tests cover preview-only dragging, final release coordinates,
  one history entry per gesture, cancellation, focus loss/removal, pointer button
  ownership, undo/redo, stable identity, bounded history, group clamping, invalid
  inputs and transaction refusal. Prepared cubic fixtures include short, backward,
  vertical and coincident-port connections.
- The real desktop controller/render pipeline checks capture, release, commands,
  semantic actions, camera anchoring, floating-tool layout, unchanged text-shaping
  counts during dragging, and stale handler refusal after retirement.
- The dense retained-GPU fixture keeps all 512 nodes, 511 connections and 512
  prepared labels visible in an 800×600 viewport at scale 0.25. It uses 45 GPU
  tiles, within the unchanged 256-tile and 1,048,576-frame-pixel limits. No node
  or label was dropped to make it fit. Unchanged frames reuse completed tiles.
- Independent offscreen pixels cover adaptive subdivision at scales 1, 1.25,
  1.5 and 2, including clipping, isolated opacity and overlapping geometry. The
  existing two-byte per-channel bound remains unchanged. A deliberately reduced
  12-mesh test inventory forces subdivision; production inventories are unchanged.
- Smaller reference-path diagram fixtures use the already-supported 64-pixel
  tiles. The normal retained GPU path starts at its existing 128 pixels and
  subdivides only where admission requires it. The reference path's initial
  128-pixel work-budget refusal is retained in the failed-generation logs.

## Failures retained and corrections

Early generations exposed a test pattern mismatch, a short-connector cusp,
reference-path work-budget refusal, pinned native resource admission failure,
and a single-tile inventory overflow. Connector controls now avoid backtracking
cusps. Pending programs flush before their combined pinned inventories would
exceed fixed limits. Individually excessive tiles subdivide, with at most 256
immutable partition records and 512 region visits per frame. Minimum-size
excesses still refuse explicitly. Independent tests then found repeated parent
compilation for unchanged subdivisions; bounded partition metadata fixes reuse.
Stricter lifecycle assertions subsequently found nine pinned entries after
cancellation and one after a later tile refusal. Error cleanup now closes pending
programs before returning; completed immutable tiles remain safe to reuse. Tests
require zero pending pins immediately, preserve an exported prior frame and check
subsequent recovery. All failed generations remain available with their original
fixtures and bounds. The existing cancellation test expected three pending
programs until the next render; its expectation now requires zero programs and
zero pinned entries at return, while preserving its retry/submission/pixel checks.

## Evidence and acceptance limits

Raw logs, pixel captures, package checks and generated build directories are kept
outside Git under
`/Users/bbuchsbaum/code/scala/sirin-artifacts/diagram-proof-20261030`.
`checksums.json` identifies the logs, captures and package checks.

- Shared suites log SHA-256: `2719a6afce6baf32fab9e21d00e9964d4969d41b0130c8ebb315721af2cda545`.
- Final desktop/package log SHA-256: `2e3b241c34e05a43b2d5d0922ddb91242c0920fb728bf61151e9b499f3754038`.
- Final package-check receipt SHA-256: `e1f4429d142f93b1cbe8f5a43709358203f6997ef026fb6e85297be9ba0c6aeb`.

Captures are explicit GPU readbacks; the normal rendered-frame path reports zero automatic readback bytes.
There is no latency, zero-copy, browser-parity or cross-platform desktop claim.

No app was launched for acceptance. Real pointer operation, VoiceOver and arbitrary
custom semantic-part transport remain open. Owner visual review, independent
feature authoring, the ScalaFX comparison, motion, remaining control migration,
Native GC and delivery-order qualification remain separate gates. Alpha4 is open.
