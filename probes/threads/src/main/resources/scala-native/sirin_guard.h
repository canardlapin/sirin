// Thread-identity guard: Scala code never runs on a thread Scala Native did not
// create (VISION §5.4). Scala-created threads mark themselves; any shim entry
// that would call a Scala CFuncPtr first calls sirin_require_scala_thread,
// which aborts on an unmarked (foreign) thread.
#ifndef SIRIN_GUARD_H
#define SIRIN_GUARD_H

#include <stdint.h>

void sirin_thread_mark_scala(void);
void sirin_thread_unmark(void);
int sirin_thread_is_scala(void);

// The Scala Native runtime's own view of the current thread, read from the
// immix GC's thread-local MutatorThread (an internal symbol; probe use only):
// -1 = not registered with the GC, 0 = Managed, 1 = Unmanaged (inside @blocking).
int sirin_thread_gc_state(void);

// Aborts with a diagnostic unless the current thread is marked as Scala-created
// and registered with the Scala Native GC.
void sirin_require_scala_thread(const char *where);
uint64_t sirin_guard_checks(void);

#endif
