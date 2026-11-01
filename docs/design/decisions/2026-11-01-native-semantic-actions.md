# Native semantic actions

The public `SemanticAction` vocabulary already includes increment, decrement,
dismissal and named custom actions. The macOS bridge currently transports only
activation, focus and text edits. This change completes transport for the four
additional action kinds without changing kernel dispatch or focus policy.

- Increment and decrement map to AccessKit's corresponding actions. AccessKit
  C 0.23.1 has no general dismiss action; dismissal is a custom action named
  `Dismiss`, kept distinct from a user-declared `Custom("Dismiss")`.
- Each accepted semantic part owns its action declarations. Native requests
  queue that part's stable ID, presentation revision and copied action name.
  The owner revalidates revision, visibility, enabled state and the exact
  declaration before invoking the existing public element action handler.
- The pointer-only ABI becomes version 3 and adds one action-declaration entry
  point, taking one batch of kind/length arrays and concatenated UTF-8 names
  per semantic part. Arrays cross in bulk, and invalid batches leave the
  pending node unchanged. JVM and Scala Native reach the same function. Native callbacks only
  enqueue copied facts; they never enter Scala.
- Each part may register at most 32 additional actions, with custom names of
  1–256 UTF-8 bytes and no NUL. Duplicates and invalid declarations are refused.
  This bounds native snapshot storage; it does not raise existing node, field
  or queued-request limits. Declaration order is deterministic for each frame.
- Declaration data is copied into the pending tree and becomes visible only
  on successful publication. Retiring a part removes its action address. A
  queued request from an older revision is rejected by the owner.
  Custom action IDs remain stable for an unchanged declaration on the same
  part and are never reused after retirement. Reordering declarations or
  replacing a name cannot reinterpret an old client request as a different action.

Semantic relationships, collection positions and `NodeRef.focusPart` remain
separate work. Ordinary focus behavior is unchanged. Real VoiceOver navigation
and action invocation still need client acceptance; native queue tests do not
establish that outcome.
