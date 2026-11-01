// Hidden-window integration test of the real SDL/Cocoa field editor; no synthetic keys or Scala callbacks.
#import <Cocoa/Cocoa.h>
#include "../text_input_owner.h"
#include <stdio.h>
#include <string.h>

static NSWindow *native_window(SDL_Window *window) {
  return (__bridge NSWindow *)SDL_GetPointerProperty(
      SDL_GetWindowProperties(window), SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, NULL);
}

static bool client_attached(SDL_Window *window) {
  NSWindow *native = native_window(window);
  id client = native.firstResponder;
  return [client conformsToProtocol:@protocol(NSTextInputClient)] &&
      [client isKindOfClass:NSView.class] && [(NSView *)client window] == native;
}

int main(int argc, char **argv) {
  @autoreleasepool {
    bool legacy = argc == 2 && strcmp(argv[1], "--legacy-order") == 0;
    if (!SDL_Init(SDL_INIT_VIDEO)) {
      fprintf(stderr, "SDL video initialization failed: %s\n", SDL_GetError());
      return 1;
    }
    SDL_Window *first = SDL_CreateWindow("Hidden input A", 160, 80, SDL_WINDOW_HIDDEN);
    SDL_Window *second = SDL_CreateWindow("Hidden input B", 160, 80, SDL_WINDOW_HIDDEN);
    if (!first || !second) {
      fprintf(stderr, "Hidden window creation failed: %s\n", SDL_GetError());
      return 2;
    }
    SirinTextInputOwner owner = {0};
    bool passed = true;
    for (int turn = 0; turn < 4; turn++) {
      SDL_Window *old = turn % 2 == 0 ? first : second;
      SDL_Window *next = turn % 2 == 0 ? second : first;
      if (legacy) {
        passed = passed && SDL_StartTextInput(old) && client_attached(old);
        passed = passed && SDL_StartTextInput(next) && client_attached(next);
        passed = passed && SDL_StopTextInput(old);
      } else {
        passed = passed && sirin_text_input_start(&owner, old) && client_attached(old);
        passed = passed && sirin_text_input_start(&owner, next) && client_attached(next);
        id client = native_window(next).firstResponder;
        passed = passed && sirin_text_input_start(&owner, next) && native_window(next).firstResponder == client;
        passed = passed && !SDL_TextInputActive(old);
        passed = passed && sirin_text_input_stop(&owner, old);
      }
      bool attached = client_attached(next);
      printf("turn=%d active=%d attached=%d legacy=%d\n", turn, SDL_TextInputActive(next), attached, legacy);
      passed = passed && SDL_TextInputActive(next) && attached;
      if (!passed) break;
    }
    if (!legacy) {
      passed = passed && owner.window == first && sirin_text_input_stop(&owner, second);
      SDL_DestroyWindow(second);
      second = NULL;
      passed = passed && client_attached(first);
      SDL_Window *active = owner.window;
      passed = passed && active && sirin_text_input_stop(&owner, active);
      passed = passed && !owner.window && !SDL_TextInputActive(active) && !client_attached(active);
      passed = passed && sirin_text_input_start(&owner, first) && client_attached(first);
      passed = passed && sirin_text_input_stop(&owner, first) && !owner.window;
    }
    if (second) SDL_DestroyWindow(second);
    SDL_DestroyWindow(first);
    SDL_Quit();
    puts(passed ? "Text-input owner integration passed" : "Text-input owner integration FAILED");
    return passed ? 0 : 3;
  }
}
