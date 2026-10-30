# Persistent document model and real file storage checkpoint

`DocumentEditor` uses only public Scope/Cell/Command/View contracts. It owns
independent title/body revisions, exact committed save snapshots, validation,
shared save refusal/error state, dirty status and a dirty-close prompt signal.
The application's document scope owns it; removing its window does not cancel
its saves. Disposing the document scope retires pending work.

`FileDocumentRepository` supplies real JVM I/O via Work. Describing an operation
performs no filesystem I/O. Loads contain immutable content digests; a session's
queued saves follow its own successful disk baseline. A separately opened stale
session refuses after another write or deletion. Drafts and on-disk data survive
refused writes. Reopening explicitly obtains a new baseline.

Files have a versioned bounded binary envelope, strict UTF-8, embedded identity,
and no trailing data. Data/lock file operations refuse symlinks; valid IDs cannot
escape the configured directory. Creation publishes without replacing an existing
name. Saves flush a temporary file, recheck the digest and atomically replace
under an advisory lock. Cancellation after writing a temporary file removes it
without publishing a document. Collection/file/directory bounds fail explicitly;
there is no non-atomic overwrite fallback.

Full checks pass **369 JVM + 369 Native + 69 desktop cases**, plus authoring
compilation. Six new shared cases cover queued field revisions, invalid editable
drafts, failed save/retry, view versus document ownership, snapshot refusal and
validation. Eight real filesystem cases cover no-I/O descriptions and exact
Unicode/CRLF reopen in a fresh repository, revisions 17 and 18 persistence through the
actual model/scheduler, stale-session conflicts and explicit reopen, deletion/
symlink refusal, malformed UTF-8/version/size/identity/trailing data, cancellation
cleanup, cooperating locks and refusing document 513 while retaining all 512.

The first storage test run passed five filesystem cases, then its cancellation
fixture used MUnit intercept, which excludes InterruptedException. The escaped
exception interrupted the test executor and caused two following filesystem
cases to fail. The successor explicitly catches the contract's cancellation
exception and retains the same cleanup assertions. The full failed output and
source snapshots are preserved. Initial compile observations and sources are
also preserved; they are labeled summaries because full stdout was not captured.
No production exception handling, test budget or threshold was loosened.

**This is not the complete visible workbench.** Search/selection, retained draft
collection views, import/cancel, conflict/dirty-close dialogs, native file dialogs,
programmatic quit and actual packaged reopen remain required. This checkpoint
has not launched UI or qualified a Scala Native filesystem/application adapter.
Advisory locks coordinate cooperating writers; digest checks are not a filesystem
compare-and-swap against uncooperative writers. Power-loss/directory durability
and other platforms remain unqualified. All six alpha deliverables stay open.

The preceding menu/host checkpoint 0c2ee30 is on main with all nine CI jobs green;
its exact result is in menu-main-ci.json. That result is separate from CI for
this successor. The earlier frozen SirinAlpha.app candidate remains unchanged
and awaits its existing launch confirmation.

Reconcile with `python3 -B docs/evidence/application-alpha/documents/verify.py --sources`.
