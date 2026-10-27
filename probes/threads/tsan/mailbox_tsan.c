// Pure-C ThreadSanitizer harness for the P0.7 mailbox, guard and foreign
// producers. It compiles the exact shim sources the probe links, minus SDL and
// the Scala Native runtime (SIRIN_NO_SCALANATIVE_GC), and replays cases a-d
// with a C consumer thread standing in for the Scala main thread.
//
//   probes/threads/tsan/run.sh [n_per_case]
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "sirin_foreign.h"
#include "sirin_guard.h"
#include "sirin_mailbox.h"

static int failures = 0;
#define CHECK(name, cond)                                                     \
  do {                                                                        \
    int ok_ = (cond);                                                         \
    if (!ok_) failures++;                                                     \
    printf("RESULT tsan.%s=%s\n", name, ok_ ? "PASS" : "FAIL");               \
  } while (0)

static atomic_ullong wakes = 0;
static void wake(void *ctx) { (void)ctx; atomic_fetch_add(&wakes, 1); }

typedef struct verifier {
  uint64_t next[64];
  uint64_t received, bad, order;
  int contiguous;
} verifier;

static void verify(verifier *v, const sirin_msg *m, uint64_t base) {
  const uint64_t *w = (const uint64_t *)m->data;
  if (m->len != 24 || w[0] != m->producer || w[1] != m->seq ||
      w[2] != sirin_payload_checksum(m->producer, m->seq))
    v->bad++;
  uint64_t p = m->producer - base;
  if (p >= 64) { v->bad++; return; }
  if ((v->contiguous && m->seq != v->next[p]) || (!v->contiguous && m->seq < v->next[p])) v->order++;
  v->next[p] = m->seq + 1;
  v->received++;
}

static uint64_t drain_all(sirin_mbox *mb, verifier *v, uint64_t base) {
  static sirin_msg buf[256];
  uint64_t rem = 0, total = 0;
  size_t n;
  do {
    n = sirin_mbox_drain(mb, buf, 256, &rem);
    for (size_t i = 0; i < n; i++) verify(v, &buf[i], base);
    total += n;
  } while (n == 256 || rem > 0);
  return total;
}

static void case_a(uint64_t n) {
  sirin_mbox *mb = sirin_mbox_create(1024, 4, wake, NULL);
  uint32_t h = sirin_mbox_register(mb);
  verifier v = {.contiguous = 1};
  uint32_t threads = 8;
  uint64_t per = n / threads;
  sirin_foreign_group *g = sirin_foreign_start(mb, threads, per, 100, &h, 1, -1, 1);
  while (v.received < per * threads || sirin_foreign_running(g) > 0) {
    if (drain_all(mb, &v, 100) == 0) sched_yield();
  }
  uint64_t c[SIRIN_FG_N];
  sirin_foreign_join(g, c);
  drain_all(mb, &v, 100);
  sirin_mbox_close(mb);
  sirin_mbox_wait_idle(mb, -1);
  int64_t left = sirin_mbox_destroy(mb);
  printf("RESULT tsan.a.deliveries=%llu wakes=%llu\n", (unsigned long long)v.received,
         (unsigned long long)atomic_load(&wakes));
  CHECK("a.all_delivered", v.received == per * threads && c[SIRIN_FG_OK] == per * threads && left == 0);
  CHECK("a.order_and_payload", v.order == 0 && v.bad == 0);
  CHECK("a.foreign", c[SIRIN_FG_FOREIGN_CONFIRMED] == threads);
}

static void case_b(uint64_t n) {
  uint64_t ok = 0, rounds = 0, bad = 0;
  unsigned seed = 7;
  while (ok < n) {
    sirin_mbox *mb = sirin_mbox_create(256, 4, wake, NULL);
    uint32_t h = sirin_mbox_register(mb);
    verifier v = {.contiguous = 1};
    int64_t wait = rounds % 2 == 0 ? -1 : 0;
    sirin_foreign_group *g = sirin_foreign_start(mb, 4, 1ull << 40, 200, &h, 1, wait, 1);
    uint64_t until = sirin_now_ns() + (uint64_t)(rand_r(&seed) % 5000) * 1000ull;
    while (sirin_now_ns() < until) drain_all(mb, &v, 200);
    sirin_mbox_close(mb); // producers are still active
    uint64_t c[SIRIN_FG_N];
    sirin_foreign_join(g, c);
    int idle = sirin_mbox_wait_idle(mb, 1000000000);
    if (rounds % 3 != 0) drain_all(mb, &v, 200);
    uint64_t st[SIRIN_ST_N];
    sirin_mbox_stats(mb, st);
    int64_t left = sirin_mbox_destroy(mb);
    if (!(idle && left >= 0 && c[SIRIN_FG_ENDED_CLOSED] == 4 && c[SIRIN_FG_OK] == st[SIRIN_ST_OK] &&
          st[SIRIN_ST_OK] == v.received + (uint64_t)left && v.order == 0 && v.bad == 0))
      bad++;
    ok += c[SIRIN_FG_OK];
    rounds++;
  }
  printf("RESULT tsan.b.rounds=%llu ok_deliveries=%llu\n", (unsigned long long)rounds,
         (unsigned long long)ok);
  CHECK("b.every_round_clean", bad == 0);
  CHECK("b.mailboxes_freed", sirin_mbox_live_count() == 0);
}

static void case_c(uint64_t n) {
  sirin_mbox *mb = sirin_mbox_create(64, 4096, wake, NULL);
  uint32_t hs[4];
  uint64_t reuse = 0, exhausted = 0;
  for (int i = 0; i < 4; i++) hs[i] = sirin_mbox_register(mb);
  verifier v = {.contiguous = 0};
  uint64_t per = n / 4, rotations = 0;
  sirin_foreign_group *g = sirin_foreign_start(mb, 4, per, 300, hs, 4, -1, 1);
  while (sirin_foreign_running(g) > 0) {
    drain_all(mb, &v, 300);
    int k = (int)(rotations % 4);
    uint32_t old = hs[k];
    sirin_mbox_unregister(mb, old);
    uint32_t nh = sirin_mbox_register(mb);
    if (nh == 0) { exhausted++; break; }
    if ((nh & 0xFFFFu) == (old & 0xFFFFu) && (nh >> 16) <= (old >> 16)) reuse++;
    hs[k] = nh;
    sirin_foreign_set_handle(g, (uint32_t)k, hs[k]);
    rotations++;
  }
  uint64_t c[SIRIN_FG_N];
  sirin_foreign_join(g, c);
  drain_all(mb, &v, 300);
  uint64_t st[SIRIN_ST_N];
  sirin_mbox_stats(mb, st);
  sirin_mbox_close(mb);
  int64_t left = sirin_mbox_destroy(mb);
  printf("RESULT tsan.c.rotations=%llu ok=%llu stale=%llu dropped_at_drain=%llu\n",
         (unsigned long long)rotations, (unsigned long long)c[SIRIN_FG_OK],
         (unsigned long long)c[SIRIN_FG_STALE], (unsigned long long)st[SIRIN_ST_DROPPED_STALE]);
  CHECK("c.accounted", c[SIRIN_FG_OK] + c[SIRIN_FG_STALE] == per * 4 &&
                           st[SIRIN_ST_OK] == v.received + st[SIRIN_ST_DROPPED_STALE] && left == 0);
  CHECK("c.order_and_payload", v.order == 0 && v.bad == 0);
  CHECK("c.handles_never_reused", reuse == 0 && exhausted == 0);
}

static void case_d(uint64_t n) {
  sirin_mbox *mb = sirin_mbox_create(4096, 4, wake, NULL);
  uint32_t h = sirin_mbox_register(mb);
  verifier v = {.contiguous = 1};
  uint64_t per = 1000, calls = n / (2 * per), ok = 0;
  for (uint64_t i = 0; i < calls; i++) {
    uint64_t c[SIRIN_FG_N];
    if (sirin_reentrant_delivery(mb, h, per, per, 400, i * per, -1, c) != 0) failures++;
    ok += c[SIRIN_FG_OK];
    drain_all(mb, &v, 400);
  }
  sirin_mbox_close(mb);
  int64_t left = sirin_mbox_destroy(mb);
  printf("RESULT tsan.d.calls=%llu deliveries=%llu\n", (unsigned long long)calls,
         (unsigned long long)v.received);
  CHECK("d.all_delivered", ok == calls * 2 * per && v.received == ok && left == 0);
  CHECK("d.order_and_payload", v.order == 0 && v.bad == 0);
}

typedef struct { uint64_t n; } consumer_arg;

// The consumer runs on its own pthread, marked as the "Scala" thread, as the
// real main thread would be.
static void *consumer_main(void *arg) {
  uint64_t n = ((consumer_arg *)arg)->n;
  sirin_thread_mark_scala();
  case_a(n);
  case_b(n);
  case_c(n);
  case_d(n);
  return NULL;
}

int main(int argc, char **argv) {
  consumer_arg a = {argc > 1 ? strtoull(argv[1], NULL, 10) : 100000};
  printf("RESULT tsan.n_per_case=%llu\n", (unsigned long long)a.n);
  pthread_t t;
  pthread_create(&t, NULL, consumer_main, &a);
  pthread_join(t, NULL);
  printf("RESULT tsan.failures=%d\n", failures);
  return failures == 0 ? 0 : 1;
}
