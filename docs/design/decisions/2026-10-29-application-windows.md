# Application alpha: keyed window lifetimes

This is an explicit additive design update under VISION sections 4 and 5.2.
The application-alpha programme remains the acceptance authority; this first
implementation does not qualify Application.run or the native host.

A Windows description is built once under the application/workspace owner.
Its private state, observations and component descriptions belong to a mounted
component scope. It uses the existing ForEach and Component reconciliation,
including call-site Equality and SchemaId evidence. No second keyed reconciler,
new transaction phase or foreign Scala callback is introduced.

Each row component builds one immutable Window description and owns the window's
local state. The Window wrapper reconciles its content and declares its live
title dependency. Title failure is represented as Value.Failed in the window
snapshot; content remains present. Source replacement and reorder preserve the
window scope, child identities, drafts and live Inputs. Source removal retires
that window without disposing the application, document or another window.

A native close request is an ordinary UiTxn, routed by a mounted instance ID.
Window.onClose supplies an optional pre-close action. An accepted action dismisses
the key from this Windows presentation, disposing its entire row component;
source data remains owned by the workspace. A refusal or defect rolls back the
handler's writes and dismissal together. Source removal in the handler is valid.
A stale instance cannot dispatch to a later window that reuses its source key.
Before calling the user's handler, the window captures its source key/revision
and prepares an immutable dismissal action. After the handler, the engine only
applies that action if the window scope is still available in the candidate.
Application disposal in onClose must not be rolled back by an automatic write
through that disposed owner. Preparation does not publish or mutate dismissal
state, so handler reads still see the mounted window.
Window.onCloseRequest additionally returns Allow or KeepOpen. KeepOpen commits
prompt/error state while retaining the window and all local state. A reference
application can open a dirty-close prompt, then use a separate document command
to save and remove the window's source key on confirmation. Thus displaying a
prompt does not require a refusal that would roll back the prompt itself. Native
dialog presentation and asynchronous save/confirmation wiring remain host work.

Dismissal records use an owned KeyedCell, not a plain Map mutated by a UI
handler. Its existing guarded key lookup/update methods enforce read-only key
functions/equality even when a user comparison catches the rejected write.
Projection comparisons run within the existing read-only Signal evaluation.
No new guard or transaction exception is added to the kernel.

Dismissed keys remain suppressed across value replacement/reorder. A committed
Removed delta after dismissal clears that key's tombstone when the existing
owned event driver delivers it. Remove/reinsert across revisions therefore
reopens with fresh state after event delivery. A net remove/reinsert within one
transaction obeys the existing collection delta law, rather than inventing a
second window generation. Dismissal records the source revision so delayed
Removed events from before that close cannot reopen a subsequently dismissed
window. Tombstones and observations retire with the Windows mount.

The mounted window snapshot identifies a content root for each native peer.
Generic single-window tree capture rejects a whole window forest; a host must
capture each window's content separately. Native peer creation, global SDL event
routing, main-thread enforcement, menus/shortcuts/dialogs, guarded application
quit, and a backend for Application.run remain required platform work. In
particular, the current C transport still rejects a second native window.
