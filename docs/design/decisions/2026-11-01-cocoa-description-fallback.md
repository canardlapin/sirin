# Cocoa description fallback

A hidden-window client test against the pinned native library shows that
`labelledBy` produces a Cocoa title, but `describedBy` alone produces no Cocoa
Help. Publishing the relationship is insufficient for the supported platform.

The macOS bridge keeps `describedBy` and supplies a direct native description
when the source has no nonempty explicit description. It reads the target from
the same accepted immutable tree: a Label's nonempty value, otherwise its name;
for other roles, the target's name. This follows the direct textual-target rule
used for native labels, without traversing arbitrary relationship graphs or
concatenating potentially unbounded subtrees. Authors use a named semantic node
or Label for descriptive text. Missing or textless targets produce no fallback.
An explicit source description wins. Every publication recomputes the fallback,
so removing the relationship or changing the target cannot retain stale help.

Waiting for an upstream adapter change was rejected because declared help must
be available in the current macOS host. Adding a separate application-only
accessibility implementation was rejected: AccessKit still owns all native
objects and action callbacks. There is no ABI, runtime policy or dependency-pin
change. The existing copied tree and string bounds apply.

Cocoa Focus and Press are distinct actions. A TextField supports the former;
it must not gain button activation merely to accommodate an automation tool's
generic click. A hidden-window native test verifies the focus setter, queued
identity and accepted focus publication. This does not qualify real VoiceOver,
window activation or physical input.
