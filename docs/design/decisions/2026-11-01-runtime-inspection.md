# Bounded runtime inspection

The developer inspector starts with an owner-thread snapshot of committed runtime
metadata. Snapshot capture is allowed only outside transactions and read-only
callbacks. It neither attaches new signal roots nor evaluates derivations,
equalities, user formatters, work or completion queues. In particular, scheduler
inspection must not call `tickets`, which collects worker results and submits work.

Snapshots copy scope identities and names, signal dependency IDs, cell revisions,
evaluation counts, last committed change causes, command running/refusal state,
work-ticket flags and recent transaction causes. Signal IDs use a separate
monotonic diagnostic counter; they never participate in component identity or
kernel scheduling. Existing node copies preserve their diagnostic ID. A removed
node has no retained registry entry. Command metadata is registered transactionally
and removed on committed owner retirement or runtime close.

Every output collection and text preview is capped. A snapshot reports omitted
rows and dependency edges explicitly. It contains only copied strings, numbers,
booleans, enums and immutable collections of those values, never scopes, signals,
callbacks, application payloads or exception objects. Values are redacted by
default. Opt-in previews support only built-in scalar values and strings; an
arbitrary application's `toString` is never called. Refusal text and diagnostic
names are bounded strings. Failure details use the exception class, not application
exception formatting. Successful runtime behavior and command policy do not change.

Last-change causes identify committed value changes, not every invalidation or
attempted write. A failed or refused transaction does not become a node's committed
change cause. Historical causes can age out of the bounded trace ring; missing
parents are explicitly outside retained history. Full per-phase frame causality,
render/cache inspection and a visible browser remain later inspector work. This
snapshot API alone does not complete Alpha5.
