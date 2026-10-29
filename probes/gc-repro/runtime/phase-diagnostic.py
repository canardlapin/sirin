#!/usr/bin/env python3
"""Private Darwin phase-mask successor; never modifies input JAR or masks."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('source',type=Path)
p.add_argument('output',type=Path)
a = p.parse_args()
expected = 'e73c396c789988b48d8e49744c7ad1d02291dcd65fca3bbd2c3d211b3d7a10dc'
if hashlib.sha256(a.source.read_bytes()).hexdigest() != expected:
    p.error('requires the frozen prior mask-diagnostic JAR')
if a.output.exists() or a.source.resolve() == a.output.resolve():
    p.error('output must be a new private JAR')
prefix = 'scala-native/gc/'
h = prefix+'immix/MutatorThread.h'
m = prefix+'immix/MutatorThread.c'
c = prefix+'immix/Synchronizer.c'
t = prefix+'shared/SafepointPollTrampoline.c'
with zipfile.ZipFile(a.source) as jar:
    original = {n:jar.read(n).decode() for n in [h,m,c,t]}
changed = dict(original)
def replace(name,old,new):
    if changed[name].count(old) != 1:
        raise SystemExit('Unexpected anchor: '+name+' '+old[:70])
    changed[name] = changed[name].replace(old,new)
replace(h,'    atomic_int sirinDiagYieldBus;', '''    atomic_int sirinDiagYieldBus;
    atomic_uint sirinMasks[8];
    atomic_uint sirinMaskCounts[8];
    atomic_uint sirinHandlersActive;
    atomic_uint sirinNestedHandlers;
    atomic_uint sirinHandlerExits;''')
replace(h,'} MutatorThread;', '''} MutatorThread;
#if defined(__APPLE__) && defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)
void sirin_diag_mask(MutatorThread *self, unsigned hook);
#endif''')
replace(m,'static mutex_t threadListsModificationLock;', '''#if defined(__APPLE__) && defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)
/* Native-only diagnostic: NULL new-mask pointer, preserve errno. */
void sirin_diag_mask(MutatorThread *self, unsigned hook) {
    if (self == NULL) return;
    int saved_errno = errno;
    sigset_t mask;
    unsigned bits;
    if (sigprocmask(SIG_SETMASK, NULL, &mask) != 0) {
        bits = 0x80000000u | (unsigned)errno;
    } else {
        bits = (sigismember(&mask, SIGBUS) ? 1u : 0u) |
               (sigismember(&mask, SIGSEGV) ? 2u : 0u) |
               (sigismember(&mask, SIGCONT) ? 4u : 0u);
    }
    atomic_store_explicit(&self->sirinMasks[hook], bits, memory_order_relaxed);
    atomic_fetch_add_explicit(&self->sirinMaskCounts[hook], 1, memory_order_relaxed);
    errno = saved_errno;
}
#endif
static mutex_t threadListsModificationLock;''')
replace(m,'#include <assert.h>','#include <assert.h>\n#include <errno.h>')
replace(m,'    currentMutatorThread = self;','''    currentMutatorThread = self;
#if defined(__APPLE__) && defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)
    sirin_diag_mask(self, 7);
#endif''')
replace(m,'    assert(self != NULL);\n#if defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)', '''    assert(self != NULL);
#if defined(__APPLE__) && defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)
    sirin_diag_mask(self, 5);
#endif
#if defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)''')
replace(m,'''    if (atomic_load_explicit(&self->state, memory_order_acquire) ==
        GC_MutatorThreadState_Managed)
        return;''','''    if (atomic_load_explicit(&self->state, memory_order_acquire) ==
        GC_MutatorThreadState_Managed) {
#if defined(__APPLE__) && defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)
        sirin_diag_mask(self, 6);
#endif
        return;
    }''')
replace(m,'''    MutatorThread_switchState(self, GC_MutatorThreadState_Managed);
    MutatorThreads_unlock();''','''    MutatorThread_switchState(self, GC_MutatorThreadState_Managed);
    MutatorThreads_unlock();
#if defined(__APPLE__) && defined(SCALANATIVE_GC_USE_YIELDPOINT_TRAPS)
    sirin_diag_mask(self, 6);
#endif''')
replace(c,'''        atomic_fetch_add_explicit(&self->sirinDiagSignals, 1, memory_order_relaxed);''','''#ifdef __APPLE__
        sirin_diag_mask(self, 0);
        if (atomic_fetch_add_explicit(&self->sirinHandlersActive, 1, memory_order_relaxed) != 0)
            atomic_fetch_add_explicit(&self->sirinNestedHandlers, 1, memory_order_relaxed);
#endif
        atomic_fetch_add_explicit(&self->sirinDiagSignals, 1, memory_order_relaxed);''')
replace(c,'''        errno = old_errno;
        return;''','''#ifdef __APPLE__
        if (self != NULL) {
            atomic_fetch_add_explicit(&self->sirinHandlerExits, 1, memory_order_relaxed);
            atomic_fetch_sub_explicit(&self->sirinHandlersActive, 1, memory_order_relaxed);
        }
#endif
        errno = old_errno;
        return;''')
anchor='''                atomic_load_explicit(&thread->sirinDiagYieldBus,memory_order_relaxed));'''
args=',\n                '.join('atomic_load_explicit(&thread->sirinMasks[%d],memory_order_relaxed)'%i for i in range(8))
counts=',\n                '.join('atomic_load_explicit(&thread->sirinMaskCounts[%d],memory_order_relaxed)'%i for i in range(8))
replace(c,anchor,anchor+'''
#ifdef __APPLE__
            GC_LOG_WARN("SIRIN_PHASE masks=%x,%x,%x,%x,%x,%x,%x,%x counts=%u,%u,%u,%u,%u,%u,%u,%u active=%u nested=%u exits=%u",
                '''+args+',\n                '+counts+''',
                atomic_load_explicit(&thread->sirinHandlersActive,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinNestedHandlers,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinHandlerExits,memory_order_relaxed));
#endif''')
replace(t,'#include <ucontext.h>','#include <ucontext.h>\n#include <signal.h>')
replace(t,'void scalanative_gc_safepoint_poll_run_yield(void) { Synchronizer_yield(); }','''void scalanative_gc_safepoint_poll_run_yield(void) {
#if defined(__APPLE__) && defined(SCALANATIVE_GC_IMMIX)
    sirin_diag_mask(currentMutatorThread, 2);
#endif
    Synchronizer_yield();
#if defined(__APPLE__) && defined(SCALANATIVE_GC_IMMIX)
    sirin_diag_mask(currentMutatorThread, 3);
#endif
}''')
replace(t,'''    return (self != NULL) ? self->safepointResumePc : (uintptr_t)0;''','''#if defined(__APPLE__) && defined(SCALANATIVE_GC_IMMIX)
    sirin_diag_mask(self, 4);
#endif
    return (self != NULL) ? self->safepointResumePc : (uintptr_t)0;''')
replace(t,'''    ucontext_t *uc = (ucontext_t *)uap;''','''    ucontext_t *uc = (ucontext_t *)uap;
#if defined(__APPLE__) && defined(SCALANATIVE_GC_IMMIX)
    unsigned bits = (sigismember(&uc->uc_sigmask, SIGBUS) ? 1u : 0u) |
                    (sigismember(&uc->uc_sigmask, SIGSEGV) ? 2u : 0u) |
                    (sigismember(&uc->uc_sigmask, SIGCONT) ? 4u : 0u);
    atomic_store_explicit(&self->sirinMasks[1], bits, memory_order_relaxed);
    atomic_fetch_add_explicit(&self->sirinMaskCounts[1], 1, memory_order_relaxed);
#endif''')
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.source) as source, zipfile.ZipFile(a.output,'x',zipfile.ZIP_DEFLATED) as target:
    for info in source.infolist():
        target.writestr(info,changed[info.filename].encode() if info.filename in changed else source.read(info))
a.output.with_suffix('.patch').write_text(''.join(''.join(difflib.unified_diff(original[n].splitlines(True),changed[n].splitlines(True),fromfile='a/'+n,tofile='b/'+n)) for n in original))
a.output.with_suffix('.json').write_text(json.dumps({'source_sha256':expected,'output_sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'changed_resources':list(changed),'mask_bits':{'SIGBUS':1,'SIGSEGV':2,'SIGCONT':4,'query_error':2147483648},'hooks':['handler entry','saved ucontext','trampoline entry','yield return','resume helper','admission entry','admission exit','thread registration'],'scope':'Darwin Immix observation only; no mask/handler policy/assembly change; non-coherent last observations, not performance or remedy qualification'},indent=2)+'\n')
print(a.output)
