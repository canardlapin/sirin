# Packaged macOS observations — 2026-10-28

User approved the specific local app launch. CUA launched
`/tmp/sirin-responsive-20261028/desktop/target/package/Sirin.app`, bundle ID
`io.github.canardlapin.sirin.dev`, window **Sirin Workbench**. Actual window
screenshots were inspected in the session; no saved OS PNG is claimed. The initial
observations preceded the coupled graphics merge. The final package was launched
again at18:59–19:00 UTC after that merge, cleanup fixes and base-cache shortcut;
all steps below were repeated with the same visible results and clean process
exit. Package hashes are in `package.sha256`.

- Initial1200x776 screenshot (including56px title bar) displayed the workbench,
  records1–5, retained Document panel, and multilingual footer.
- Tab showed a focus ring on Activate. Enter then Space changed count0→2.
- Shift-Tab wrapped to Move document. Enter moved the Document right; its button
  retained the ring. This visibly verifies retained focus through outlet move.
- Pointer Activate changed count2→3. Toggle activation disabled it; clicking it
  and pressing Enter left count3, with its disabled label visible.
- Clicking the list and PageDown displayed records5–9. Wheel down advanced to
  records96–100, clamped at the last record.
- Dragging the bottom-right window corner changed the screenshot1280x856,
  rendering a1280x800 client area. The list relaid out to records95–100 and
  the document explanation fit on two lines.
- Native close terminated the process. A subsequent AX read returned
  procNotFound, then `cua.listApps` returned no Sirin app.

Only the window and native chrome are exposed in AX. Native control accessibility
is not implemented. These checks do not qualify text editing, IME, Native UI,
Windows/Linux, monitor-scale transitions, occlusion, or ScalaFX superiority.
