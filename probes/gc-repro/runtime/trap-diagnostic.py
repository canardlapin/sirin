#!/usr/bin/env python3
"""Build a private Immix trap diagnostic JAR; preserve every input resource."""
import argparse,difflib,hashlib,json
from pathlib import Path
import zipfile

p=argparse.ArgumentParser(description=__doc__)
p.add_argument("source",type=Path);p.add_argument("output",type=Path)
a=p.parse_args()
if a.source.resolve()==a.output.resolve():p.error("output must be private")
with zipfile.ZipFile(a.source) as jar:
    names=["scala-native/gc/immix/MutatorThread.h","scala-native/gc/immix/Synchronizer.c"]
    originals={n:jar.read(n).decode() for n in names}
changes=dict(originals)
def replace(name,old,new):
    if changes[name].count(old)!=1:raise SystemExit("unrecognized runtime resource: "+name+" anchor "+old[:60])
    changes[name]=changes[name].replace(old,new)
h,c=names
replace(h,"    uintptr_t safepointResumePc;", """    uintptr_t safepointResumePc;
    /* Native-only lock-free diagnostic counters, never managed upcalls. */
    atomic_uint sirinDiagSignals;
    atomic_uint sirinDiagRecognized;
    atomic_uint sirinDiagRedirects;
    atomic_uint sirinDiagYields;
    atomic_uint sirinDiagPhase;
    atomic_uintptr_t sirinDiagFault;
    atomic_int sirinDiagSignal;
#if ATOMIC_INT_LOCK_FREE != 2 || ATOMIC_POINTER_LOCK_FREE != 2
#error Sirin diagnostic requires lock-free signal-handler atomics
#endif""")
replace(c,"    MutatorThread *self = currentMutatorThread;\n    if (self != NULL) {\n        trapCell", """    MutatorThread *self = currentMutatorThread;
    if (self != NULL) {
        atomic_fetch_add_explicit(&self->sirinDiagSignals, 1, memory_order_relaxed);
        atomic_store_explicit(&self->sirinDiagPhase, 1, memory_order_relaxed);
        atomic_store_explicit(&self->sirinDiagFault, (uintptr_t)siginfo->si_addr, memory_order_relaxed);
        atomic_store_explicit(&self->sirinDiagSignal, signal, memory_order_relaxed);
    }
    if (self != NULL) {
        trapCell""")
replace(c,"    if (isOwnTrap || isStaleCarrierTrap) {", """    if (isOwnTrap || isStaleCarrierTrap) {
        if (self != NULL) {
            atomic_fetch_add_explicit(&self->sirinDiagRecognized, 1, memory_order_relaxed);
            atomic_store_explicit(&self->sirinDiagPhase, 2, memory_order_relaxed);
        }""")
replace(c,"        if (!scalanative_gc_safepoint_prepare_redirect(uap, self)) {\n            Synchronizer_yield();\n        }", """        if (!scalanative_gc_safepoint_prepare_redirect(uap, self)) {
            if (self != NULL) atomic_store_explicit(&self->sirinDiagPhase, 3, memory_order_relaxed);
            Synchronizer_yield();
        } else if (self != NULL) {
            atomic_fetch_add_explicit(&self->sirinDiagRedirects, 1, memory_order_relaxed);
            atomic_store_explicit(&self->sirinDiagPhase, 4, memory_order_relaxed);
        }""")
replace(c,"void Synchronizer_yield(void) {\n    MutatorThread *self = currentMutatorThread;", """void Synchronizer_yield(void) {
    MutatorThread *self = currentMutatorThread;
#ifdef SCALANATIVE_GC_USE_YIELDPOINT_TRAPS
    atomic_fetch_add_explicit(&self->sirinDiagYields, 1, memory_order_relaxed);
    atomic_store_explicit(&self->sirinDiagPhase, 5, memory_order_relaxed);
#endif""")
replace(c,"                        alive ? \"yes\" : \"NO (zombie)\");", """                        alive ? "yes" : "NO (zombie)");
#ifdef SCALANATIVE_GC_USE_YIELDPOINT_TRAPS
            GC_LOG_WARN("SIRIN_DIAG thread=%lu trap=%p stackTop=%p waiting=%u signals=%u recognized=%u redirects=%u yields=%u phase=%u signal=%d fault=%p resume=%p",
                (unsigned long)thread->thread, (void*)thread->yieldpointTrap,
                (void*)atomic_load_explicit(&thread->stackTop,memory_order_relaxed),
                (unsigned)atomic_load_explicit(&thread->isWaiting,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinDiagSignals,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinDiagRecognized,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinDiagRedirects,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinDiagYields,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinDiagPhase,memory_order_relaxed),
                atomic_load_explicit(&thread->sirinDiagSignal,memory_order_relaxed),
                (void*)atomic_load_explicit(&thread->sirinDiagFault,memory_order_relaxed),
                (void*)thread->safepointResumePc);
#endif""")
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.source) as source,zipfile.ZipFile(a.output,"x",zipfile.ZIP_DEFLATED) as target:
    for info in source.infolist():target.writestr(info,changes[info.filename].encode() if info.filename in changes else source.read(info))
patch="".join("".join(difflib.unified_diff(originals[n].splitlines(True),changes[n].splitlines(True),fromfile="a/"+n,tofile="b/"+n)) for n in names)
a.output.with_suffix(".patch").write_text(patch)
a.output.with_suffix(".json").write_text(json.dumps({"source":str(a.source),"source_sha256":hashlib.sha256(a.source.read_bytes()).hexdigest(),"output_sha256":hashlib.sha256(a.output.read_bytes()).hexdigest(),"changed_resources":names,"mode":"diagnostic counters only; no causal fix"},indent=2)+"\n")
print(a.output)
