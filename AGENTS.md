# Sirin agent instructions

- **Design authority.** `docs/design/VISION.md` is the design authority, and
  `docs/plans/` holds the current plan. If code and documents disagree, stop
  and report. Standing review directives are in
  `docs/design/decisions/2026-10-30-design-review.md`.
- **Kernel semantics.** Transactions, identity, lifetimes, scheduling, and
  command policies (VISION §5) are owned by the main session. Do not change
  them without an explicit design update.
- **Native boundary.** Scala Native cannot pass structs by value. Every
  by-value C function or callback goes through the pointer-only C shim.
  Scala code never runs on a thread that Scala Native did not create.
  Every blocking extern is `@blocking`, and the list is in VISION §5.4.
  The JVM FFM bindings and the Scala Native externs call the same shim.
  Arrays cross the boundary in one bulk copy, not per-element loops
  (VISION §6.1).
- **Public seam.** Standard controls use the public `Element`/`LayoutPolicy`
  and semantics interfaces. A control that needs a host-private hook gets an
  entry in the public-seam ledger in `docs/STATUS.md` (VISION §8).
- **Verification.** Run `sbt testJVM` and `sbt testNative`. A probe's claims
  need an evidence note in `docs/evidence/`. Never weaken a test. A test
  that cannot run yet is skipped with a reason; it is never a passing stub.
- **Evidence weight.** The committed note says what was claimed, how it was
  checked, and the result. Keep raw logs, tarballs, captures and source
  snapshots over 1 MB out of git. Keep them as CI or release artifacts, or
  under an ignored local path, and record their hashes in the note
  (VISION §11).
- **Status page.** A change in what works updates `docs/STATUS.md` in
  plain language.
- **Code style.** In new or edited code, use one statement per line and keep
  lines at most 120 characters, matching `core`. Don't chain statements with
  `;`. Reformatting existing files is a separate, behavior-free change.
- **Native artifacts.** Fetch them with `native/fetch-macos.sh` into
  `native/vendor/`, which is gitignored. Do not build native dependencies
  from source without checking free disk space first.
- **Worktrees.** Each task runs in its own git worktree, named in its Mote
  claim. When the branch merges, remove the worktree and delete the branch.
  Never leave uncommitted state behind at hand-off: commit it, or archive it
  to a named `archive/*` branch and say so in the hand-off note.
- **Issues.** Track work in Mote (`mote ready`, `mote claim`,
  `mote close`). Write notes as sentences a person can read, not compressed
  counters.
