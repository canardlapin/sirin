# Per-window render refusal and explicit recovery

A failed current render must not terminate every application window. Preserve the
underlying application/document state and the actual renderer refusal; do not
increase budgets, substitute a different renderer, silently shorten source text
or present an old frame as current content. This implements recovery from a
refused scene, not qualification of that scene's rendering.

Each mounted Window owns a small render-status cell, a normal content holder and
a recovery holder. Both holders remain mounted under the same window lifetime;
only the selected holder is exposed to host capture, input and semantics. Failure
reporting targets a live mounted instance transactionally. It cannot target a new
window reusing a source key, and rollback/disposal retain the existing laws.
Retry is an ordinary window-owned command which clears status. Close invokes the
same close guard/dismissal path as an OS close request. A kept-open decision stays
visible. Window.renderFailureContent supplies optional application recovery
controls, allowing dirty-close/quit confirmations without rendering failed data.

A bounded immutable WindowRenderFailure contains the failed generation, error
category/detail preview and explicit counts of unapplied queued input and cancelled
composition. Text previews never introduce a split surrogate. Raw exception
objects and application trees do not enter this value. Details are available to
developer diagnostics; the default recovery panel uses controlled short text.
Only eight records per mounted window are retained, with no old frames or owners.

The desktop peer accepts failures only for its current coordinator generation.
It keeps distinct normal/recovery input controllers, preserving normal field IDs,
committed drafts and undo while the retained normal holder is unavailable. Entering
recovery cancels provisional composition with visible acknowledgement, rejects
queued geometry-dependent input explicitly with a receipt/count, invalidates old
presentation/input geometry and releases its frame. These facts cannot be replayed
against the recovery buttons. Subsequent current recovery frames use the ordinary
same-budget renderer and accepted accessibility path. Other peers keep operating.

Retry requests the current normal model; no failed scene retries in the background.
A failed application recovery view escalates once to the minimal built-in panel.
If that panel also fails, suppress automatic submissions and expose an unavailable
native title while OS close and application menus remain available. Fatal runtime
errors are not converted to ordinary scene refusals. Existing device-loss recovery
remains a distinct bounded path. No Scala runs in native callbacks.

The workbench supplies a data-independent confirmation panel so a rendering
failure does not hide dirty-close/quit decisions. It retains revision authority
and explicitly indicates when edits invalidate a prompt. Actual native input,
menus, screen-reader, IME and lifecycle acceptance still need client evidence.
The original unsupported-emoji paragraph refusal must remain a failing render
control and gain a successful explicit-recovery successor, not become a rendering
pass. Large-surface/complex-paragraph rendering and all six alpha gates remain
open until independently established.


Automatic/OS close and application quit cannot dismiss a recovery view with
unreviewed unapplied-input or cancelled-composition receipts. The explicit
"Acknowledge input and close" command acknowledges that receipt and still invokes
the application's ordinary dirty-close guard. Explicit Retry also acknowledges
the receipt before returning to current content. This prevents a queued quit from
silently dropping keys whose publication was held behind failed geometry.
