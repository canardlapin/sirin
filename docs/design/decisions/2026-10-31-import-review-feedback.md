# Import review validity and operation feedback

Real-window recovery checks found two misleading states: a completed copy still
said Saving a copy, and import review errors appeared only in the main window.
They also found that editing a reviewed source left Start import enabled for the
old source, which the import view did not identify.

- Clear the transient copy notice after successful creation. The new editor's
  Saved status is the completion feedback; the original draft stays retained.
- Display review errors and refusals beside Source directory in the import
  window, and show a reviewing message while work is running. Global workspace
  notices are not the import window's error channel.
- A reviewed plan records both the source's revision and a monotonic review
  attempt number. Start is enabled only while both still match. Editing away
  and back, or beginning another review, revokes the earlier permission. A
  failed review cannot reactivate an older plan. Checking only source text was
  rejected because it cannot distinguish revisions or failed review attempts.
- The import view identifies the reviewed source and explains a stale review.
  A started import continues against its frozen plan; later field edits do not
  redirect it. Existing per-file stamp checks, no-overwrite publication,
  cancellation, complete receipts and one-shot run ownership remain intact.
- Status and recent receipts use plain text instead of enum/case-class dumps.
  Counts, failures and abandoned queued receipts remain explicit; raw receipt
  identities and stamps remain in the model for verification.

- Keep the source, Review and Open workbench controls outside the scrolling
  review/receipt body. With the entire window scrolled, the source's NodeRef
  could be clipped away and the Import command could not focus/raise an existing
  window. Keeping the command's focus target visible preserves window navigation
  through the public focus interface; adding a host-private raise hook was rejected.

These are application decisions through public signals, commands and views.
They do not change transaction, scheduling, lifetime or command-policy laws.
