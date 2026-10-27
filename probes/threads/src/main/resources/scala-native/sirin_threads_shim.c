// SDL glue, watchdog, re-entry trampolines and cost probes for P0.6/P0.7.
// The mailbox, guard and foreign producers live in their own SDL-free files so
// that a pure-C ThreadSanitizer harness can compile them (probes/threads/tsan).
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>

#include <SDL3/SDL.h>

#ifdef __APPLE__
#include <mach/mach.h>
#endif

#include "sirin_foreign.h"
#include "sirin_guard.h"
#include "sirin_mailbox.h"

// ---------------------------------------------------------------------------
// Constants read from the SDL headers.

size_t sirin_threads_sdl_constants(uint64_t *out) {
  out[0] = SDL_EVENT_USER;
  out[1] = SDL_EVENT_LAST;
  out[2] = sizeof(SDL_Event);
  out[3] = SDL_WINDOW_HIDDEN;
  out[4] = SDL_INIT_VIDEO;
  out[5] = SDL_EVENT_QUIT;
  return 6;
}

// ---------------------------------------------------------------------------
// User events. data1/data2 carry two 64-bit values (arm64/x64: pointers are 64-bit).

static atomic_ullong sirin_push_failures = 0;

int sirin_push_user(uint32_t type, int32_t code, uint64_t a, uint64_t b) {
  SDL_Event e;
  memset(&e, 0, sizeof e);
  e.type = type;
  e.user.code = code;
  e.user.data1 = (void *)(uintptr_t)a;
  e.user.data2 = (void *)(uintptr_t)b;
  if (!SDL_PushEvent(&e)) {
    atomic_fetch_add(&sirin_push_failures, 1);
    return 0;
  }
  return 1;
}

uint64_t sirin_push_failure_count(void) { return atomic_load(&sirin_push_failures); }

// Returns the event type; out = {code, data1, data2} for user-range events.
uint32_t sirin_decode_user(const SDL_Event *e, uint64_t *out) {
  if (e->type >= SDL_EVENT_USER && e->type <= SDL_EVENT_LAST) {
    out[0] = (uint64_t)(int64_t)e->user.code;
    out[1] = (uint64_t)(uintptr_t)e->user.data1;
    out[2] = (uint64_t)(uintptr_t)e->user.data2;
  }
  return e->type;
}

// ---------------------------------------------------------------------------
// Mailbox wake functions.

// The SDL event type is carried in the context pointer itself, so a mailbox
// needs no wake allocation and a wake can never read freed memory.
static void sdl_wake(void *ctx) {
  sirin_push_user((uint32_t)(uintptr_t)ctx, 0, sirin_now_ns(), 0);
}

static void counting_wake(void *ctx) { atomic_fetch_add((atomic_ullong *)ctx, 1); }

sirin_mbox *sirin_mbox_create_sdl(uint32_t capacity, uint32_t max_consumers, uint32_t type) {
  return sirin_mbox_create(capacity, max_consumers, sdl_wake, (void *)(uintptr_t)type);
}

sirin_mbox *sirin_mbox_create_counting(uint32_t capacity, uint32_t max_consumers,
                                       uint64_t *counter) {
  return sirin_mbox_create(capacity, max_consumers, counting_wake, counter);
}

sirin_mbox *sirin_mbox_create_nowake(uint32_t capacity, uint32_t max_consumers) {
  return sirin_mbox_create(capacity, max_consumers, NULL, NULL);
}

// ---------------------------------------------------------------------------
// SDL event watch: records which kind of thread SDL runs watches on for
// user-range events. Watches run synchronously inside SDL_PushEvent.

static atomic_ullong watch_scala = 0, watch_foreign = 0, watch_other = 0;

static bool sirin_event_watch(void *userdata, SDL_Event *e) {
  (void)userdata;
  if (e->type >= SDL_EVENT_USER && e->type <= SDL_EVENT_LAST) {
    int marked = sirin_thread_is_scala();
    int gc = sirin_thread_gc_state();
    if (marked && gc >= 0) atomic_fetch_add(&watch_scala, 1);
    else if (!marked && gc == -1) atomic_fetch_add(&watch_foreign, 1);
    else atomic_fetch_add(&watch_other, 1);
  }
  return true;
}

int sirin_event_watch_install(void) { return SDL_AddEventWatch(sirin_event_watch, NULL) ? 1 : 0; }
void sirin_event_watch_remove(void) { SDL_RemoveEventWatch(sirin_event_watch, NULL); }
void sirin_event_watch_counts(uint64_t *out) {
  out[0] = atomic_load(&watch_scala);
  out[1] = atomic_load(&watch_foreign);
  out[2] = atomic_load(&watch_other);
}

// ---------------------------------------------------------------------------
// Watchdog: a C pthread (never runs Scala) that aborts the process if any
// heartbeat slot stops advancing for stall_ms. It catches deadlocks, including
// a stop-the-world GC that never completes.

#define SIRIN_WD_SLOTS 64
static atomic_ullong wd_beats[SIRIN_WD_SLOTS];
static atomic_int wd_active[SIRIN_WD_SLOTS];
static atomic_int wd_run = 0;
static uint64_t wd_stall_ms = 0;
static pthread_t wd_thread;

void sirin_hb(uint32_t slot) { atomic_fetch_add_explicit(&wd_beats[slot % SIRIN_WD_SLOTS], 1, memory_order_relaxed); }
void sirin_hb_activate(uint32_t slot, int active) { atomic_store(&wd_active[slot % SIRIN_WD_SLOTS], active); }

static void *wd_main(void *arg) {
  (void)arg;
  uint64_t last[SIRIN_WD_SLOTS], since[SIRIN_WD_SLOTS];
  uint64_t now = sirin_now_ns();
  for (int i = 0; i < SIRIN_WD_SLOTS; i++) { last[i] = atomic_load(&wd_beats[i]); since[i] = now; }
  while (atomic_load(&wd_run)) {
    usleep(200 * 1000);
    now = sirin_now_ns();
    for (int i = 0; i < SIRIN_WD_SLOTS; i++) {
      uint64_t b = atomic_load(&wd_beats[i]);
      if (b != last[i] || !atomic_load(&wd_active[i])) { last[i] = b; since[i] = now; continue; }
      if (now - since[i] > wd_stall_ms * 1000000ull) {
        fprintf(stderr, "sirin watchdog: FATAL: slot %d made no progress for %llu ms; aborting\n",
                i, (unsigned long long)wd_stall_ms);
        fflush(stderr);
        abort();
      }
    }
  }
  return NULL;
}

int sirin_watchdog_start(uint64_t stall_ms) {
  wd_stall_ms = stall_ms;
  atomic_store(&wd_run, 1);
  return pthread_create(&wd_thread, NULL, wd_main, NULL) == 0;
}

void sirin_watchdog_stop(void) {
  atomic_store(&wd_run, 0);
  pthread_join(wd_thread, NULL);
}

// ---------------------------------------------------------------------------
// Re-entry experiment (P0.6): C calls a Scala CFuncPtr n times. The Scala side
// declares one symbol @blocking and the other plain, so the only difference is
// the GC thread state the runtime sets around the call.

static atomic_int reenter_state_seen[3]; // index: gc state + 1 observed in C before each callback

static int64_t reenter_impl(sirin_cb_i64 cb, int32_t n) {
  int64_t sum = 0;
  for (int32_t i = 0; i < n; i++) {
    int s = sirin_thread_gc_state();
    if (s >= -1 && s <= 1) atomic_fetch_add(&reenter_state_seen[s + 1], 1);
    sum += sirin_invoke_guarded(cb, i);
  }
  return sum;
}

int64_t sirin_reenter_blocking(sirin_cb_i64 cb, int32_t n) { return reenter_impl(cb, n); }
int64_t sirin_reenter_plain(sirin_cb_i64 cb, int32_t n) { return reenter_impl(cb, n); }

void sirin_reenter_states(uint64_t *out) {
  for (int i = 0; i < 3; i++) out[i] = (uint64_t)atomic_load(&reenter_state_seen[i]);
}

void sirin_reenter_reset(void) {
  for (int i = 0; i < 3; i++) atomic_store(&reenter_state_seen[i], 0);
}

// ---------------------------------------------------------------------------
// Cost probes: identical C bodies; Scala declares one of each pair @blocking.

static atomic_ullong noop_sink = 0;
void sirin_noop_plain(void) { atomic_fetch_add_explicit(&noop_sink, 1, memory_order_relaxed); }
void sirin_noop_blocking(void) { atomic_fetch_add_explicit(&noop_sink, 1, memory_order_relaxed); }
void sirin_sleep_plain(uint32_t us) { usleep(us); }
void sirin_sleep_blocking(uint32_t us) { usleep(us); }

// ---------------------------------------------------------------------------

uint64_t sirin_resident_bytes(void) {
#ifdef __APPLE__
  mach_task_basic_info_data_t info;
  mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
  if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &count) != KERN_SUCCESS)
    return 0;
  return (uint64_t)info.resident_size;
#else
  return 0;
#endif
}
