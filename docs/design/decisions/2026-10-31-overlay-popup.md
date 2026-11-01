# Overlay and Popup

`Overlay` and `Popup` were the last control placeholders. Their public API was
already fixed in `core/src/main/scala/sirin/View.scala`, and A-0 corpus
program 4 (`examples/authoring/sirin/examples/PopupFocus.scala`) already used
it. This note records how they are built. The owner asked for these decisions
to be made on engineering judgment and recorded so they can be revisited. Each
one is numbered, with the alternative that was set aside.

## Overlay

`Overlay(base, layers*)` is a public `Layout`:
- the base is measured with the offered constraints;
- each layer is measured tight to the base's size;
- everything is placed at the origin, in order, so later layers draw and hit
  above earlier ones.

It needs no new capability.

## Popup: structure

**D1. Ownership by declaration, placement by the host.** A `Popup` is mounted
where it is declared, under its originator's scope. It lives, keeps its state
and is disposed with its originator. VISION §5.2 says "placed elsewhere visually
but still belongs to its originator", and this is that rule. Its content is
mounted only while `open` is true. Closing disposes the content's own state, so
anything that must survive closing belongs in the originator. Program 4 keeps
its draft there.
*Set aside:* keeping closed content mounted and hidden. That leaks lifetimes,
and it makes focus and drafts behave differently on every reopen.

**D2. A window overlay layer in the host, like windows and outlets.** Window
capture lifts each open popup out of the flow, so it takes no space where it is
declared. The window's tree becomes `Content.Layered(base, popups)`. The host:
- lays out the base;
- finds each popup's anchor by its `NodeRef` in the placed tree;
- measures the popup's content loosely within the window;
- places the popup above everything.

The content itself uses only public controls. Only the layer, like the window
and docking, is host machinery.
*Set aside:* a public "anchor geometry" API so popups could be ordinary layouts.
Placement must see the whole window, which no layout policy can. Dismissal
and focus are window-level input rules either way.

**D3. Placement.** `Below`, `Above`, `Start` and `End` place the popup 4 units
outside the anchor's edge, aligned to the anchor's leading edge. If the popup
does not fit there and fits on the opposite side, it flips. It is then clamped
inside the window, 8 units from its edges when it fits. `Over` places it at the anchor's origin. A popup whose
anchor is not presented, because it is unmounted, hidden or unplaced, is not
shown. It then takes no input, even if it is modal, and it stays open, so it
reappears when its anchor does.
*Set aside:* falling back to the window's center. A popup showing far from
anything the user touched is confusing, and an unplaced anchor is almost always
transient.

**D4. Nesting.** A popup declared inside another popup stacks above it. Sibling
popups stack in document order. Dismissal and Escape go to the topmost popup
only.
*Set aside:* stacking in the order popups were opened. That makes stacking
depend on input history rather than on structure.

## Popup: input

**D5. Escape** goes first to the focused element. If the element does not
handle it, and the topmost popup allows Escape dismissal, the popup is offered
`DismissReason.Escape`. Escape then ends there whether or not the dismissal is
vetoed. This sits after focused editing keys and before local and window
shortcuts, as the VISION §5.3 precedence requires.

**D6. Outside press.** A primary press outside the topmost popup is consumed. It
never reaches what lies beneath:
- if the popup allows outside-press dismissal, it is offered
  `DismissReason.OutsidePress`;
- a non-modal popup that does not allow it lets the press through instead.

Consuming the press means the click that closes a popup never also activates
something beneath it. In particular, pressing the anchor button closes the
popup rather than closing and reopening it. macOS menus and popovers work the
same way.
*Set aside:* click-through dismissal. Users find it surprising and it is
destructive.

**D7. Window deactivation** offers `DismissReason.WindowDeactivated` to every
open non-modal popup, topmost first, so transient menus and pickers go away.
Modal popups stay. `onDismiss` can veto either way.

**D8. Dismissal is transactional.** A dismissal runs `onDismiss(reason)` and,
if it allows, writes `false` to `open`, in one transaction. A veto changes
nothing.

**D9. Modality.** While a modal popup is presented:
- pointer input beneath it does not reach the base;
- Tab stays inside it;
- the base's elements receive no keys or text input. Input eligibility follows
  the presented modal layer even when the popup has no focusable content or a
  `Lost` veto prevents moving focus. Stored focus may remain beneath the modal,
  but it receives no input until the modal closes. Programmatic and assistive
  focus/action requests beneath it are refused as well.

Window menu commands and their shortcuts still work, since they are window
commands, not base content. A modal popup does not dim the base. That is left
to the theme.

**D10. Tab is contained.** While focus is inside a popup, Tab and Shift-Tab cycle
within it, for modal and non-modal popups alike. Escape is the exit. With focus
in the base, Tab reaches non-modal popups after the base content.
*Set aside:* letting Tab leave a non-modal popup. It strands focus behind an
open popover.

## Popup: focus

**D11. Opening.** When a popup is first presented, the host remembers the
focused view. It then focuses `initialFocus`, or the popup's first focusable
view if none is given. The focus moves through the ordinary element focus
events, so a `Lost` veto is honored.

**D12. Restoring.** When a popup is no longer presented, focus is restored, but
only if focus was inside it or nowhere. A user who clicked elsewhere keeps
their new focus. The candidates are tried in this order:
1. the `restoreFocus` target: the remembered view for `Previous`, or the ref
   for `To`, if it is still presented and focusable;
2. the popup's anchor, or the first focusable view inside it;
3. nothing, which leaves the window without focus.

`Nowhere` skips all of these. This changes VISION §5.3, which said "nearest
eligible ownership ancestor". The anchor is the originator's own view, and it
is what the user acted on, so it is the better fallback. The ancestor chain
mostly reaches non-focusable layouts. VISION is updated.

**D13. Shortcut scope.** A focused view inside a popup bubbles local shortcuts
through the popup's content and then through the anchor's ancestry, because the
popup belongs to its originator. In program 4, the header shortcut therefore
still applies while the rename popup has focus.
For a nested popup, this follows the anchor through each earlier parent layer
back to the base. The innermost matching binding still wins. If stored focus is
blocked by a modal layer, it supplies no local shortcut ancestry; window and
application shortcuts remain available under D9.

## Decisions made during implementation

**D15. TextField lets Escape bubble.** Outside an IME composition, Escape was
consumed by `TextField` and did nothing. Now it reaches local shortcuts and
popups, so Escape in the rename field dismisses the rename popup, and the
corpus pattern `.onKey(Chord(Key.Escape))(cancel)` on a field works. Escape
during a composition still cancels the composition only.

**D16. A popup has a surface.** Each popup in the layer is painted on a raised
surface with a 1-unit border and 6-unit corners. Without one, popup content was
transparent over the base, and the first render showed overlapping labels. The
development theme gains a dark `SurfaceRaised` color; before, the role fell
through to a light default.

**D17. Default width.** A popup's content is measured at most 400 wide, within
the window's margins, unless its content declares a `.width`. Rows that grow,
such as the right-aligned button row in program 4, otherwise stretch a popup to
the full window.

**D18. A popup is opaque to input.** A press anywhere on a popup's surface,
padding included, never reaches what lies beneath it. The wheel likewise. This
is `InteractionSnapshot.receives`.

## Accessibility

**D14.** The host now captures `.semantics(...)` on any view as a container node
with that view's bounds. It was refused before. Program 4 marks its popup
content as `Role.Dialog`. A container node's instance is a negative number
derived from its node identity, so it can never equal an element instance, and
it has no actions. Native transport still publishes only buttons and text
inputs, so the dialog reaches headless semantic snapshots, not VoiceOver. A
relation between the dialog and its anchor is not modeled yet.

## Proof

Program 4 runs end to end on the desktop host in `PopupSeamSuite`. It checks:
- opening, initial focus, placement and flipping;
- Escape, outside press and veto;
- restoring focus to the button or to the header field;
- Tab containment, modality, shortcut scope and the locked-layer effect;
- disposal with the originator.

`OverlayLayout` is covered in the host suite.
