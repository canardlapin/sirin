# Initial alpha delivery: JVM first, Native experimental

The owner authorized the five-step Native qualification and delivery course on
November 2, including an explicit delivery-order decision from its results.
This session selects a macOS/JVM initial alpha. Scala Native remains a required
experimental target with a shared programming model and its own delivery gates.
This supersedes the contingent Native-first order recorded on October 30.

The conditional-yieldpoint configuration at pinned upstream `fa4a9e6` is the
Native qualification candidate. Its binaries and build-time setting are verified,
and a fresh 120-second four-allocator survivor check completes with a flat sampled
heap capacity. It has not passed the original long event/50 ms qualification.
The November 2 preflight fails all six samples, so the gate is unavailable on
the current shared host. This is missing qualification, not a failed conditional
runtime result. There is also no qualified Native application provider.

A JVM alpha must still satisfy its application, input, accessibility, visual,
independent-authoring and ScalaFX comparison gates. Nothing in this decision
declares that alpha accepted or changes the programming model. The JVM uses
its own runtime; the Native safepoint gate does not measure JVM GC behavior.

Native delivery requires the unchanged event workload and pause limits on a
qualifying macOS ARM64 host, an explicit reviewed runtime choice, appropriate
regressions on that toolchain, and an implemented/qualified application provider.
The trap-path defect remains open for runtime review even if a conditional
configuration passes. The experimental saved-mask patch is not adopted.
No production runtime pin, transaction, identity, lifetime, scheduling or command
policy changes. Shared JVM and Native suites remain mandatory.

Alternatives set aside:

- Keeping the initial alpha blocked on Native prevents application acceptance
  progress despite an executable JVM host and no qualified Native provider.
- Adopting the conditional configuration immediately would promote bounded
  correctness checks to an unearned latency and platform claim.
- Adopting the saved-mask patch from short passing trials would bypass the
  unresolved mask ownership, nesting, main-mutator and register-state review.
- Relaxing the readiness or 50 ms limits would lose the original qualification
  question. Those limits and all existing failure records are retained.

Tracking: P0.6 `bd-01M5Z3K47RQTRDXEY7Y2P8TMJ3`; delivery-order decision
`bd-01M66B73BRTV11D479KARSA9Y8`. Evidence is in
[the November 2 qualification note](../../evidence/2026-11-02-native-qualification.md).
