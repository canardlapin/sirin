# Native text-input ownership across windows

The workbench can activate a retained editor in another window through public
NodeRef focus. Native focus-gain/loss facts may arrive after that request has
already started text input in the new window. The old per-window start/stop
calls permitted a late stop to remove the new window's platform text client.

SDL 3.4.18 keeps a per-window active flag but its Cocoa backend has one shared
field editor. `Cocoa_StopTextInput` removes that editor regardless of the window
argument. The failure was reproduced in a package trace and with hidden SDL
windows. See [SDL's pinned Cocoa implementation](https://raw.githubusercontent.com/libsdl-org/SDL/release-3.4.18/src/video/cocoa/SDL_cocoakeyboard.m)
and [its per-window state](https://raw.githubusercontent.com/libsdl-org/SDL/release-3.4.18/src/video/SDL_video.c).

- The native application owns one text-input client. Starting another window
  first stops the previous owner, then starts the new owner. A later stop from
  the old window does nothing. Repeated starts for the same owner retain its
  client, so caret/geometry updates do not reset composition.
- A background presentation cannot take ownership. Start requests are applied
  only to SDL's current keyboard-focus window. Late composition retirement from
  a different window cannot clear the current owner's composition.
- Closing the owner releases it before destroying its native window. Closing
  another window leaves the owner intact. A failed handoff is reported through
  the existing native result code, rather than retaining a false owner.
- Put the correction in the shared pointer-only shim. Changing dispatch order
  in the JVM alone was rejected because pointer-driven window changes can have
  either event order, and Scala Native must use the same native contract.

This changes platform-client ownership, not Sirin focus ownership, transaction
laws, command precedence, identity or scheduling. No ABI entry is added and no
Scala callback enters from Cocoa. Actual IME acceptance remains separate from
the field-editor ownership check.
