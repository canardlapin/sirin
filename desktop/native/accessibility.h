#ifndef SIRIN_ACCESSIBILITY_H
#define SIRIN_ACCESSIBILITY_H
#include <stdint.h>
// Pointer-only ABI, version 2. IDs are positive, <= 2^48-1; parent 0 is the window.
// Kinds: 0 text input, 1 multiline input, 2 button, 3 checkbox, 4 dialog,
// 5 label, 6 group, 7 status, 8 alert, 9 list, 10 item, 11 form,
// 12 menu, 13 menu item, 14 toolbar, 15 canvas, 16 graphic node.
// Flags: focus=1, activate=2, disabled=4, focused=8, modal=16, invalid=32,
// busy=64, selected=128, selected-known=256, polite=512, assertive=1024.
// Limits: 1024 total semantic nodes, 64 legacy/rich fields, 64 queued actions.
// checked: -1 absent, 0 false, 1 true. Bounds are x,y,width,height in logical pixels.
int sirin_desktop_ax_abi(void);
int sirin_desktop_ax_begin(void *window, int64_t revision);
int sirin_desktop_ax_field(void *window, int64_t id, int kind, const char *label,
    const char *value, const char *help, int start, int length, int focused, int enabled,
    const double *bounds, const double *spans, int count);
int sirin_desktop_ax_node(void *window, int64_t id, int64_t parent, int kind,
    const char *label, const char *value, const char *help, int flags, int checked, const double *bounds);
int sirin_desktop_ax_link(void *window, int64_t id, int64_t parent, int flags);
int sirin_desktop_ax_publish(void *window);
int sirin_desktop_ax_poll(void *window, int64_t *facts, char *text, int capacity);
#endif
