# System light and dark appearance

Tracking: `bd-01M65371KJWBVVMB9ANE8KWZPG`, within the persistent workbench
acceptance goal. Parent revision: `e921e31`. Worktree:
`/private/tmp/sirin-workbench-20261031`.

## Capability and limits

The desktop application host reads SDL's current system theme at startup and
each event-loop turn. SDL's global theme event wakes the existing loop; no new
polling timer is introduced. Unknown preferences retain the dark default.
An immutable `ThemeSnapshot` travels with each frame, including Reduce Motion.
Workers use the captured palette. Theme changes invalidate older frame requests
and advance the existing theme-color epoch without changing kernel semantics.

Standard controls now use semantic colors for backgrounds, text, selection,
validation, borders, focus and press feedback. OnAccent supplies contrasting ink
on accent backgrounds. The public diagram and NoteField specimens also use
semantic colors, avoiding dark-only node fills or pale carets on light surfaces.
The design note records the alternatives set aside.

These changes do not establish actual macOS appearance switching, VoiceOver,
IME, physical trackpad behavior, display-scale review, owner visual approval or
ScalaFX superiority. No application window or operating-system setting was
changed in this continuation. The older isolated launch candidates are preserved.

## Checks

- Palette tests require text contrast of at least 4.5:1 against both surfaces,
  focus/border contrast of at least 3:1, and contrasting text on accent and
  selection backgrounds. These are palette checks, not full accessibility
  conformance or rendered-glyph contrast measurements.
- The generation gate rejects dark results after a light request and rejects
  both older requests after switching back to dark, while retaining tree identity.
- A custom element declaring a theme-color layout dependency remeasures on
  each switch. An ordinary element keeps its cached measurement.
- Both reference/readback and retained GPU sessions render a public form dark,
  light, then dark. They retain the typed draft, text focus and semantics, avoid
  reshaping unchanged text, match a fresh light render exactly, and reproduce
  the earlier dark pixels. Additional states verify visible keyboard focus and
  press feedback on primary buttons and checked boxes.
- The public diagram switches both ways in both renderer paths without changing
  geometry or reshaping labels. Light/dark form and light diagram captures were
  visually inspected; this is agent inspection rather than owner approval.
- A C probe opens an application context without creating a window. Its query
  returns light (`1`), equal to SDL's query, and rejects missing, wrong-thread
  and retired owners. The JVM suite loads the FFM binding. A separately linked
  Scala Native executable calls the same pointer-only entry point and verifies
  missing-owner rejection. This is ABI reachability, not a Native application
  provider or Native desktop qualification.

## Failures preserved

The first sbt attempt could not write its cache lock under the sandbox. The next
desktop test compile rejected an unused helper under fatal warnings; the helper
was removed. Initial focused rendering then passed.

Expanding focus coverage exposed a reference-renderer refusal for the checkbox's
rounded focus stroke: `requirement failed: per-sample work budget`. The retained
GPU path passed the same form. A square focus border passes the same regression
in both paths. No renderer budget or test assertion was weakened. The original
failing run and intermediate captures remain in the artifact directory.

One later full Native run failed before `TransactionSuite` connected to the test
runner. The runner reported an accept timeout, forcibly terminated that child
with signal 9 and reported 199 engine tests passed plus one runner error. The
same suite passed all 24 tests unchanged in isolation. No same-day Sirin/Java
diagnostic report was found. Memory and process snapshots were retained, but
they do not establish a cause. The planned single full Native follow-up and its
specimen-color addendum were recorded before execution. A later pass must not
be interpreted as explaining or resolving this startup failure.

## Final verification and artifacts

Final source verification passes on macOS with JDK 25:

| Check | Result |
|---|---:|
| `testJVM` | 476 passed |
| `testNative`, documented full follow-up | 476 passed |
| `desktopJVM/test` | 159 passed |
| Focused appearance and diagram suites | 11 passed |
| Examples compile | Passed |
| Native appearance probe link and execution | Passed |
| Packaged-library Native probe | Passed |
| App build and deep/strict signature verification | Passed |

The earlier Native timeout remains unresolved despite the isolated and full
follow-up passes. Engine test sources, runtime pins, timeouts, heap settings and
test concurrency were not changed. The final implementation/test source digest
list was frozen before the full follow-up and verified afterward.

Artifacts are under `/private/tmp/sirin-system-colors-20261101`: every failed and
successful log, the prospective Native follow-up records, memory/process
diagnostics, source copies/digests, intermediate and final PNGs, native probes,
signature receipts and the isolated app image. `manifest.json` has 620 file/link
entries and SHA-256
`790f26a6e93691385f3422e8c4f6494290b41cceb6732f36f971ff58b64d6060`.

The image is `SirinWorkbench.app` in that directory, configured only for its
sibling `documents` directory, with a distinct application identifier. It was
not launched. Its `sirin.jar` SHA-256 is
`1115cac9e38dcf7a0cd1272a4852223416c155080851df9ccf7a5915f529b8b4`;
the packaged native library SHA-256 is
`08805d438211ff41d6c5af8da9625186ee0ed5582c67e97e43f9825670aeeb63`.
The Native probe's loader trace confirms that exact packaged library and its
SDL framework were loaded, rather than the development copies.

The shared checkout's tracked diff remains unchanged at SHA-256
`1bfa8a28e367ba1c946dcaf2e7febba495c95b8c52127c2379ac590320dfa588`.
This continuation does not merge or push the owned branch, close visual
acceptance, or adopt a Native runtime remedy.
