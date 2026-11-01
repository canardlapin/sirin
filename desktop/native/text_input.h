#ifndef SIRIN_TEXT_INPUT_H
#define SIRIN_TEXT_INPUT_H

// Existing pointer-only text/focus ABI, shared by JVM FFM and the Scala Native input probe.
int sirin_desktop_text_input(void *window, int mode, const double *area);
int sirin_desktop_ime_control(void *window, const char *text, int start, int length, int commit);
int sirin_desktop_activate(void *window);
int sirin_desktop_keyboard_focus(void *window);

#endif
