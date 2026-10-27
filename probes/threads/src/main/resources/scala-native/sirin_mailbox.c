// Bounded native mailbox (see sirin_mailbox.h). One mutex protects all state;
// producers that wait for space block on not_full; close broadcasts it.
#include "sirin_mailbox.h"

#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

struct sirin_mbox {
  pthread_mutex_t lock;
  pthread_cond_t not_full;
  pthread_cond_t idle;
  sirin_msg *ring;
  uint32_t capacity;
  uint32_t head; // next slot to read
  uint32_t count;
  uint32_t inflight;
  int closed;
  int wake_pending;
  sirin_wake_fn wake;
  void *wake_ctx;
  uint32_t max_consumers;
  uint16_t *generation; // per slot; a handle is live iff live[slot] && gen matches
  uint8_t *live;
  uint64_t st[SIRIN_ST_N];
};

static atomic_llong sirin_mbox_live = 0;

uint64_t sirin_now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static uint32_t handle_slot(uint32_t h) { return (h & 0xFFFFu) - 1u; }
static uint16_t handle_gen(uint32_t h) { return (uint16_t)(h >> 16); }

// Caller holds the lock.
static int handle_live(const sirin_mbox *mb, uint32_t h) {
  if ((h & 0xFFFFu) == 0) return 0;
  uint32_t s = handle_slot(h);
  return s < mb->max_consumers && mb->live[s] && mb->generation[s] == handle_gen(h);
}

// Timed wait on a condition; returns ETIMEDOUT when the deadline passes.
static int cond_wait_until(pthread_cond_t *c, pthread_mutex_t *m, uint64_t deadline_ns) {
  uint64_t now = sirin_now_ns();
  if (now >= deadline_ns) return ETIMEDOUT;
  uint64_t rel = deadline_ns - now;
#ifdef __APPLE__
  struct timespec ts = {(time_t)(rel / 1000000000ull), (long)(rel % 1000000000ull)};
  return pthread_cond_timedwait_relative_np(c, m, &ts);
#else
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  uint64_t abs_ns = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec + rel;
  ts.tv_sec = (time_t)(abs_ns / 1000000000ull);
  ts.tv_nsec = (long)(abs_ns % 1000000000ull);
  return pthread_cond_timedwait(c, m, &ts);
#endif
}

sirin_mbox *sirin_mbox_create(uint32_t capacity, uint32_t max_consumers, sirin_wake_fn wake,
                              void *wake_ctx) {
  if (capacity == 0 || max_consumers == 0 || max_consumers > 0xFFFFu) return NULL;
  sirin_mbox *mb = calloc(1, sizeof *mb);
  if (mb == NULL) return NULL;
  mb->ring = calloc(capacity, sizeof(sirin_msg));
  mb->generation = calloc(max_consumers, sizeof(uint16_t));
  mb->live = calloc(max_consumers, 1);
  if (mb->ring == NULL || mb->generation == NULL || mb->live == NULL) {
    free(mb->ring); free(mb->generation); free(mb->live); free(mb);
    return NULL;
  }
  pthread_mutex_init(&mb->lock, NULL);
  pthread_cond_init(&mb->not_full, NULL);
  pthread_cond_init(&mb->idle, NULL);
  mb->capacity = capacity;
  mb->max_consumers = max_consumers;
  mb->wake = wake;
  mb->wake_ctx = wake_ctx;
  mb->st[SIRIN_ST_CAPACITY] = capacity;
  atomic_fetch_add(&sirin_mbox_live, 1);
  return mb;
}

static int leave(sirin_mbox *mb, int status) {
  mb->st[status]++;
  if (--mb->inflight == 0) pthread_cond_broadcast(&mb->idle);
  pthread_mutex_unlock(&mb->lock);
  return status;
}

int sirin_mbox_deliver(sirin_mbox *mb, uint32_t consumer, uint64_t producer, uint64_t seq,
                       const void *data, uint32_t len, int64_t wait_ns) {
  pthread_mutex_lock(&mb->lock);
  mb->inflight++;
  if (mb->st[SIRIN_ST_INFLIGHT] < mb->inflight) mb->st[SIRIN_ST_INFLIGHT] = mb->inflight;
  if (len > SIRIN_MBOX_PAYLOAD) return leave(mb, SIRIN_MBOX_TOO_LARGE);
  uint64_t deadline = wait_ns > 0 ? sirin_now_ns() + (uint64_t)wait_ns : 0;
  for (;;) {
    if (mb->closed) return leave(mb, SIRIN_MBOX_CLOSED);
    if (!handle_live(mb, consumer)) return leave(mb, SIRIN_MBOX_STALE);
    if (mb->count < mb->capacity) break;
    if (wait_ns == 0) return leave(mb, SIRIN_MBOX_FULL);
    if (wait_ns < 0) {
      pthread_cond_wait(&mb->not_full, &mb->lock);
    } else if (cond_wait_until(&mb->not_full, &mb->lock, deadline) == ETIMEDOUT) {
      if (!mb->closed && mb->count >= mb->capacity) return leave(mb, SIRIN_MBOX_FULL);
    }
  }
  sirin_msg *m = &mb->ring[(mb->head + mb->count) % mb->capacity];
  m->consumer = consumer;
  m->len = len;
  m->producer = producer;
  m->seq = seq;
  m->t_ns = sirin_now_ns();
  if (len > 0) memcpy(m->data, data, len);
  mb->count++;
  if (mb->count > mb->st[SIRIN_ST_HIGH_WATER]) mb->st[SIRIN_ST_HIGH_WATER] = mb->count;
  int need_wake = !mb->wake_pending && mb->wake != NULL;
  if (need_wake) {
    mb->wake_pending = 1;
    mb->st[SIRIN_ST_WAKES]++;
  }
  sirin_wake_fn wake = mb->wake;
  void *ctx = mb->wake_ctx;
  // Count the delivery and leave before waking: the wake function (e.g.
  // SDL_PushEvent, which runs SDL event watches) must never run under our lock.
  mb->st[SIRIN_MBOX_OK]++;
  mb->inflight--;
  if (need_wake) {
    // Stay in flight across the wake call so destroy cannot free mb under us.
    mb->inflight++;
    pthread_mutex_unlock(&mb->lock);
    wake(ctx);
    pthread_mutex_lock(&mb->lock);
    if (--mb->inflight == 0) pthread_cond_broadcast(&mb->idle);
  } else if (mb->inflight == 0) {
    pthread_cond_broadcast(&mb->idle);
  }
  pthread_mutex_unlock(&mb->lock);
  return SIRIN_MBOX_OK;
}

uint32_t sirin_mbox_register(sirin_mbox *mb) {
  uint32_t h = 0;
  pthread_mutex_lock(&mb->lock);
  if (!mb->closed) {
    // A slot whose 16-bit generation is exhausted is retired rather than
    // wrapped, so a stale handle can never become live again (no ABA).
    for (uint32_t s = 0; s < mb->max_consumers; s++) {
      if (!mb->live[s] && mb->generation[s] != 0xFFFFu) {
        mb->live[s] = 1;
        mb->generation[s]++;
        h = ((uint32_t)mb->generation[s] << 16) | (s + 1);
        break;
      }
    }
  }
  pthread_mutex_unlock(&mb->lock);
  return h;
}

int sirin_mbox_unregister(sirin_mbox *mb, uint32_t handle) {
  pthread_mutex_lock(&mb->lock);
  int was = handle_live(mb, handle);
  if (was) mb->live[handle_slot(handle)] = 0;
  pthread_mutex_unlock(&mb->lock);
  return was;
}

size_t sirin_mbox_drain(sirin_mbox *mb, sirin_msg *out, size_t max, uint64_t *remaining) {
  size_t n = 0;
  pthread_mutex_lock(&mb->lock);
  uint32_t freed = 0;
  while (mb->count > 0 && n < max) {
    sirin_msg *m = &mb->ring[mb->head];
    if (handle_live(mb, m->consumer)) {
      out[n++] = *m;
      mb->st[SIRIN_ST_DRAINED]++;
    } else {
      mb->st[SIRIN_ST_DROPPED_STALE]++;
    }
    mb->head = (mb->head + 1) % mb->capacity;
    mb->count--;
    freed++;
  }
  if (mb->count == 0) mb->wake_pending = 0;
  if (remaining != NULL) *remaining = mb->count;
  if (freed > 0) pthread_cond_broadcast(&mb->not_full);
  pthread_mutex_unlock(&mb->lock);
  return n;
}

void sirin_mbox_close(sirin_mbox *mb) {
  pthread_mutex_lock(&mb->lock);
  mb->closed = 1;
  pthread_cond_broadcast(&mb->not_full);
  pthread_mutex_unlock(&mb->lock);
}

int sirin_mbox_wait_idle(sirin_mbox *mb, int64_t timeout_ns) {
  pthread_mutex_lock(&mb->lock);
  uint64_t deadline = timeout_ns > 0 ? sirin_now_ns() + (uint64_t)timeout_ns : 0;
  int ok = 1;
  while (mb->inflight > 0) {
    if (timeout_ns < 0) {
      pthread_cond_wait(&mb->idle, &mb->lock);
    } else if (timeout_ns == 0 ||
               cond_wait_until(&mb->idle, &mb->lock, deadline) == ETIMEDOUT) {
      ok = mb->inflight == 0;
      break;
    }
  }
  pthread_mutex_unlock(&mb->lock);
  return ok;
}

int64_t sirin_mbox_destroy(sirin_mbox *mb) {
  pthread_mutex_lock(&mb->lock);
  if (!mb->closed || mb->inflight > 0) {
    pthread_mutex_unlock(&mb->lock);
    return -1;
  }
  int64_t dropped = mb->count;
  pthread_mutex_unlock(&mb->lock);
  pthread_cond_destroy(&mb->not_full);
  pthread_cond_destroy(&mb->idle);
  pthread_mutex_destroy(&mb->lock);
  free(mb->ring);
  free(mb->generation);
  free(mb->live);
  free(mb);
  atomic_fetch_sub(&sirin_mbox_live, 1);
  return dropped;
}

void sirin_mbox_stats(sirin_mbox *mb, uint64_t *out) {
  pthread_mutex_lock(&mb->lock);
  memcpy(out, mb->st, sizeof mb->st);
  out[SIRIN_ST_COUNT] = mb->count;
  out[SIRIN_ST_IS_CLOSED] = (uint64_t)mb->closed;
  // SIRIN_ST_INFLIGHT reports the high-water mark of concurrent producers.
  pthread_mutex_unlock(&mb->lock);
}

int64_t sirin_mbox_live_count(void) { return atomic_load(&sirin_mbox_live); }

size_t sirin_msg_layout(size_t *out) {
  size_t i = 0;
  out[i++] = sizeof(sirin_msg);
  out[i++] = offsetof(sirin_msg, consumer);
  out[i++] = offsetof(sirin_msg, len);
  out[i++] = offsetof(sirin_msg, producer);
  out[i++] = offsetof(sirin_msg, seq);
  out[i++] = offsetof(sirin_msg, t_ns);
  out[i++] = offsetof(sirin_msg, data);
  return i;
}
