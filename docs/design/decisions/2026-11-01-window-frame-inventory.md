# Frame inventories for multiple windows

The retained-GPU candidate limited the entire process to three complete frame
packets. That covered one renderer's displayed frame, unpolled result and frame
being constructed. It does not cover two independent window renderers: both can
hold a displayed frame and prepare replacements concurrently. A two-device
regression fails on the fourth packet with only 2 KiB of shared output textures;
the real workbench reproduced the same native frame-open rejection on handoff.

Keep the three-packet bound, but apply it to the owning batch device/generation.
Also retain a process-wide ceiling of 48 packets, derived from the existing
16-window host capacity times three. Retiring device generations count toward
that total. Packets only reference immutable tile textures; the independent
16 MiB process-wide exported-output cap remains unchanged, as do per-cache
pixel/entry bounds, frame dimensions, coverage validation and device identity.
The new ceiling bounds packet metadata, not an additional texture allocation.

Reserve owner and process slots with atomic compare/exchange. Roll back the
owner reservation when the global bound refuses admission, and both when frame
allocation fails. Release the owner count before dropping tile references that
may free the device. No new native entry point, managed callback, blocking wait,
application Work policy or kernel scheduling rule is introduced. The private
shared batch-owner record holds the count; Native compiles that same record.
Inventory exhaustion receives native status 6, distinct from invalid geometry
or mixed-device status 4, so a future bound refusal can be diagnosed precisely.

Rejected alternatives:

- Raising only the process-wide number would let a single renderer retain too
  many frames and would conceal a mailbox/retirement leak.
- Serializing every window's rendering around three process-wide slots would
  couple independent windows and still fail when four displayed windows retain
  their packets.
- Releasing a displayed frame before its replacement is ready would weaken the
  retained presentation/lifecycle contract and induce unnecessary rebuilds.
- Increasing texture memory, pixel budgets or dimensions is unrelated to this
  packet-accounting failure and remains outside this repair.

Acceptance requires the original negative control, independent-device frame
retention, unchanged per-owner refusal and coverage checks, concurrent atomic
limit/rollback checks, cleanup to zero, shared JVM/Native suites, the Native
batch path and the originally failing packaged handoff sequence. This does not
claim arbitrary window counts at full resolution or resolve the separate large
window, P0.6, Native provider, IME or VoiceOver gates.
