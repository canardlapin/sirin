#ifndef SIRIN_DESKTOP_APPEARANCE_H
#define SIRIN_DESKTOP_APPEARANCE_H

/* Main-thread application owner only. -1 invalid owner, 0 unknown, 1 light, 2 dark. */
int sirin_desktop_system_theme(void *application);

#endif
