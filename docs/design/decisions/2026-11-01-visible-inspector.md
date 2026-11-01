# Visible developer inspector

The macOS development host enables the inspector only with `-Dsirin.inspector=true`.
It adds an application-owned Developer menu and an ordinary keyed tool window.
The window uses public controls, search, explicit capture, bounded paging and
links between copied command/signal/cause records. It does not continuously sample
or mutate application state. Scalar previews require the inspector's explicit
checkbox; they remain bounded and never call application formatters. Display rows
escape malformed UTF-16, control characters and literal backslashes before text
shaping. Labels and details are capped at 256 and 4,096 characters respectively;
truncated detail is marked. Repeated graph inputs yield one navigation link per
target, preserving keyed row identity.

A private Windows composition helper preserves the application's quit handler and
appends a tool's menu and window view. Tools cannot supply a second quit handler.
The tool's close removes only its own window key and clears its captured data.
Opening/refreshing queues one coalesced capture after successful outer publication;
the owner loop captures outside transactions/read-only callbacks. Closing before
capture cancels that request. Ordinary application window, focus and quit rules
remain in force; the tool does not create a separate native lifecycle.

Render workers put bounded cache summaries in immutable frame results. The owner
copies numeric generation, queue/render duration, dimensions, transfer counts,
input backlog and failure receipts into inspector data. Requested, rendered and
presented generations remain distinct. Cache statistics describe the rendered
result, not a later live cache. No inspector code touches worker-owned native
objects. No per-frame file writes or idle polling are introduced.

The runtime graph includes the inspector's clearly named scope. Snapshots contain
only copied metadata, so inspecting the inspector cannot create a recursive
payload history. One snapshot is retained; the next replaces it, and close clears
it. Output budgets and omission counts remain explicit. Full per-phase causality,
actual window/assistive-client acceptance and latency qualification stay open.
