# Application-owned native host

Additive application-alpha design under VISION sections 5.2, 5.3, 5.4 and 6.
The six-part alpha plan remains the full acceptance scope. No kernel transaction,
identity, command or worker policy changes are introduced by this host boundary.

One process-main-thread native application owns SDL initialization and the global
event pump. It outlives zero or more native windows. Explicit application close
requires every peer retired; legacy standalone NativeWindow callers receive an
implicit context retired with its last window. A failed peer open releases only
that peer, never another window or an explicit application context.

The native context supports at most 16 live peers. Each has a 64-fact FIFO; all
queued text together is bounded by 1 MiB and each text fact by 65536 UTF-8 bytes.
Facts copy SDL text immediately, retain timestamps/modifiers and target only the
registered native window. Capacity/allocation failures return explicit errors;
unknown retired-window facts are counted, never redirected to another peer.
Application wakeups remain available with zero windows. Window wakeups and
AccessKit callbacks contain native facts only; no managed upcalls are introduced.
Window/queue storage retires before SDL application shutdown. Frame/render owners
continue to use the existing owned render service and reference-counted GPU data.

The public core launch entry discovers one ApplicationPlatform service provider.
No provider or ambiguous providers are explicit configuration failures. The
JVM development backend checks the process main thread before constructing UI
state. The backend mounts Windows through existing HeadlessRuntime semantics,
captures each content root independently and maps mounted instance IDs to peers.
Service discovery is backend selection, not an alternate lifetime model. A Native
backend must supply the same SPI/host contracts and receives its own qualification;
a JVM provider cannot qualify a Scala Native application launch.

Close requests use the existing transaction/KeepOpen model. An application quit
request offers close to its windows; KeepOpen leaves the application alive.
After explicit quit and no remaining windows, queued workspace work is pumped to
termination before disposing the application owner. Closing the last window by
itself keeps the application context and workspace jobs alive. Native menus,
shortcuts, asynchronous dialog presentation and the complete persistent workbench
remain required application work and need real host evidence.

Native wait operations are blocking boundaries; a Scala Native adapter must mark
application wait/window wait @blocking. The JDK development adapter uses FFM
without upcalls. Native toolkit callbacks never execute Scala on foreign threads.
Tests of queues/capture and headless FFM checks do not validate real multiwindow
input, focus, IME, menus or accessibility. Preserve separate launch/runtime receipts.
