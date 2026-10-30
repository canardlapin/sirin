# First public toolkit proof, 2026-10-30

## Claim

Public custom elements and layouts now execute through guarded phases. The
single-state form and full graphics adapter are usable by an external-package
consumer. Standard Button uses that seam for measurement, drawing, semantics,
activation and feedback. This is an initial toolkit proof, not Alpha4 completion.

## Checks and results

On macOS arm64, JDK 25.0.4.1 and the pinned Scala/Scala Native toolchain:

- Full `sbt testJVM`: 409 passed before the final input-contract regression.
- Full `sbt testNative`: the same 409 passed.
- `sbt examples/compile`: passed, including consumer/CurveControl.scala.
- Final input follow-up: all 53 host tests passed on JVM and Native. This adds
  one regression beyond the full runs, bringing the shared inventory to 410.
- Final `sbt desktopJVM/test`: all 90 passed after the input fix and the diagnostic
  pattern update. No app was launched for real-client acceptance.
- Public graphics recordings match standalone commands for curves, gradients,
  images, instance batches, transforms, clips, layers and Drawing embedding.
  Scoped and cross-thread recording misuse refuses explicitly.
- Phase revisions, named-child identity, stale handlers, caught mutation/read
  violations, constrained measurement and hidden-child behavior are exercised.
  Declared font/scale epochs invalidate measurement; theme-only epochs do not.
- Button input preserves release semantics, reconstruction identity, disabled
  command refusal and stale-instance refusal. Press feedback and disabled-color
  changes do not increase the real text-shaping counter.
- Shared activation honors custom hit geometry and focus policy. A paint-only
  change preserves pending input when hit/semantic state and geometry are unchanged.
- Workbench and recovery fixtures replay immutable drawings with fresh fonts,
  device and caches, matching pixels byte for byte. Accepted fixtures contain no
  UI code or graph; refused input fixtures remain available. Existing edit/save,
  recovery, identity and undo assertions remain in force.

Early failures remain in the external logs: the archived identity assertion
assumed a private Button snapshot; reconstructed definitions initially remounted;
accepted-frame serialization encountered custom callbacks; one diagnostic pattern
needed the new interaction-target field. Each was corrected and rechecked.

## Evidence storage

Raw logs and checksums are outside Git at:
`/Users/bbuchsbaum/code/scala/sirin-artifacts/public-toolkit-proof-20261030`.
`checksums.json` identifies logs and generated drawing/pixel fixtures. Generated
worktree directories are preserved there when the task checkout is retired.

- Full suites log SHA-256: `a16301da4075e85fd7329e7fbf0dbd6b778b68196c9aa7ddc6ed483a1c2d30f3`.
- Final host follow-up log SHA-256: `35bf75b5c4f92d8ea75066e99daed97eacfe3408d73983a11d4262c5b5a2ffbf`. Its subsequent desktop compile
  found the diagnostic-pattern mismatch; the following run supersedes that failure.
- Final desktop log SHA-256: `c0d44706c98080e97b095cb4aeeb133d04fd64da622a7fb49f7535fabf2c0369`.

Formatting of touched existing files is a separate behavior-free commit. The
implementation and its status/guide changes follow it. Exact remote CI status is
reported against the pushed commit, separately from these local checks.

## Remaining boundaries

Native GC/P0.6, real IME/VoiceOver/drag acceptance, full semantic-part transport,
non-button accessibility roles, configurable element event queues, dynamic
font/theme reconfiguration, motion, remaining controls and the diagram stay open.
The desktop explicitly refuses element capacities other than its shared 256-entry
FIFO. Constant Presentation values are not motion. No new performance or
cross-platform desktop claim is made.
