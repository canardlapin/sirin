#include "../appearance.h"
#include <SDL3/SDL.h>
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

int sirin_desktop_app_open(void **out, int32_t *info, char *error);
int sirin_desktop_app_close(void *application);

static void *query_wrong_thread(void *application) {
  assert(sirin_desktop_system_theme(application) == -1);
  return NULL;
}

int main(void) {
  assert(sirin_desktop_system_theme(NULL) == -1);
  void *application = NULL;
  int32_t info[2] = {0};
  char error[512] = {0};
  if (sirin_desktop_app_open(&application, info, error)) {
    fprintf(stderr, "Application context failed: %s\n", error);
    return 1;
  }
  int theme = sirin_desktop_system_theme(application);
  assert(theme >= 0 && theme <= 2);
  assert(theme == (int)SDL_GetSystemTheme());
  pthread_t worker;
  assert(pthread_create(&worker, NULL, query_wrong_thread, application) == 0);
  assert(pthread_join(worker, NULL) == 0);
  assert(sirin_desktop_app_close(application) == 0);
  assert(sirin_desktop_system_theme(application) == -1);
  printf("System theme=%d; missing, wrong-thread and retired owners rejected; no window created.\n", theme);
  return 0;
}
