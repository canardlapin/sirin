# JVM alpha review bundle

The November 2 bundle is prepared for later owner review. Its four applications
have not been launched as part of this tranche. Packaging, signatures and tests
are recorded separately from visible behavior and physical-input acceptance.

## Open the prepared applications

The local bundle is outside temporary storage:

```text
/Users/bbuchsbaum/code/scala/sirin-artifacts/jvm-acceptance-20261102
```

Read `START-HERE.md` there, then open these apps through Finder when ready:

| Application | Path relative to the bundle | Test data |
| --- | --- | --- |
| Workbench | `apps/workbench/SirinWorkbench.app` | `documents/workbench` |
| ScalaFX baseline | `apps/scalafx/ScalaFXWorkbench.app` | `documents/scalafx` |
| Independent starter | `apps/reviewpad/SirinReviewPad.app` | `documents/reviewpad/review.note` |
| Inspector-enabled workbench | `apps/inspector/SirinWorkbench.app` | `documents/inspector` |

Each has a unique bundle identifier ending in
`acceptance20261102.{workbench,scalafx,reviewpad,inspector}`. Use the exact app
path rather than a generic app name, since older packages may still exist.
The inspector copy adds only `-Dsirin.inspector=true`. There are no artificial
input, storage or rendering delays. All four launcher configurations select
absolute synthetic-data paths inside this bundle. Moving the bundle requires
preparing new configurations and signatures; these copies are local artifacts.

The applications come from clean published source
`6a13aea9e666beaadef7b2d8f3c9841de8774914`. ReviewPad was built separately against
the exported binary SDK, without framework source dependencies. Its four tests
passed. `receipts/apps.json` inventories the configured apps, and
`receipts/validation.json` records signature and source-code parity checks.
These are preparation receipts, not application acceptance results.

## Record the review

Use `review-results.md` in the bundle. For each task record app identity, input
method (physical, accessibility automation or synthetic), expected and observed
behavior, pass/fail/pending, and a screenshot or receipt when useful. Preserve
the first failure and modified test files. Do not replace a failure with an
unrecorded retry. Record macOS version, display/scale, appearance, input method,
VoiceOver status and any unrelated load. No timing or performance comparison is
qualified by this checklist.

The Workbench and ScalaFX start with identical `Acceptance Alpha` documents.
The initial body is `Workbench acceptance: saved body 2026-10-31.`. Compare the
same task on both apps, using their separate document roots. Record differences
in available features instead of silently skipping an unsupported task.

1. Open Alpha, change the title to `Acceptance Alpha — saved` and the body to
   `Saved review: café 日本語 123.`. Save, quit and relaunch that exact app. Verify
   both values persisted. In Sirin, also record the save status and dirty marker.
2. Create two documents. Leave an unsaved draft, filter it out and back, reorder
   the collection, and move/detach/return its editor where supported. Confirm
   selection, draft, undo and editor state survive. Save from each active view.
3. In Sirin, leave a dirty draft, request quit and cancel with Escape. Type
   immediately into the field. Verify the decision closes, focus returns and
   typing changes the intended draft. Exercise Tab within the decision and an
   outside click; record dismissal and focus behavior.
4. Close every view while a draft remains, reopen the workbench with Command-0,
   then request quit again. Confirm the application-owned draft and quit guard
   survive zero windows. Avoid discarding it until the observation is recorded.
5. In Sirin, review import from `documents/import`. It contains two valid files:
   Alpha's existing ID and one new ID. Start once. Expect a denominator of two,
   one existing-ID failure without replacement, and one newly created document.
   Compare Alpha's saved contents before/after. A second import should report
   two existing-ID failures. Review `documents/malformed` separately: preparation
   should refuse the malformed document rather than importing it. Do not call a
   completed two-file import a cancellation test; meaningful cancellation needs
   a separately prepared larger set and an observed in-flight cancellation.
6. In ReviewPad, change Title and Notes, toggle Review complete, save, quit and
   relaunch. Verify all three values persisted. Then edit, request quit, cancel
   the decision and continue typing. Record keyboard and checkbox behavior.
7. In the inspector-enabled app, use the Developer menu to show the inspector.
   Refresh, select records and inspect available pages. Record whether its
   separate window remains usable while the workbench is edited and closed.
8. Start each app without foreground activation, then bring it forward. Record
   initial presentation and subsequent input. This task remains pending until
   an actual background launch/foreground transition is observed.

Use only these synthetic documents for external-change/conflict testing. A
conflict exercise needs a recorded external edit between load and save, followed
by explicit reload/save-copy decisions. The baseline checklist alone does not
prove conflict or power-loss behavior. Existing workflow and bounds are described
in [the workbench guide](persistent-workbench.md).

## Physical-input and owner checks

These require a person at the machine and remain pending:

- VoiceOver navigation, labels, checkbox value/action, dialog announcement,
  modal background exclusion, text editing and focus restoration.
- A real IME candidate session: provisional text, commit, cancellation, focus
  changes and interaction with popup decisions.
- Trackpad scroll, real pointer selection/drag and resizing, including movement
  between windows. Accessibility actions do not establish physical delivery.
- Owner visual review in light/dark appearance and available display scales,
  larger windows, text/emoji coverage and reduced-motion preferences. Record
  temporary system-setting changes and restore them afterward.
- Paired ScalaFX review of the same tasks, with an explicit usability judgment
  and feature gaps. Passing test counts do not establish this judgment.

## Reproduce preparation

Use a clean checkout and JDK25. Fetch the pinned vendor artifacts as described
in the workbench guide. Package the production workbench and ScalaFX baseline:

```sh
sbt --java-home /path/to/jdk25 \
  desktopJVM/desktopWorkbenchPackage scalafxWorkbench/scalafxWorkbenchPackage
python3 -B desktop/tools/export-sdk.py \
  desktop/target/package-workbench/SirinWorkbench.app /absolute/review/sdk
```

Copy the tracked `examples/starter` files to an independent directory, set
`SIRIN_SDK=/absolute/review/sdk`, and follow its README to test/package ReviewPad.
Create the four isolated document folders above and seed the three workbenches
with `specimens/scalafx-workbench/src/test/resources/acceptance-alpha.sirin-doc`.
Keep a before-manifest of all fixtures. Run:

```sh
python3 -B desktop/tools/prepare-acceptance.py \
  --workbench desktop/target/package-workbench/SirinWorkbench.app \
  --scalafx specimens/scalafx-workbench/target/package/ScalaFXWorkbench.app \
  --starter /absolute/starter/target/package/SirinReviewPad.app \
  --sdk /absolute/review/sdk --output /absolute/review/apps \
  --documents /absolute/review/documents \
  --bundle-prefix io.github.canardlapin.sirin.acceptance.UNIQUE
```

Choose a fresh review root and unique reverse-DNS prefix. The helper refuses
nonempty or directly symlinked output and existing receipts. It validates the
SDK/app JAR identity, native closure and signatures, then copies and configures
the apps. It checks application JARs and the native transport again after
signing. A failed preparation may leave partial copies; retain them as a failed
generation and use a fresh root. It never launches an app or resets test data.
Run `sbt testJVM testNative` and retain logs beside the bundle.
