#!/usr/bin/env python3
"""Exercise the generated C/assembly detector with explicit synthetic positives."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("jar", type=Path)
p.add_argument("out", type=Path)
p.add_argument("--restore-mask", action="store_true")
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
with zipfile.ZipFile(a.jar) as jar:
    for name in ["SafepointPollTrampoline.c", "SafepointPollTrampoline.h", "SafepointPollTrampoline-aarch64.S"]:
        (a.out / name).write_bytes(jar.read("scala-native/gc/shared/" + name))
    header = jar.read("scala-native/gc/immix/MutatorThread.h").decode()
fields = re.findall(r"    (?:atomic_uint(?:ptr_t)?|sigset_t) sirin_\w+;", header)
atomics = re.findall(r"    atomic_uint(?:ptr_t)? (sirin_\w+);", header)
assert len(fields) == 23 and len(atomics) == 22
for sub in ["immix", "immix_commix"]:
    (a.out / sub).mkdir()
(a.out / "immix/MutatorThread.h").write_text("""#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <signal.h>
typedef struct {
    uintptr_t safepointResumePc;
""" + "\n".join(fields) + """
} MutatorThread;
""")
(a.out / "immix/State.h").write_text("""#include "MutatorThread.h"
extern MutatorThread *currentMutatorThread;
""")
(a.out / "immix_commix/Synchronizer.h").write_text("void Synchronizer_yield(void);\n")
source = """#define _XOPEN_SOURCE 700
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <ucontext.h>
#include "immix/State.h"
#include "SafepointPollTrampoline.h"
extern char sirin_activation_tail_begin[];
extern char sirin_activation_tail_end[];
extern atomic_uint *sirin_activation_active_address(void);
static MutatorThread state;
MutatorThread *currentMutatorThread = &state;
static sigset_t saved_mask;
static int inject;
static void redirect(uintptr_t pc) {
    struct __darwin_mcontext64 machine = {0};
    ucontext_t context = {0};
    context.uc_mcontext = &machine;
    context.uc_sigmask = saved_mask;
    machine.__ss.__pc = pc;
    assert(scalanative_gc_safepoint_prepare_redirect(&context, &state));
    assert(machine.__ss.__pc == (uintptr_t)scalanative_gc_safepoint_poll_trampoline);
}
void Synchronizer_yield(void) {
    if (inject)
        redirect(0x2220);
}
static void reset(void) {
    assert(sigprocmask(SIG_SETMASK, &saved_mask, NULL) == 0);
    memset(&state, 0, sizeof(state));
INITIALIZERS
    inject = 0;
}
int main(void) {
    sigset_t before;
    assert(sigprocmask(SIG_SETMASK, NULL, &before) == 0);
    saved_mask = before;
    assert(sigaddset(&saved_mask, SIGUSR2) == 0);
    assert(sigdelset(&saved_mask, SIGBUS) == 0);
    reset();
    errno = EDOM;
    redirect(0x1110);
    unsigned owner = scalanative_gc_safepoint_poll_run_yield();
    assert(owner == 1 && state.sirin_overlap == 0);
    assert(scalanative_gc_safepoint_load_resume_pc(owner) == 0x1110);
    assert(state.sirin_owner_mismatch == 0 && state.sirin_pc_mismatch == 0);
    assert(state.sirin_usr2_missing == 0 && state.sirin_mask_errors == 0);
    assert(state.sirin_mask_yield_mismatch == 0 && errno == EDOM);
    assert(sirin_activation_active_address() == &state.sirin_active);
    puts("PASS ordinary activation and unrelated blocked signal");

    reset();
    redirect(0x1110);
    inject = 1;
    owner = scalanative_gc_safepoint_poll_run_yield();
    assert(state.sirin_overlap == 1 && state.sirin_overlap_phase == 2);
    assert(state.sirin_owner_mismatch == 1 && state.sirin_pc_mismatch == 1);
    /* Diagnostics observe corruption; they must not repair the single PC slot. */
    assert(scalanative_gc_safepoint_load_resume_pc(owner) == 0x2220);
    assert(state.sirin_owner_mismatch == 2);
    puts("PASS injected redirect during yield detects owner and PC overwrite");

    reset();
    state.sirin_phase = 5;
    assert((uintptr_t)sirin_activation_tail_end > (uintptr_t)sirin_activation_tail_begin);
    redirect((uintptr_t)sirin_activation_tail_begin);
    assert(state.sirin_overlap == 1 && state.sirin_tail_overlap == 1);
    assert(state.sirin_overlap_phase == 5);
    reset();
    redirect((uintptr_t)sirin_activation_tail_end);
    assert(state.sirin_overlap == 0 && state.sirin_tail_overlap == 0);
    puts("PASS restore-tail interval includes clear and excludes resumed code");

    reset();
    state.sirin_sequence = UINT32_MAX;
    redirect(0x1110);
    assert(state.sirin_wrapped == 1);
    puts("PASS activation counter wrap is visible");

    reset();
    redirect(0x1110);
    sigset_t bus;
    sigemptyset(&bus);
    sigaddset(&bus, SIGBUS);
    assert(sigprocmask(SIG_BLOCK, &bus, NULL) == 0);
    owner = scalanative_gc_safepoint_poll_run_yield();
    assert(state.sirin_mask_entry_mismatch == 1);
    assert(state.sirin_mask_restore_mismatch == EXPECT_MISMATCH);
    assert(state.sirin_mask_yield_mismatch == EXPECT_MISMATCH);
    assert(state.sirin_usr2_missing == 0);
    assert(state.sirin_owner_mismatch == 0 && state.sirin_pc_mismatch == 0);
    assert(scalanative_gc_safepoint_load_resume_pc(owner) == 0x1110);
    puts("PASS entry-mask mismatch control and SIGUSR2 preservation");
    assert(sigprocmask(SIG_SETMASK, &before, NULL) == 0);
    return 0;
}
"""
source = source.replace("INITIALIZERS", "\n".join("    atomic_init(&state." + f + ", 0);" for f in atomics))
source = source.replace("EXPECT_MISMATCH", "0" if a.restore_mask else "1")
(a.out / "harness.c").write_text(source)
command = [
    "clang", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
    "-DSCALANATIVE_MULTITHREADING_ENABLED", "-DSCALANATIVE_GC_USE_YIELDPOINT_TRAPS",
    "-DSCALANATIVE_GC_IMMIX", "-I", str(a.out), str(a.out / "harness.c"),
    str(a.out / "SafepointPollTrampoline.c"), str(a.out / "SafepointPollTrampoline-aarch64.S"),
    "-o", str(a.out / "harness"),
]
with (a.out / "compile.log").open("w") as log:
    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
(a.out / "compile-result.json").write_text(json.dumps({"command": command, "exit_code": result.returncode}) + "\n")
if result.returncode:
    raise SystemExit(result.returncode)
with (a.out / "run.log").open("w") as log:
    result = subprocess.run([str(a.out / "harness")], stdout=log, stderr=subprocess.STDOUT, timeout=10)
(a.out / "run-result.json").write_text(json.dumps({"exit_code": result.returncode}) + "\n")
print((a.out / "run.log").read_text())
raise SystemExit(result.returncode)
