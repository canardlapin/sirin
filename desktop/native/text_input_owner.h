#ifndef SIRIN_TEXT_INPUT_OWNER_H
#define SIRIN_TEXT_INPUT_OWNER_H

#include <SDL3/SDL.h>

// Cocoa has one process-wide field editor although SDL keeps per-window active flags.
// Confined to the application owner thread. This structure never crosses the ABI.
typedef struct SirinTextInputOwner {
  SDL_Window *window;
} SirinTextInputOwner;

static inline bool sirin_text_input_stop(SirinTextInputOwner *input, SDL_Window *window) {
  if (input->window != window) return true;
  if (!SDL_StopTextInput(window)) return false;
  input->window = NULL;
  return true;
}

static inline bool sirin_text_input_start(SirinTextInputOwner *input, SDL_Window *window) {
  // Stop the prior owner before starting the next. Its later focus-loss fact is a no-op.
  if (input->window && input->window != window && !sirin_text_input_stop(input, input->window)) return false;
  if (!SDL_TextInputActive(window) && !SDL_StartTextInput(window)) return false;
  input->window = window;
  return true;
}

#endif
