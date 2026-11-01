# Visible workbench decisions and editor allocation

The real-window checks found dirty-close and dirty-quit controls below the
viewport, and an editor constrained to half of an already narrow dock area.
This implements the workbench acceptance plan without changing kernel laws.

1. Decisions use public `Popup`, anchored to the window-sized content surface.
   The popup is modal, starts on Keep editing, traps Tab through the existing
   popup policy, and cancels on Escape. Outside presses leave it open.
   An inline scrolled confirmation was rejected because it hid the action
   required to continue. Prompt metadata and open state change atomically.
2. Previous focus is restored by the existing Popup policy. Forcing search
   focus on cancel was rejected because it loses the user's editing position.
   Revision-authority checks still require a new review if drafts changed.
3. A public `LayoutPolicy` gives the active retained editor all width beside
   a bounded document list. Moving the editor swaps it with the list, retaining
   the actual fields. Equal-width active and empty dock panes were rejected
   because the empty pane consumed the writing area. The body grows into the
   remaining window height, with Save always in the editor footer.
4. Less frequent document operations live in the native File menu. They are
   enabled only in the main workbench; extra document and import windows do
   not accidentally operate on its globally selected document. Save and Close
   view remain window-specific.
5. The popup border uses an outer rounded fill and an inset raised-surface
   fill, producing a one-pixel inner border. The earlier stroked rounded path
   exceeded the reference renderer's fixed per-sample budget at this popup
   size. Raising that budget was rejected. This changes paint only; it adds
   no ABI or private control API. Custom translucent theme colors compose
   over the outer fill, consistent with a raised surface over its border.

Native Dialog and Checkbox publication, actual VoiceOver, IME and physical
trackpad acceptance remain separate obligations. A visible popup with shared
semantics does not establish native accessibility.
