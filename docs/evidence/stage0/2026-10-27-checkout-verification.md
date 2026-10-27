# Fresh-checkout verification — macOS arm64, 2026-10-27

## Scope and environment

Verified the checkout at `1fc105e` in `/Users/bbuchsbaum/code/scala/sirin`.
No implementation changes were needed for this baseline.

- macOS 15.1.1 (24B91), arm64; 226 GiB available before the build.
- Temurin JDK 21.0.12.1; Apple clang 16.0.0.
- Scala 3.7.4, sbt 1.12.14, Scala Native 0.5.12 (repository pins).
- GitHub CLI login verified as `canardlapin`; repository-local commit
  identity configured as `canardlapin <brad@duckrabbit.ai>`.
- HTTPS origin: `https://github.com/canardlapin/sirin.git`.

## Commands and results

`native/fetch-macos.sh` fetched the pinned wgpu-native v29.0.1.1 and SDL3
3.4.18 prebuilt artifacts. Both SHA-256 checks passed.

```sh
sbt testJVM testNative examples/compile probeWindow/test probeThreads/test
```

The command exited successfully. Test report XML confirms:

| Check | Result |
|---|---|
| JVM `HandleSuite` | 2 passed |
| Native `HandleSuite` | 2 passed |
| Engine and testkit, JVM and Native | Commands succeeded; no suites yet |
| Authoring corpus | All 7 Scala sources compiled |
| Native `AbiSuite` | 14 passed |
| Native `MailboxSuite` | 13 passed |

All 31 executed tests passed, with zero failures, errors, or ignored tests.
Reports are in each project's gitignored `target/test-reports/` directory.

## Tracker setup

The installed `mote 0.1.0` does not support the handoff's `mote admit`
command. `mote init` recognizes the imported store but does not restore its
missing temporary directory. Creating `.mote/tmp` and `.mote/local` and
setting a distinct local actor makes `mote doctor` report valid format and
layout and a clean operation log. The original store ID and imported
operations are preserved. The resume instructions now document these steps.

## Remaining gates

This is build and deterministic-test evidence only. It does not rerun the
P0.6 allocation/GC soak, the optimized safepoint-stall reproducer, the P0.7
million-delivery stress cases, or manual window/drag-resize checks. Their
previous evidence and open blockers remain as recorded in the handoff.
Rulings R1–R16 remain proposed, awaiting project-owner acceptance; Stage 1
kernel semantics have not been changed.

The parent workspace has no `packages.toml` on this machine. The catalog
entry mentioned in the old handoff remains specific to the original
workspace; no new workspace catalog was invented here.
