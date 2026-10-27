#include "sirin_guard.h"

#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>

static _Thread_local int sirin_tls_scala = 0;
static atomic_ullong sirin_checks = 0;

#ifdef SIRIN_NO_SCALANATIVE_GC
// Pure-C harnesses (TSan) have no Scala Native runtime.
static _Thread_local void *currentMutatorThread = NULL;
#else
// Defined by the Scala Native immix GC (gc/immix/State.c). The first field of
// MutatorThread is `_Atomic(GC_MutatorThreadState) state`.
extern _Thread_local void *currentMutatorThread;
#endif

void sirin_thread_mark_scala(void) { sirin_tls_scala = 1; }
void sirin_thread_unmark(void) { sirin_tls_scala = 0; }
int sirin_thread_is_scala(void) { return sirin_tls_scala; }

int sirin_thread_gc_state(void) {
  void *t = currentMutatorThread;
  if (t == NULL) return -1;
  return (int)atomic_load((_Atomic int *)t);
}

void sirin_require_scala_thread(const char *where) {
  atomic_fetch_add_explicit(&sirin_checks, 1, memory_order_relaxed);
  if (!sirin_tls_scala || currentMutatorThread == NULL) {
    fprintf(stderr,
            "sirin: FATAL: Scala entry '%s' reached on a foreign thread "
            "(marked=%d, gc-registered=%d); aborting\n",
            where, sirin_tls_scala, currentMutatorThread != NULL);
    fflush(stderr);
    abort();
  }
}

uint64_t sirin_guard_checks(void) { return atomic_load(&sirin_checks); }
