# Native qualification successor — 2026-10-29

This tranche produces an evidence-supported private GC remedy and a relocatable
development package. **P0.6 remains open**: production runtime policy is unchanged,
the long-event gate is unrun, and real native client testing awaits explicit
launch/setup confirmations. The clean landing candidate is isolated on
`codex/native-qualification-20261029` in `/private/tmp/sirin-qualification-20261029`,
from 970cd41. The shared checkout's source and prior receipts are preserved.

## GC mechanism and causal control

[The unsent upstream successor](upstream-report.md) records the full observation,
patch, inputs and limitations. Frozen sequence outcomes:

| Sequence | Completed | Safepoint abort | External timeout |
| --- | ---: | ---: | ---: |
| Query-only phase diagnostic | 1/3 | 2/3 | 0/3 |
| Original controls surrounding phase diagnostic | 0/2 | 2/2 | 0/2 |
| Saved-mask restoration control | 3/3 | 0/3 | 0/3 |
| Original controls surrounding restoration | 0/2 | 2/2 | 0/2 |
| Independent restoration,120s / 4 workers | 1/1 | 0/1 | 0/1 |

All short attempts use 60s / 1 worker / 2048 survivor slots, 128M heap cap, 15s GC
synchronization timeout and 75s external timeout. The120s check retains the same
per-worker survivor count/cap/synchronization timeout with135s external timeout.
There are 11 allocation attempts, with all outcomes retained. Instrumented and
original runtimes are separate generations. Shared-host correctness results are
not latency measurements,estimated failure rates or universal progress proofs.

Both instrumented failures retain saved-context mask 4 vs trampoline/resume/
admission masks 5 (BUS=1,SEGV=2,CONT=4),matching recognized handler entries and
exit-hook counts 1698 and 913,active 0,nested 0. The observations are last values,
not a coherent simultaneous mask snapshot. The private causal patch copies the
full saved context mask and restores it before yielding from the normal-context
trampoline helper. It changes no handler flags,assembly or Sirin scheduling.
Three candidate completions between two original failures support a remedy for
the bounded observed stall. They do not qualify nested/asynchronous signal cases,
other collectors/platforms or production adoption.

The separate four-worker run finishes 39,523 collections and 973,246,353 allocator
iterations, validates reachable survivor endpoints and retains 4,351,848 payload
bytes. Sampled runtime heap capacity stays 35,790,848 bytes after startup; this
is neither RSS nor live payload. The first diagnostic build's missing signal.h
declaration is preserved as a build failure; its corrected successor alone ran.
All three private JARs replay byte for byte from their frozen input hashes.

An event-probe candidate from original source 1fc105e links the same saved-mask
JAR with traps enabled and passes 31 compatibility checks. The previously prepared
conditional event binary also remains preserved. **Neither has passed the
original 600-second event/ticker 50ms qualification.** After all candidate builds
and allocation controls finished, all six unchanged idle-preflight samples failed:
31.82–55.52% idle vs 80%, with unchanged load limit 0.5 per logical CPU. No gate
was launched,no unrelated process stopped,no threshold waived and no retry-until-
pass attempted. Candidate defaults remain stock 0.5.12.

## Relocatable development image

The desktop shim now has install name `@rpath/libsirin_desktop.dylib` and its sole
runtime search path is `@loader_path`. SDL is copied beside it using `ditto` to
retain framework symlinks. The app image bundles that framework and the four
fixture fonts with their license/provenance files. Its config resolves both the
shim and fixture root with `$APPDIR`,removing source-worktree dependencies.
System frameworks,JDK/OS requirements and the bounded system CJK fallback remain;
this is not a redistributable installer or cross-platform qualification.

The original combined command passes 323 JVM + 323 Native + 50 desktop cases and
examples compilation,then fails packaging because IO.copyDirectory flattened the
SDL framework symlinks. The exact codesign failure,malformed app hashes and local
preserved output path are retained. This is not relabeled as an all-success run.
A successor preserves framework links and passes the affected 50 desktop cases
again plus the package task. There are 696 distinct current Scala cases; those 50
reruns and the separate 31 original-probe checks have explicit denominators.

`desktop/tools/portable-image.py` validates arm64 Mach-O dependency closure,
install name/rpath,internal framework symlinks,pinned font bytes and deep strict
code-signature validity. Both the original and relocated complete app copies pass,
and their shim/SDL/config hashes match. A separate process successfully loads the
relocated library without invoking any UI function. The preserved malformed
framework is a negative control and is rejected. No app was launched through a
CLI,library load or other route to bypass the rejected native UI action.

The built app is `desktop/target/package-editor/SirinEdit.app`; the independently
checked copy is `/private/tmp/sirin-relocated-20261029/SirinEdit.app`. Exact package
hashes are in package-manifest.json. The large generated app/runtime/binaries stay
local; compact logs,patches,recipes,hashes and all failures are committed.

## Native client gate and replay

Automatic approval review rejected the new launch of the previously accepted
SirinEdit.app,requiring confirmation for this invocation despite the prior launch
approval. A new exact-app question remains pending. Read-only CUA inspection
finds only the Canadian input source,Show Input menu off,and Japanese Romaji
available. **VoiceOver is already on** and has not been toggled. Temporary
Japanese/Chinese input-source setup/restoration was requested explicitly; no
preference changes have occurred. Real composition/candidate UI/cancel,VoiceOver
announcements/navigation,and native drag/word selection remain unrun in Mote
`bd-01M64NKHTDEJ7WZGQBTA6SMF23`. Prior accepted direct-client tests remain separate.

Run `python3 -B docs/evidence/native-qualification-20261029/verify.py` to verify
frozen hashes,sources,all 11 allocation attempts,positive/negative packaging
receipts and unchanged qualification limits. `replay-runtime.py` reconstructs
private JARs from their hash-checked inputs/resources. The saved-mask control
consumes the original pinned JAR; both phase diagnostic generations consume the
previous frozen mask diagnostic. Integrity replay does not qualify runtime/UI.

Remaining execution: a suitable quiet window for the unchanged original event
gate,and the requested launch/input-source confirmations for real native client
validation. No upstream report has been posted and no shared branch advanced.
