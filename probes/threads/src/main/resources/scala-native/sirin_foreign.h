// Foreign producers for P0.7: raw pthreads, created in C, that Scala Native
// knows nothing about. They only ever call sirin_mbox_deliver. No CFuncPtr is
// reachable from them.
#ifndef SIRIN_FOREIGN_H
#define SIRIN_FOREIGN_H

#include <stdint.h>

#include "sirin_mailbox.h"

#define SIRIN_FOREIGN_MAX_HANDLES 16
#define SIRIN_PAYLOAD_WORDS 3 // producer, seq, checksum

// Indices into the array filled by sirin_foreign_join.
enum {
  SIRIN_FG_OK = 0, // SIRIN_FG_OK .. SIRIN_FG_STALE mirror SIRIN_MBOX_* statuses
  SIRIN_FG_FULL,
  SIRIN_FG_CLOSED,
  SIRIN_FG_TOO_LARGE,
  SIRIN_FG_STALE,
  SIRIN_FG_ATTEMPTS,
  SIRIN_FG_FOREIGN_CONFIRMED, // producers that verified they are unmarked and GC-unregistered
  SIRIN_FG_IDENTITY_VIOLATIONS,
  SIRIN_FG_ENDED_CLOSED,      // producers whose last result was CLOSED
  SIRIN_FG_N
};

typedef struct sirin_foreign_group sirin_foreign_group;

uint64_t sirin_payload_checksum(uint64_t producer, uint64_t seq);

// Starts nthreads foreign producers. Producer p sends n_per_thread attempts
// with tag producer_base + p and seq 0, 1, ...; seq advances only on OK, so
// each producer's accepted messages are contiguous. Attempt i is addressed to
// handle slot (p + i) % nhandles, read atomically, so the consumer can swap
// handles while producers run. If stop_on_closed, a producer exits at its first
// CLOSED result. Returns NULL if a thread cannot be created.
sirin_foreign_group *sirin_foreign_start(sirin_mbox *mb, uint32_t nthreads, uint64_t n_per_thread,
                                         uint64_t producer_base, const uint32_t *handles,
                                         uint32_t nhandles, int64_t wait_ns, int stop_on_closed);
void sirin_foreign_set_handle(sirin_foreign_group *g, uint32_t i, uint32_t handle);
uint32_t sirin_foreign_running(sirin_foreign_group *g);
// Joins every producer, sums their counters into out[SIRIN_FG_N], frees g.
void sirin_foreign_join(sirin_foreign_group *g, uint64_t *out);

// Re-entry shape (P0.7 d). Called from Scala; runs entirely in C. First the
// calling thread delivers n_sync messages through a two-level C callback chain
// (as a native library invoking a registered C callback would), then a fresh
// foreign pthread delivers n_foreign messages while the caller waits inside
// this call. Producer tags: producer_base (sync) and producer_base + 1 (foreign).
// out[SIRIN_FG_N] as for sirin_foreign_join. Returns 0, or -1 on thread failure.
int sirin_reentrant_delivery(sirin_mbox *mb, uint32_t handle, uint64_t n_sync, uint64_t n_foreign,
                             uint64_t producer_base, uint64_t seq_base, int64_t wait_ns,
                             uint64_t *out);

// Spawns a pthread and reports its identity: out[0] = marked as Scala,
// out[1] = sirin_thread_gc_state() on that thread.
void sirin_foreign_identity(int *out);

// Forks; in the child a foreign pthread calls a guarded Scala entry with a C
// stand-in callback. Returns the child's terminating signal, or 1000 + exit
// code if it exited normally (which means the guard failed).
int sirin_guard_selftest_fork(void);

// The same, in this process: expected to abort it (SIGABRT).
void sirin_guard_foreign_call_inprocess(void);

// Guarded trampoline: what any shim path that calls a Scala CFuncPtr does.
typedef int64_t (*sirin_cb_i64)(int32_t);
int64_t sirin_invoke_guarded(sirin_cb_i64 cb, int32_t arg);

#endif
