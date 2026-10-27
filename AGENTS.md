# Sirin agent instructions

- **Design authority.** `docs/design/VISION.md` is the design authority, and
  `docs/plans/` holds the current plan. If code and documents disagree, stop
  and report.
- **Kernel semantics.** Transactions, identity, lifetimes, scheduling, and
  command policies (VISION §5) are owned by the main session. Do not change
  them without an explicit design update.
- **Native boundary.** Scala Native cannot pass structs by value. Every
  by-value C function or callback goes through the pointer-only C shim.
  Scala code never runs on a thread that Scala Native did not create.
  Every blocking extern is `@blocking`, and the list is in VISION §5.4.
- **Verification.** Run `sbt testJVM` and `sbt testNative`. A probe's claims
  need an evidence note in `docs/evidence/`. Never weaken a test. A test
  that cannot run yet is skipped with a reason; it is never a passing stub.
- **Native artifacts.** Fetch them with `native/fetch-macos.sh` into
  `native/vendor/`, which is gitignored. Do not build native dependencies
  from source without checking free disk space first.
- **Issues.** Track work in Mote (`mote ready`, `mote claim`,
  `mote close`).
