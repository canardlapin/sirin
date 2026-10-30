# Worktree and design stocktake, 2026-10-30

Implementation is paused at the owner's request. Main includes the accepted
application checkpoints through `1d5edcc` and the design review `7cd0dd5`.
The exact `1d5edcc` CI run, 41015663675, passed all nine jobs. The review and this
stocktake change documentation only; their later CI status is a separate claim.

## Every remaining worktree

Paths below are relative to `/Users/bbuchsbaum/code/scala/`, except `/private/tmp/`.
Merge ancestry and dirty-source content were checked live, not inferred from age.

| Worktree | State | Disposition |
|---|---|---|
| `sirin-main` | Main, clean before this documentation lands | Canonical accepted checkout |
| `sirin-alpha` | Clean stocktake branch; element WIP saved separately | Return to the paused WIP branch after landing this note |
| `sirin` | Branch tip `ae1817f` is in main; old dirty source | All 751 non-Mote/non-OMC live files match archive `28d05fc`; retain authoritative `.mote` and local state |
| `/private/tmp/sirin-gpu-host-20261028` | Tip `08d9837` is in main; old dirty source | All 997 non-Mote/non-OMC live files match archive `3e1d33d`; the running Sirin app uses this path |
| `/private/tmp/sirin-editor-20261029` | Clean, tip `9372ca5` in main | Retain frozen SirinEdit launch candidate at its approved path |
| `/private/tmp/sirin-camera-20261029` | Clean, tip `13030fe` in main | Source merged; generated build/evidence artifacts retained pending cleanup |
| `/private/tmp/sirin-qualification-20261029` | Clean, tip `021919e` in main | Source merged; generated build/package artifacts retained pending cleanup |
| `/private/tmp/sirin-responsive-20261028` | Clean, tip `f4acc6c` in main | Source merged; generated build/evidence artifacts retained pending cleanup |
| `/private/tmp/sirin-tranche-20261029` | Clean, tip `970cd41` in main | Source merged; generated build/evidence artifacts retained pending cleanup |

The design-review worktree and branch were removed by their owner after landing.
No worktree was force-removed, no dirty source reset, and no running app stopped
for this stocktake. D11 remains open for artifact-aware removal and consolidation.

## Preserved work outside main

All three archive refs are now pushed to origin:

- `archive/gc-threading-wip-20261029`: `28d05fc`.
- `archive/editor-wip-20261029`: `3e1d33d`.
- `archive/public-elements-wip-20261030`: `0413beb`.

The newest archive has five passing engine and four passing host JVM tests. Its
diagram compilation fails because the generated main differs from `DiagramEditor`
only by case. Full JVM/Native suites, desktop compilation, diagram model/input
validation and real clients remain unchecked. It must not be merged as accepted
code. The archive includes a short resume note with these limits.

## Direction before implementation resumes

The review's architectural priorities stand. Public elements must be accompanied
by full graphics access, a simple single-state form, and built-in controls using
the same seam. Prove that with a small built-in port before expanding more private
host machinery. TextField also needs public text/composition and text-range
semantics capabilities; a successful diagram alone cannot close D1.

Native GC and delivery order remain an explicit gate before Alpha6. Motion stays
inside the alpha. Bulk copies, readable ownership code and lighter evidence
storage are standing constraints, not reasons to declare any alpha item complete.

One review correction: main already has shared-device retained GPU presentation
without a full-frame CPU readback on its normal batch path. Reference/fallback
rendering and explicit captures still use readback. The status page and D8 now
make that distinction; no fresh performance measurement is claimed.
