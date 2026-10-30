# Application-owned native host: implementation and headless checkpoint

The public Application.run entry now selects one ApplicationPlatform provider.
The JVM development provider checks the process main thread before application
construction, mounts the public Windows model and captures content per window.
The new native application context owns SDL across zero or more windows, routes
copied facts to per-window FIFOs, and rejects capacity failures explicitly.
16 native peers, 64 queued facts per peer, 65536 bytes per text fact and 1 MiB
shared queued text are explicit bounds. Retired native IDs are counted rather
than routed to another peer. Legacy standalone NativeWindow callers use an
implicit context. A failed open releases only its own reservation/resources.

The host shares one workspace runtime/worker pool across windows. Each peer uses
the existing editor controller, frame coordinator, viewport feedback and render
service. Scope close hides the peer immediately and requests render retirement
without joining on the event loop; native cleanup follows retirement. Application
wakeups remain available with no windows. Explicit quit pumps workspace work
before owner disposal. Refused/failed close transactions retain the model;
failures are recorded in a bounded diagnostic list. Asynchronous dirty-close
confirmation and native menu/dialog presentation remain unfinished.

**351 JVM + 351 Native + 54 headless desktop cases pass**, plus authoring
compilation and packaging. All exact tested source files and hashes are retained.
Only the package recipe and test receipt-directory prefix changed afterward:
the first image embedded an absolute diagnostic output path and was refused by
the unchanged portability verifier. Its config, source, and refusal remain here.
The successor uses the OS temporary directory, reruns the affected eight desktop
cases and packaging, and passes the unchanged native closure/font/deep strict
signature verifier. It loads the dylib without calling any UI function. The
portable-image and launch-candidate records identify the exact prepared app.
Standalone C queue lifetime/order/budget checks passed with address and undefined
behavior sanitizers; the original observed command/output is recorded in summary.

The preceding window-model checkpoint b320e8a is on main and all eight jobs of
its new main CI run 40986314862 pass. Those results do not test this new native
host generation. A new headless macOS/JDK25 CI job now builds the combined pinned
native dependencies, tests launch/editor/ABI/retirement policy, packages this app
and verifies its closure without assuming a hardware GPU or launching a UI.
The full local Metal cases remain separate from that CI job.

The prepared app is desktop/target/package-application/SirinAlpha.app. Its main
uses public Application.run/Window/Windows/Scope/Command/TextField APIs. A private
acceptance observer supplies test input and ownership observations; it is not
an application-authoring dependency. The planned scenario covers two views,
shared editing through the native text client, a failed partial peer open,
closing a view while a workspace save runs, zero-window application lifetime,
background reopening, title updates and quit. **This scenario has not run.**
Required action-time confirmation for this new executable is pending. The
previous approval named a different SirinEdit.app. No CLI launch substitutes for
that confirmation. No actual IME candidate UI, VoiceOver, native dragging,
Scala Native UI backend or shipping qualification is inferred from these checks.

Native menus are explicitly refused by the current host rather than silently
dropped. Menus/shortcuts/dialogs, complete persistent workbench, remaining controls,
diagram editor, inspector, starter/independent authoring and all alpha acceptance
remain in the six-part programme. The original GC event gate is unrun: a fresh
nonqualifying screen found 65.71/66.60% idle against 80%; no process was stopped.

Run python3 -B docs/evidence/application-alpha/native-application/verify.py for
frozen receipt integrity. Add --sources to require the current implementation
sources to match. Integrity replay does not execute the UI or qualify alpha.
