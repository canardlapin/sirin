# Linux Native reconciliation baseline

Original public main5c73273 run40974637787 failed in Linux Native with a
ClassCastException involving ArrayBuffer$$Lambda$1 and IterableOnce. The original
failed-job log,exact seed and40-step counterexample are frozen here. All7 other
CI jobs passed. Underlying cause remains unresolved; no engine fix is claimed.

The exact program now runs as a regression, and a separate inherited suite
replays the failing ScalaCheck seed for300 cases. The original independent model
and randomized300-case property remain unchanged. The first underlying exception
prints its stack before shrinking. On macOS both suites pass on JVM and Native:
6 cases each. This does not reproduce or repair the Linux failure.

The dedicated alpha workflow predeclares three full Native-suite attempts on
Ubuntu,retaining all logs/outcomes and failing the job if any attempt fails.
It is a diagnostic,not a substitute for the required main CI matrix. The original
workflow and its acceptance limits are unchanged. Artifacts use the official
[upload-artifact mechanism](https://docs.github.com/en/actions/tutorials/store-and-share-data)
with a30-day diagnostic retention; failures must be copied into the repository
receipt before relying on them beyond that window. Do not retry-until-green.
