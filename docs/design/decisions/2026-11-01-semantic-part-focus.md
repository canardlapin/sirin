# Focus inside custom elements

`NodeRef.focusPart` uses the existing staged, rollback-aware focus intent queue.
The intent includes a part ID. After settling and presenting, the host resolves
it against a directly referenced focusable custom element and its exposed,
non-clipped semantic parts. Missing, ambiguous, hidden, retired or modal-excluded
targets do not redirect focus to another part. A reference on a layout retains
its existing first-descendant behavior for `focus()`, but does not acquire a
part namespace for `focusPart`. Parts belong to their custom element.

The interaction state owns an optional explicit part alongside the element's
focus. All exposed parts of a focusable custom element can receive native focus;
focus is a host operation, not activation or selection, and does not invoke the
element's semantic-action handler. The first part remains the native default
when the element has focus without an explicit part. `focusedPart` reports the
explicit part on directly attached references, and `None` otherwise. Ancestor
references continue reporting `containsFocus` without borrowing child part IDs.
Tab still traverses elements; individual-part keyboard navigation belongs to
the element. These choices avoid changing the established traversal policy or
silently treating selection as focus.

Changing explicit part within an element does not emit element Lost/Gained.
Moving to another element retains the existing focus-loss veto. Focus
events reach custom elements that declare activation as well as those
without activation; activation eligibility does not replace their focus hooks.
Ordinary reference focus, Tab and pointer focus clear the explicit part; a control may
then request its hit part in the same input turn. Publication validates part
survival against the next accepted geometry. A removed part clears to `None`
while the surviving element keeps focus. Popup restoration retains the previous
part if still exposed, otherwise restores the element. Deactivation clears the
published reference signals and retains logical part focus for reactivation.

Disabled elements retain existing programmatic-focus eligibility; native requests
still reject disabled targets. Focus intents and all four reference focus
signals publish transactionally. No foreign callback or new ABI entry point is
introduced; native actions already carry exact semantic node identity.
Part changes also advance the frame request so native focus is republished even
when no application signal depends on the part. Base drawing caches omit that
host-owned part field, just as they omit ordinary focus decoration; any
application-dependent drawing change still arrives through its normal tree.
The application host coalesces identical pending intents by their last occurrence,
preserving the order of final requests. Keeping the first duplicate would make
requests for part 2, then 1, then 2 finish on part 1.

The alternatives set aside were an application-owned selection cell pretending
to be focus, treating every part as a Tab stop, automatically picking another
part for an invalid explicit request, and widening part lookup to arbitrary
descendants. Each obscures either focus ownership or the target's identity.
