// Bounded native mailbox for foreign-thread entry (VISION §5.4, §6.1; P0.7).
//
// Pure C: no SDL, no Scala Native runtime. Producers may be any thread,
// including threads Scala Native did not create. The mailbox copies each
// message into an inline slot, so a producer's buffer may be reused as soon
// as sirin_mbox_deliver returns, and no per-message allocation happens on a
// foreign thread. A Scala-owned thread drains copies into its own buffer.
//
// Lifetime rule: a mailbox must outlive every thread that can still reach
// its pointer. Close it first (producers then get SIRIN_MBOX_CLOSED), wait for
// in-flight producers to leave, and destroy it only once no foreign thread can
// call into it again. If that cannot be guaranteed, leave it closed and
// allocated.
#ifndef SIRIN_MAILBOX_H
#define SIRIN_MAILBOX_H

#include <stddef.h>
#include <stdint.h>

#define SIRIN_MBOX_PAYLOAD 64

enum {
  SIRIN_MBOX_OK = 0,
  SIRIN_MBOX_FULL = 1,      // no slot free and the wait expired (or wait_ns == 0)
  SIRIN_MBOX_CLOSED = 2,    // closed before or while waiting
  SIRIN_MBOX_TOO_LARGE = 3, // payload exceeds SIRIN_MBOX_PAYLOAD
  SIRIN_MBOX_STALE = 4,     // consumer handle is unregistered or has an old generation
  SIRIN_MBOX_STATUS_COUNT = 5
};

typedef struct sirin_msg {
  uint32_t consumer; // handle the message was addressed to
  uint32_t len;      // payload bytes in data
  uint64_t producer; // producer tag
  uint64_t seq;      // producer sequence number
  uint64_t t_ns;     // monotonic enqueue time
  uint8_t data[SIRIN_MBOX_PAYLOAD];
} sirin_msg;

typedef void (*sirin_wake_fn)(void *ctx);
typedef struct sirin_mbox sirin_mbox;

// Indices into the array filled by sirin_mbox_stats.
enum {
  SIRIN_ST_OK = 0,
  SIRIN_ST_FULL,
  SIRIN_ST_CLOSED,
  SIRIN_ST_TOO_LARGE,
  SIRIN_ST_STALE,
  SIRIN_ST_DRAINED,       // messages handed to the consumer
  SIRIN_ST_DROPPED_STALE, // messages discarded at drain because their consumer was unregistered
  SIRIN_ST_WAKES,         // wake calls (one per empty -> pending transition)
  SIRIN_ST_COUNT,         // messages currently queued
  SIRIN_ST_HIGH_WATER,
  SIRIN_ST_INFLIGHT,      // high-water mark of producers concurrently inside deliver
  SIRIN_ST_CAPACITY,
  SIRIN_ST_IS_CLOSED,
  SIRIN_ST_N
};

uint64_t sirin_now_ns(void);

// capacity >= 1, max_consumers in [1, 65535]. wake may be NULL.
sirin_mbox *sirin_mbox_create(uint32_t capacity, uint32_t max_consumers, sirin_wake_fn wake,
                              void *wake_ctx);

// Copies len bytes of data. wait_ns: 0 = never wait, < 0 = wait until space or close,
// > 0 = wait at most that long. Thread-safe from any thread.
int sirin_mbox_deliver(sirin_mbox *mb, uint32_t consumer, uint64_t producer, uint64_t seq,
                       const void *data, uint32_t len, int64_t wait_ns);

// Returns a handle (generation << 16 | slot + 1), or 0 if the mailbox is closed
// or no slot is free. Generations start at 1 and never wrap: a slot that has
// issued generation 0xFFFF is retired once unregistered.
uint32_t sirin_mbox_register(sirin_mbox *mb);
// Returns 1 if the handle was live, else 0. After it returns, no message for
// the handle is ever returned by sirin_mbox_drain.
int sirin_mbox_unregister(sirin_mbox *mb, uint32_t handle);

// Copies up to max live messages, FIFO, into out. Messages addressed to
// unregistered handles are dropped and counted. *remaining (if non-NULL) is the
// queue length afterwards. The wake latch is re-armed only when the queue is
// emptied, so the consumer must keep draining while *remaining > 0.
size_t sirin_mbox_drain(sirin_mbox *mb, sirin_msg *out, size_t max, uint64_t *remaining);

// Closes the mailbox and releases every waiting producer with SIRIN_MBOX_CLOSED.
void sirin_mbox_close(sirin_mbox *mb);
// Waits until no producer is inside deliver. Returns 1 when idle, 0 on timeout.
int sirin_mbox_wait_idle(sirin_mbox *mb, int64_t timeout_ns);
// Frees a closed, idle mailbox. Returns the number of undrained messages
// discarded, or -1 (and frees nothing) if it is still open or has producers inside.
int64_t sirin_mbox_destroy(sirin_mbox *mb);

void sirin_mbox_stats(sirin_mbox *mb, uint64_t *out);
// Mailboxes created minus mailboxes destroyed (process-wide).
int64_t sirin_mbox_live_count(void);

// Layout export for the Scala mirror: out[0] = sizeof(sirin_msg), then offsets.
size_t sirin_msg_layout(size_t *out);

#endif
