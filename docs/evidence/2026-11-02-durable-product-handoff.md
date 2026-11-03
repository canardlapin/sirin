# Durable product handoff — November 2, 2026

The owner review prioritizes product integration, the Linux Native reconciliation
exception and actual JVM alpha acceptance. Additional macOS Native latency
measurements are parked after the completed fixed comparison. No qualification
failure, human acceptance gate or runtime choice is converted to a pass.
See the [execution plan](../plans/2026-11-02-alpha-integration.md).

## Product boundary

The integration branch starts at `1d3f667`, 29 commits ahead of `1a3ca02`: the
28-commit workbench checkpoint `c049658` plus the JVM-first delivery decision and
its frozen qualification tools. Earlier Native evidence remains in that history.
The four subsequent shared-host latency commits through `8b00b89`, and parser
count correction `0d1943e`, remain on `native-qualification-20261102`.
This preserves existing ancestry rather than rewriting the workbench history.

One existing correctness fix is carried forward separately in `f757b35`:
`sirin_push_user` uses `@blocking` because SDL queue locking/platform wakeup can
block. Its C callbacks remain C-only. VISION's explicit list follows the same
existing rule. This is a probe binding correction; no runtime policy or pin is
adopted. Leaving it behind in the ancestry split would restore the reported
boundary mismatch. No latency diagnostic scaffolding is added to the product.

## Durable preservation

All 42 existing `/private/tmp/sirin-*` directories and the original pinned full
Ivy toolchain cache are copied under:

`/Users/bbuchsbaum/code/scala/sirin-artifacts/evidence-preservation-20261102`

The copies contain 625,416 file/symlink entries and 22,943,399,131 logical bytes.
Every copied regular file is SHA-256 verified against its stable source; symlink
targets are verified exactly. APFS clones retain independent file contents while
sharing storage blocks. Source directories remain available, preserving running
or prepared app paths. This is durable local preservation, not publication.

`README.md` maps each old `/private/tmp/NAME/RELATIVE` to `NAME/RELATIVE` in the
archive. The original qualification cache maps to `pinned-upstream-ivy`. Protocols,
old manifests and source paths stay unchanged as provenance. Worktree copies
retain their original .git pointer files; the verified all-refs Git bundle supplies
independent committed histories, not those pointer files alone.

Inventory SHA-256:
`95b332b9b2cd733647f2e9f2d2f6a826a7c237f0aa9028ee38db4afc798a22f0`.
Initial all-refs bundle SHA-256:
`1513a6b2d7ed2805f85538d840e085991ad442b3dbfad12a06eaba324f9dab59`.
A final product bundle and integration receipt preserve the qualified handoff.

The shared checkout's tracked binary patch and 458 untracked source files are
also preserved and checked against their archive. Its tracked diff remains
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
The authoritative `.mote` stays in the shared checkout; its archive copy is a
point-in-time backup. Agent-local `.omc` state is excluded from the source backup.
No shared source is reset or cleaned, and no original artifact is deleted.

## Validation and limits

In the fresh durable integration worktree, with JDK 25 and four active build
processors, `sbt testJVM testNative desktopJVM/test examples/compile
scalafxWorkbench/test` passes: 476 JVM, 476 Native, 159 desktop/GPU and 16 ScalaFX
tests, plus authoring compilation. Native dependencies are the existing fetched
vendor artifacts; none is rebuilt from source. The corrected thread probe also
links on the unchanged default Scala Native 0.5.12 toolchain. It is not executed
as a new Native qualification.

The product production-source blobs match the preceding Native branch checkpoint,
except the carried probe correction's whitespace formatting. The later latency
work concerns probes and evidence, not a different JVM product implementation.
The final handoff change is documentation only. Raw validation logs and source
parity receipts are stored under the archive's `integration` directory.

The parser-only count correction passes all 18 parser/recovery tests. Re-reading
all four frozen measurement logs changes the retained-event counts, not any
maximum or original assertion. Native remains experimental. Its ambiguous
region-0 sampling, trap-defect review, original latency ceiling and application
provider remain open. No overnight job or upstream report is sent.

No human VoiceOver, IME, trackpad, drag, display-scale, visual, visible ScalaFX,
starter or inspector acceptance is claimed from these tests. Alpha1–6 stay open.
The original Linux CI exception remains unexplained despite local passes and
stronger failure visibility. The next engineering slot is its exact CI-platform
attribution; actual JVM acceptance sessions proceed separately.

Main/origin state and the final commit are recorded in the handoff receipt and
Mote. Remote main is verified as `1a3ca02` before local integration. This note
does not assert a push or remote CI pass. The shared-memory connector is
unavailable; repository evidence and Mote carry the handoff.
