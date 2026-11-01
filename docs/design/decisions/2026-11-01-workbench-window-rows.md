# Remaining-window activation and bounded document rows

- When the current native keyboard-focus window retires, activate the most
  recently active surviving window. If none has been active, use the last
  created survivor. Respect any window already activated by the OS or by the
  same structural update. Do not activate on background-window retirement,
  application deactivation, Quit, or when no windows remain. Preserve the
  survivor's logical control focus; choosing its first field was rejected
  because it would move a retained editor's caret/focus context.
- Add opt-in `Button.singleLine` for constrained labels. It keeps the full
  semantic name, paints one line, and reserves space for an ellipsis when the
  label exceeds its content width. Ordinary buttons continue to wrap. The
  document sidebar uses this option so its title and move controls fit the
  existing fixed row height: 40 lp for the title, 4 lp gap and 36 lp for move
  controls. Explicit heights also bound fallback-font metrics. Increasing every row's height was rejected because
  no fixed height accommodates all titles and it reduces the visible list.
- Implement both through existing host window activation and public control
  element/text interfaces. No private application-control hook, native ABI,
  transaction law, identity or scheduling policy is added.

- The packaged Arabic-title check exposed an existing native text-publication
  gap. Selection painting deliberately emits no rectangle for zero-advance
  graphemes, but accessibility still requires their source ranges. Use the
  grapheme's downstream caret geometry for those ranges, through the existing
  publication format and clipping path. Keep native contiguous-range validation
  and painted-selection semantics unchanged. Dropping the character, accepting
  incomplete native text, or suppressing the publication exception was rejected
  because each would hide a broken text snapshot.
