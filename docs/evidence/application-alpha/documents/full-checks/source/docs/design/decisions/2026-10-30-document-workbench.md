# Persistent document workbench ownership and storage

Application code reuses Scope, Cell, Command and existing save policies. A
DocumentEditor belongs to an application/document scope, independently of any
Window rendering it. Closing a view does not dispose that scope or cancel its
save. Application shutdown/disposal retains the existing cancellation and no-late-
publication laws; an external write already committed cannot be undone.

Document IDs, validated data and content stamps are immutable shared values.
Repository operations return Work and do no I/O when merely described. The JVM
file repository supplies bounded versioned files, strict UTF-8, digest-associated
loads, atomic replacement, per-document advisory locks and explicit failures.
Opening a document returns a session with an immutable initial load. Its saves
serialize their disk baseline: queued revision 18 follows our own saved revision
17, while an external change conflicts. Reload opens a new session; inspecting a
file never silently replaces the old session's baseline or a user's draft.

The editor captures both committed field revisions in a save job. Completion
marks only those revisions saved; newer edits remain dirty. Queued saves use the
existing SnapshotAssociated/Serial command policy. Invalid drafts remain editable
and show validation; they cannot dispatch a save. Failed writes retain the draft
and surface the error. A dirty close requests confirmation.

File storage is an application-owned directory, not a general filesystem browser.
Files use a bounded versioned binary envelope and copied UTF-8 strings. Filenames
come from validated IDs; data/lock symlinks are refused. New documents publish
without replacing an existing name. Updates check the opened digest immediately
before atomic replacement under an advisory lock. This detects normal external
modification and coordinates cooperating repository instances; it is not a
filesystem compare-and-swap guarantee against a writer ignoring the lock. No
fallback to non-atomic overwrite is allowed. Directory durability across power
loss and other filesystem/platform behavior need their own qualification.

This implements storage and document editing components toward the full visible
workbench, not completion of that deliverable. Search/select/retained draft views,
import/cancel, conflict/dirty-close dialogs, native file dialogs, actual packaged
reopen and all six alpha acceptance requirements remain required.
