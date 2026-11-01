#!/usr/bin/env python3
"""Private Darwin ARM64 activation diagnostic, with optional frozen mask control."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


p = argparse.ArgumentParser(description=__doc__)
p.add_argument("source", type=Path)
p.add_argument("output", type=Path)
p.add_argument("--restore-mask", action="store_true")
a = p.parse_args()
expected = "819a43ed28cf90e09822e5695a079b53f7fbb4db7aa9677f1ba4871fc144df35"
if sha(a.source.read_bytes()) != expected:
    p.error("requires the frozen original pinned JAR")
if a.output.exists() or a.source.resolve() == a.output.resolve():
    p.error("output must be a new private JAR")
prefix = "scala-native/gc/"
h = prefix + "immix/MutatorThread.h"
m = prefix + "immix/MutatorThread.c"
c = prefix + "immix/Synchronizer.c"
t = prefix + "shared/SafepointPollTrampoline.c"
th = prefix + "shared/SafepointPollTrampoline.h"
s = prefix + "shared/SafepointPollTrampoline-aarch64.S"
with zipfile.ZipFile(a.source) as jar:
    original = {n: jar.read(n).decode() for n in [h, m, c, t, th, s]}
changed = dict(original)


def replace(name, old, new):
    if changed[name].count(old) != 1:
        raise SystemExit("Unexpected anchor: " + name + " " + old[:70])
    changed[name] = changed[name].replace(old, new)


guard = "defined(__APPLE__) && defined(__aarch64__) && defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)"
fields = [
    "active", "sequence", "phase", "overlap", "tail_overlap", "wrapped", "entered", "returned",
    "loaded", "owner_mismatch", "pc_mismatch", "mask_entry_mismatch", "mask_restore_mismatch",
    "mask_yield_mismatch", "mask_errors", "usr2_missing", "last_saved_mask", "last_entry_mask",
    "last_restore_mask", "last_yield_mask", "overlap_phase",
]
declarations = "\n".join("    atomic_uint sirin_" + name + ";" for name in fields)
replace(h, "#include <stdint.h>", "#include <stdint.h>\n#include <signal.h>")
replace(h, "    uintptr_t safepointResumePc;", """    uintptr_t safepointResumePc;
#if """ + guard + """
    sigset_t sirin_saved_mask;
    atomic_uintptr_t sirin_prepared_pc;
""" + declarations + """
#if ATOMIC_INT_LOCK_FREE != 2 || ATOMIC_POINTER_LOCK_FREE != 2
#error Activation diagnostic requires always-lock-free atomics
#endif
#endif""")
replace(h, "} MutatorThread;", """} MutatorThread;
#if """ + guard + """
void sirin_activation_dump(MutatorThread *self, const char *reason);
#endif""")
initializers = "\n".join("    atomic_init(&self->sirin_" + name + ", 0);" for name in fields)
replace(m, "    currentMutatorThread = self;", "#if " + guard + "\n" + initializers + """
    atomic_init(&self->sirin_prepared_pc, 0);
#endif
    currentMutatorThread = self;""")
replace(m, "    free(self);", "#if " + guard + """
    sirin_activation_dump(self, "dispose");
#endif
    free(self);""")
warning_anchor = """        MutatorThread *thread = node->value;
        if (thread != self && !MutatorThread_isAtSafepoint(thread)) {"""
replace(c, warning_anchor, """        MutatorThread *thread = node->value;
#if """ + guard + """
        sirin_activation_dump(thread, "stuck");
#endif
        if (thread != self && !MutatorThread_isAtSafepoint(thread)) {""")

replace(t, "#include <ucontext.h>", """#include <ucontext.h>
#include <signal.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#if !defined(__APPLE__) || !defined(__aarch64__) || !defined(SCALANATIVE_GC_IMMIX)
#error This private diagnostic is only for Darwin ARM64 Immix
#endif""")
replace(th, "void scalanative_gc_safepoint_poll_run_yield(void);",
        "unsigned scalanative_gc_safepoint_poll_run_yield(void);")
replace(th, "uintptr_t scalanative_gc_safepoint_load_resume_pc(void);",
        "uintptr_t scalanative_gc_safepoint_load_resume_pc(unsigned owner);")
helper = """
/* Only atomics and saved-context reads run in prepare_redirect's signal context.
 * Output occurs on ordinary thread disposal or collector warning paths.
 * The assembly clear and PC interval jointly cover the restore tail through br.
 * Counters do not repair an overlapping activation or replace its resume PC.
 */
extern char sirin_activation_tail_begin[];
extern char sirin_activation_tail_end[];
_Static_assert(sizeof(sigset_t) == sizeof(unsigned), "Darwin mask representation");
_Static_assert(sizeof(atomic_uint) == 4, "assembly clears one 32-bit atomic");
#define READ(field) atomic_load_explicit(&self->sirin_##field, memory_order_relaxed)
#define SET(field, value) atomic_store_explicit(&self->sirin_##field, value, memory_order_relaxed)
#define INC(field) atomic_fetch_add_explicit(&self->sirin_##field, 1, memory_order_relaxed)

static unsigned sirin_read_mask(MutatorThread *self) {
    int saved_errno = errno;
    sigset_t mask = 0;
    if (sigprocmask(SIG_SETMASK, NULL, &mask) != 0)
        INC(mask_errors);
    if (sigismember(&mask, SIGUSR2) != 1)
        INC(usr2_missing);
    errno = saved_errno;
    return (unsigned)mask;
}

void sirin_activation_dump(MutatorThread *self, const char *reason) {
    if (self == NULL)
        return;
    int saved_errno = errno;
    fprintf(stderr, "SIRIN_ACT reason=%s thread=%p"
"""
helper += "\n" + "\n".join('            " ' + field + '=%u"' for field in fields)
helper += '\n            "\\n", reason, (void *)self,\n'
helper += ",\n".join("            READ(" + field + ")" for field in fields)
helper += """
    );
    errno = saved_errno;
}

unsigned scalanative_gc_safepoint_poll_run_yield(void) {
    MutatorThread *self = currentMutatorThread;
    unsigned owner = READ(sequence);
    uintptr_t expected_pc = self->safepointResumePc;
    sigset_t expected_mask = self->sirin_saved_mask;
    SET(phase, 2);
    INC(entered);
    unsigned entry_mask = sirin_read_mask(self);
    SET(last_entry_mask, entry_mask);
    if (entry_mask != (unsigned)expected_mask)
        INC(mask_entry_mismatch);
"""
if a.restore_mask:
    helper += """    /* Same normal-context restoration as the frozen saved-mask candidate. */
    int saved_errno = errno;
    if (sigprocmask(SIG_SETMASK, &self->sirin_saved_mask, NULL) != 0)
        abort();
    errno = saved_errno;
"""
helper += """    unsigned restored_mask = sirin_read_mask(self);
    SET(last_restore_mask, restored_mask);
    if (restored_mask != (unsigned)expected_mask)
        INC(mask_restore_mismatch);
    Synchronizer_yield();
    SET(phase, 3);
    INC(returned);
    if (READ(sequence) != owner)
        INC(owner_mismatch);
    if (self->safepointResumePc != expected_pc)
        INC(pc_mismatch);
    unsigned yielded_mask = sirin_read_mask(self);
    SET(last_yield_mask, yielded_mask);
    if (yielded_mask != (unsigned)expected_mask)
        INC(mask_yield_mismatch);
    return owner;
}

uintptr_t scalanative_gc_safepoint_load_resume_pc(unsigned owner) {
    MutatorThread *self = currentMutatorThread;
    SET(phase, 4);
    INC(loaded);
    if (READ(sequence) != owner)
        INC(owner_mismatch);
    if (self->safepointResumePc != READ(prepared_pc))
        INC(pc_mismatch);
    return self->safepointResumePc;
}

atomic_uint *sirin_activation_active_address(void) {
    MutatorThread *self = currentMutatorThread;
    SET(phase, 5);
    return &self->sirin_active;
}
"""
replace(t, """void scalanative_gc_safepoint_poll_run_yield(void) { Synchronizer_yield(); }

uintptr_t scalanative_gc_safepoint_load_resume_pc(void) {
    MutatorThread *self = currentMutatorThread;
    return (self != NULL) ? self->safepointResumePc : (uintptr_t)0;
}""", helper)
replace(t, "    self->safepointResumePc = pc;", """    bool tail = pc >= (uintptr_t)sirin_activation_tail_begin &&
                pc < (uintptr_t)sirin_activation_tail_end;
    unsigned prior_active = atomic_exchange_explicit(&self->sirin_active, 1, memory_order_relaxed);
    if (prior_active != 0 || tail) {
        INC(overlap);
        SET(overlap_phase, READ(phase));
    }
    if (tail)
        INC(tail_overlap);
    unsigned previous_sequence = INC(sequence);
    if (previous_sequence == UINT32_MAX)
        INC(wrapped);
    SET(phase, 1);
    SET(prepared_pc, pc);
    self->sirin_saved_mask = uc->uc_sigmask;
    SET(last_saved_mask, (unsigned)uc->uc_sigmask);
    self->safepointResumePc = pc;""")

# RUN_YIELD returns the activation token in w0, directly consumed by LOAD_RESUME.
# No live caller register is changed beyond the original trampoline's behavior.
replace(s, "    str x0, [sp, #376]", """    str x0, [sp, #376]
    bl _sirin_activation_active_address
.globl _sirin_activation_tail_begin
_sirin_activation_tail_begin:
    stlr wzr, [x0]""")
replace(s, "    br x16", """    br x16
.globl _sirin_activation_tail_end
_sirin_activation_tail_end:""")

a.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(a.source) as source, zipfile.ZipFile(a.output, "w") as target:
    for info in source.infolist():
        data = changed[info.filename].encode() if info.filename in changed else source.read(info.filename)
        target.writestr(info, data)
patch = "".join("".join(difflib.unified_diff(
    original[n].splitlines(True), changed[n].splitlines(True), "a/" + n, "b/" + n
)) for n in original)
a.output.with_suffix(".patch").write_text(patch)
a.output.with_suffix(".json").write_text(json.dumps({
    "source_sha256": expected,
    "output_sha256": sha(a.output.read_bytes()),
    "restore_mask": a.restore_mask,
    "changed_resources": list(changed),
    "scope": "Private Darwin ARM64 Immix diagnostic; no runtime adoption or latency qualification",
}, indent=2) + "\n")
