# Historical Linux Native replay and failure visibility

Tracking: `bd-01M65371ESG6B9B7S82RNE1R1W`. Parent worktree revision:
`cdf144d`, in `/private/tmp/sirin-workbench-20261031`.
The original CI ClassCastException remains unexplained. This continuation adds
local Linux evidence and prevents a captured type error from being hidden by the
reconciliation property's coarse failure comparison. It changes no engine,
kernel policy, runtime pin or acceptance threshold.

## Historical input and protocol

The original failed run is `40974637787`, revision `5c73273`. Its retained log
and forty-step program still match their recorded hashes in
`application-alpha/ci-linux-baseline/baseline.json`. The failed seed remains
`l8Dl-ZJbwAFzUVsay_UCl1zuWxTHh-oHefe7es6XzbN=`.

The local container builds a separate archive of `b0facfb`, the historical
diagnostic revision. Git comparison confirms that its engine, core, testkit
model, project configuration and build file match the failed revision. Its
additional tests preserve the original randomized 300-case property, replay the
seed for another 300 cases and run the fixed forty-step program. All 2,295
archived files were checked against the source tar before follow-up changes.

The prospective protocol fixed one full `testNative` run, followed by three
`testkitNative/test` runs with `GC_INITIAL_HEAP_SIZE=8M` and
`GC_MAXIMUM_HEAP_SIZE=128M`. Each had a 900-second external timeout; every outcome
was retained. The heap settings request a smaller initial heap and a cap; actual
collection frequency was not instrumented. There was no retry-until-green.

The environment is local Docker Linux amd64 on an ARM64 Docker host, using
architecture translation, four CPU units and an 8 GiB container limit. It has
Linux 6.10.14-linuxkit, Ubuntu glibc 2.39, Temurin 21.0.13+9, Clang 18.1.3,
sbt 1.12.14, Scala 3.7.4 and unchanged Scala Native 0.5.12. This is not the
original Linux 7.0/glibc 2.43 CI runner. Timings include build/startup work and
are not performance qualification.

## Historical results

| Run | Passed tests | Elapsed | Result |
|---|---:|---:|---|
| Full Native baseline | 327 | 701.37 s | Exit 0 |
| Smaller heap, testkit 1 | 34 | 57.95 s | Exit 0 |
| Smaller heap, testkit 2 | 34 | 56.19 s | Exit 0 |
| Smaller heap, testkit 3 | 34 | 72.16 s | Exit 0 |

Both reconciliation suites ran in every cell. No timeout or ClassCastException
was reported. These four passes neither reproduce the original failure nor
establish a remedy. The compiler's executable-stack warning for the pinned
`SafepointPollTrampoline-x86_64.S` is retained; no causal connection to the
exception is established.

## Diagnostic gap and regression

The property reduced every `CommitResult.Failed(error)` to model `Result.Failed`.
The generated program deliberately includes missing keys, duplicate keys and a
caught poison exception. A ClassCastException caught inside a transaction could
therefore compare equal to an expected model failure, losing the type-error
evidence. This observation does not explain the original exception that escaped
to ScalaCheck.

The classifier now rethrows the same captured ClassCastException before reducing
other failed receipts. The existing first-exception reporter can then preserve
its original stack. No generated operation intentionally causes a type error.
A new test obtains an actual poisoned receipt from a nested transaction that
catches a synthetic ClassCastException, then requires the classifier to rethrow
that exact instance. Explicit refusal and intentional poison controls remain.
The independent model and both 300-case properties are unchanged.

A negative control removed only the new classifier arm. The new test failed
because no ClassCastException was thrown; the other three tests passed. The
exact guarded source was restored in `finally` before the positive checks.
This demonstrates the diagnostic blind spot rather than a reproduction of
memory corruption or the original Linux failure.

The guard follow-up is separate from the completed historical protocol. The
first preparation check refused copying today's file unchanged into the old
source tree because its snapshot API changed from `Group` to `CustomLayout`.
The Linux follow-up retains the historical `Group` match and applies only the
new classifier and regression. Its protocol, source and all outcomes are kept
separately under `guard-followup/`.

Positive checks passed with the guard restored: `sbt testJVM` ran 472 tests and
`sbt testNative` ran 472 tests on macOS with JDK 25, exit 0. The separate Linux
follow-up passed 36 testkit tests, exit 0 in 176.24 seconds including startup and
rebuilding. Both inherited reconciliation suites include the new regression.
No container timed out or was OOM-killed. Desktop code was unchanged, and its
suite and window checks were not repeated in this continuation.

## Artifacts and remaining work

Raw artifacts are under `/private/tmp/sirin-linux-reconciliation-20261101`:
the source tar, container recipe and image identity, environment, prospective
protocols, all four historical logs/receipts, negative control and follow-up
checks. The first image build failed only because the sandbox could not update
Docker builder metadata; the approved build completed with the same recipe.
The source tar SHA-256 is
`f0de263602870b16eccc4061f3313a20d4bafc4820e21ff3623d786570c581a2`.
The built container image is
`sha256:ef206800c7f3c71fbc729d7a07dff5725001d7099f1ecb0bf65dd79f25bead79`.
The 43-file manifest has SHA-256
`9b0ef027aac04994119e42fa12fd74685e3b129b8b4b65af5cf911d0ed66b674`;
every entry was rehashed successfully. It includes the final guarded Linux
testkit binary and the resolved nativelib JAR. Historical test binaries were not
separately frozen before the guard rebuild; their source archive, recipe,
environment and run logs are preserved. The mutable dependency cache and build
tree are excluded from the manifest. All original archived source files were
verified again after the historical test file was restored.

The shared checkout's tracked diff is unchanged at SHA-256
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.

The original cause remains open and requires an actual reproduction with a
useful stack or another causal explanation. Future CI runs gain the failure
visibility check after this branch lands; these local runs do not update remote
CI status. The separate macOS GC stall, idle-host event/50 ms gate, Native
application provider and delivery-order decision remain open.
