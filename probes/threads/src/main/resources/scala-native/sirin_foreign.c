#include "sirin_foreign.h"

#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "sirin_guard.h"

uint64_t sirin_payload_checksum(uint64_t producer, uint64_t seq) {
  uint64_t x = producer * 0x9E3779B97F4A7C15ull ^ (seq + 0x632BE59BD9B4E019ull);
  x ^= x >> 33;
  x *= 0xFF51AFD7ED558CCDull;
  x ^= x >> 33;
  return x;
}

typedef struct producer {
  pthread_t thread;
  struct sirin_foreign_group *g;
  uint64_t tag;
  uint32_t index;
  uint64_t c[SIRIN_FG_N];
} producer;

struct sirin_foreign_group {
  sirin_mbox *mb;
  uint32_t nthreads;
  uint64_t n_per_thread;
  int64_t wait_ns;
  int stop_on_closed;
  uint32_t nhandles;
  _Atomic uint32_t handles[SIRIN_FOREIGN_MAX_HANDLES];
  atomic_uint running;
  producer *p;
};

static void check_identity(uint64_t *c) {
  if (!sirin_thread_is_scala() && sirin_thread_gc_state() == -1)
    c[SIRIN_FG_FOREIGN_CONFIRMED]++;
  else
    c[SIRIN_FG_IDENTITY_VIOLATIONS]++;
}

static int deliver_one(sirin_mbox *mb, uint32_t handle, uint64_t tag, uint64_t seq,
                       int64_t wait_ns) {
  uint64_t payload[SIRIN_PAYLOAD_WORDS] = {tag, seq, sirin_payload_checksum(tag, seq)};
  return sirin_mbox_deliver(mb, handle, tag, seq, payload, sizeof payload, wait_ns);
}

static void *producer_main(void *arg) {
  producer *self = arg;
  sirin_foreign_group *g = self->g;
  check_identity(self->c);
  uint64_t seq = 0;
  int last = -1;
  for (uint64_t i = 0; i < g->n_per_thread; i++) {
    uint32_t h = atomic_load_explicit(&g->handles[(self->index + i) % g->nhandles],
                                      memory_order_acquire);
    last = deliver_one(g->mb, h, self->tag, seq, g->wait_ns);
    self->c[SIRIN_FG_ATTEMPTS]++;
    self->c[last]++;
    if (last == SIRIN_MBOX_OK) seq++;
    if (last == SIRIN_MBOX_CLOSED && g->stop_on_closed) break;
  }
  if (last == SIRIN_MBOX_CLOSED) self->c[SIRIN_FG_ENDED_CLOSED]++;
  atomic_fetch_sub(&g->running, 1);
  return NULL;
}

sirin_foreign_group *sirin_foreign_start(sirin_mbox *mb, uint32_t nthreads, uint64_t n_per_thread,
                                         uint64_t producer_base, const uint32_t *handles,
                                         uint32_t nhandles, int64_t wait_ns, int stop_on_closed) {
  if (nthreads == 0 || nhandles == 0 || nhandles > SIRIN_FOREIGN_MAX_HANDLES) return NULL;
  sirin_foreign_group *g = calloc(1, sizeof *g);
  g->p = calloc(nthreads, sizeof(producer));
  g->mb = mb;
  g->nthreads = nthreads;
  g->n_per_thread = n_per_thread;
  g->wait_ns = wait_ns;
  g->stop_on_closed = stop_on_closed;
  g->nhandles = nhandles;
  for (uint32_t i = 0; i < nhandles; i++) atomic_store(&g->handles[i], handles[i]);
  atomic_store(&g->running, nthreads);
  for (uint32_t i = 0; i < nthreads; i++) {
    g->p[i].g = g;
    g->p[i].tag = producer_base + i;
    g->p[i].index = i;
    if (pthread_create(&g->p[i].thread, NULL, producer_main, &g->p[i]) != 0) {
      fprintf(stderr, "sirin: pthread_create failed for producer %u\n", i);
      abort(); // a partially started group cannot be joined cleanly in a probe
    }
  }
  return g;
}

void sirin_foreign_set_handle(sirin_foreign_group *g, uint32_t i, uint32_t handle) {
  atomic_store_explicit(&g->handles[i % g->nhandles], handle, memory_order_release);
}

uint32_t sirin_foreign_running(sirin_foreign_group *g) { return atomic_load(&g->running); }

void sirin_foreign_join(sirin_foreign_group *g, uint64_t *out) {
  memset(out, 0, sizeof(uint64_t) * SIRIN_FG_N);
  for (uint32_t i = 0; i < g->nthreads; i++) {
    pthread_join(g->p[i].thread, NULL);
    for (int k = 0; k < SIRIN_FG_N; k++) out[k] += g->p[i].c[k];
  }
  free(g->p);
  free(g);
}

// ---------------------------------------------------------------------------
// Re-entry shape (case d)

typedef struct reentry_ctx {
  sirin_mbox *mb;
  uint32_t handle;
  uint64_t tag;
  uint64_t seq;
  uint64_t n;
  int64_t wait_ns;
  uint64_t c[SIRIN_FG_N];
} reentry_ctx;

typedef void (*native_cb)(reentry_ctx *, uint64_t i);

static void on_library_event(reentry_ctx *ctx, uint64_t i) {
  int r = deliver_one(ctx->mb, ctx->handle, ctx->tag, ctx->seq + i, ctx->wait_ns);
  ctx->c[SIRIN_FG_ATTEMPTS]++;
  ctx->c[r]++;
}

// Stands in for a native library that calls a registered C callback from
// inside one of its own functions.
__attribute__((noinline)) static void library_dispatch(native_cb cb, reentry_ctx *ctx) {
  for (uint64_t i = 0; i < ctx->n; i++) cb(ctx, i);
}

static void *reentry_foreign_main(void *arg) {
  reentry_ctx *ctx = arg;
  check_identity(ctx->c);
  library_dispatch(on_library_event, ctx);
  return NULL;
}

int sirin_reentrant_delivery(sirin_mbox *mb, uint32_t handle, uint64_t n_sync, uint64_t n_foreign,
                             uint64_t producer_base, uint64_t seq_base, int64_t wait_ns,
                             uint64_t *out) {
  memset(out, 0, sizeof(uint64_t) * SIRIN_FG_N);
  reentry_ctx sync = {mb, handle, producer_base, seq_base, n_sync, wait_ns, {0}};
  library_dispatch(on_library_event, &sync);
  reentry_ctx foreign = {mb, handle, producer_base + 1, seq_base, n_foreign, wait_ns, {0}};
  pthread_t t;
  if (pthread_create(&t, NULL, reentry_foreign_main, &foreign) != 0) return -1;
  pthread_join(t, NULL);
  for (int k = 0; k < SIRIN_FG_N; k++) out[k] = sync.c[k] + foreign.c[k];
  return 0;
}

// ---------------------------------------------------------------------------
// Identity and guard self-test

static void *identity_main(void *arg) {
  int *out = arg;
  out[0] = sirin_thread_is_scala();
  out[1] = sirin_thread_gc_state();
  return NULL;
}

void sirin_foreign_identity(int *out) {
  pthread_t t;
  out[0] = out[1] = -2;
  if (pthread_create(&t, NULL, identity_main, out) == 0) pthread_join(t, NULL);
}

int64_t sirin_invoke_guarded(sirin_cb_i64 cb, int32_t arg) {
  sirin_require_scala_thread("sirin_invoke_guarded");
  return cb(arg);
}

static int64_t c_stand_in(int32_t x) { return x; }

static void *guard_child_main(void *arg) {
  (void)arg;
  sirin_invoke_guarded(c_stand_in, 7); // must abort: this thread is foreign
  return NULL;
}

void sirin_guard_foreign_call_inprocess(void) {
  pthread_t t;
  if (pthread_create(&t, NULL, guard_child_main, NULL) == 0) pthread_join(t, NULL);
}

int sirin_guard_selftest_fork(void) {
  pid_t pid = fork();
  if (pid < 0) return -1;
  if (pid == 0) {
    // Child: only C runs here. Silence the expected diagnostic.
    freopen("/dev/null", "w", stderr);
    pthread_t t;
    if (pthread_create(&t, NULL, guard_child_main, NULL) != 0) _exit(98);
    pthread_join(t, NULL);
    _exit(0); // reached only if the guard did not abort
  }
  int status = 0;
  if (waitpid(pid, &status, 0) != pid) return -1;
  if (WIFSIGNALED(status)) return WTERMSIG(status);
  return 1000 + WEXITSTATUS(status);
}
