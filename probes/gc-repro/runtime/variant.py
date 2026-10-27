#!/usr/bin/env python3
"""Make an isolated diagnostic runtime JAR; never modify the dependency cache."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('source', type=Path)
p.add_argument('output', type=Path)
p.add_argument('--retry-collection', action='store_true')
p.add_argument('--retry-allocation', action='store_true')
p.add_argument('--no-diagnostics', action='store_true')
p.add_argument('--collector', choices=('immix', 'commix'), default='immix')
a = p.parse_args()
assert a.source.resolve() != a.output.resolve()
if a.retry_allocation and (a.retry_collection or not a.no_diagnostics or a.collector != 'immix'):
    p.error('--retry-allocation requires --no-diagnostics, Immix, and no --retry-collection')
if a.collector != 'immix' and not a.no_diagnostics:
    p.error('diagnostic instrumentation currently supports Immix only')
prefix = f'scala-native/gc/{a.collector}/'
with zipfile.ZipFile(a.source) as jar:
    originals = {name: jar.read(prefix + name).decode() for name in ('Heap.c', 'Heap.h', 'Allocator.c')}
changes = dict(originals)
def replace(file, old, new):
    assert changes[file].count(old) == 1, (file, old)
    changes[file] = changes[file].replace(old, new)
if not a.no_diagnostics:
    replace('Heap.c', 'void Heap_Collect(Heap *heap, Stack *stack) {', '\natomic_ulong sirin_diag_epoch = 0;\nSN_ThreadLocal bool sirin_diag_collected = false;\n\nvoid Heap_Collect(Heap *heap, Stack *stack) {\n    sirin_diag_collected = false;')
    replace('Heap.c', '    Heap_Recycle(heap);', '    Heap_Recycle(heap);\n    atomic_fetch_add(&sirin_diag_epoch, 1);')
    replace('Heap.c', '    WeakReferences_InvokeGCFinishedCallback();', '    WeakReferences_InvokeGCFinishedCallback();\n    sirin_diag_collected = true;')
    replace('Allocator.c', 'bool Allocator_getNextLine(Allocator *allocator);', 'extern atomic_ulong sirin_diag_epoch;\nextern SN_ThreadLocal bool sirin_diag_collected;\n\nbool Allocator_getNextLine(Allocator *allocator);')
    replace('Allocator.c', '        Heap_Collect(heap, &stack);', '        unsigned long diag_before = atomic_load(&sirin_diag_epoch);\n        Heap_Collect(heap, &stack);\n        unsigned long diag_after = atomic_load(&sirin_diag_epoch);\n        bool diag_collected = sirin_diag_collected;')
    replace('Heap.c', '    return freeBlockCount * 2 < blockCount ||', '''    static unsigned long diag_collections = 0;
        if ((++diag_collections % 256) == 0) {
            fprintf(stderr, "DIAG sweep n=%lu blocks=%u free=%u recycled=%u unavailable=%u\\n",
                    diag_collections, blockCount, freeBlockCount, recycledBlockCount, unavailableBlockCount);
            MutatorThreads_foreach(mutatorThreads, node) {
                fprintf(stderr, "DIAG mutator ptr=%p recycled=%lu\\n", (void *)node->value,
                        (unsigned long)node->value->allocator.recycledBlockCount);
            }
        }
        return freeBlockCount * 2 < blockCount ||''')
    replace('Allocator.c', '        // A small object can always fit in a single free block', '''        static atomic_ulong diag_misses = 0;
            static atomic_ulong diag_without_collection = 0;
            if (diag_before == diag_after) atomic_fetch_add(&diag_without_collection, 1);
            unsigned long miss = atomic_fetch_add(&diag_misses, 1) + 1;
            if (miss <= 8 || miss % 128 == 0 || !Heap_isGrowingPossible(heap, 1)) {
                fprintf(stderr, "DIAG alloc-miss n=%lu size=%u blocks=%u free=%lu own_recycled=%lu cursor_bytes=%td overflow_bytes=%td collected=%d epoch_delta=%lu without_collection=%lu list_empty=%d\\n",
                        miss, size, heap->blockCount,
                        (unsigned long)allocator->blockAllocator->freeBlockCount,
                        (unsigned long)allocator->recycledBlockCount,
                        (char *)allocator->limit - (char *)allocator->cursor,
                        (char *)allocator->largeLimit - (char *)allocator->largeCursor,
                        diag_collected, diag_after - diag_before,
                        atomic_load(&diag_without_collection), allocator->recycledBlocks.first == NULL);
            }
            // A small object can always fit in a single free block''')
if a.retry_collection:
    replace('Heap.c', '    if (!Synchronizer_acquire())\n        return;',
            '    while (!Synchronizer_acquire()) {\n        // Diagnostic candidate: guarantee a collection before reporting exhaustion.\n    }')
if a.retry_allocation:
    replace('Heap.h', 'void Heap_Collect(Heap *heap, Stack *stack);',
            'bool Heap_Collect(Heap *heap, Stack *stack);')
    replace('Heap.c', 'void Heap_Collect(Heap *heap, Stack *stack) {',
            'bool Heap_Collect(Heap *heap, Stack *stack) {')
    replace('Heap.c', '    if (!Synchronizer_acquire())\n        return;',
            '    if (!Synchronizer_acquire())\n        return false;')
    replace('Heap.c', '                (end_ns - start_ns) / 1000000);\n}',
            '                (end_ns - start_ns) / 1000000);\n    return true;\n}')
    replace('Allocator.c', '        Heap_Collect(heap, &stack);',
            '        // A lost GC race may return before another collection starts.\n        // Retry allocation/collection instead of growing or reporting OOM.\n        if (!Heap_Collect(heap, &stack))\n            continue;')
a.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(a.source) as source, zipfile.ZipFile(a.output, 'x', zipfile.ZIP_DEFLATED) as out:
    for info in source.infolist():
        name = info.filename.removeprefix(prefix)
        out.writestr(info, changes[name].encode() if info.filename.startswith(prefix) and name in changes else source.read(info))
patch = ''.join(''.join(difflib.unified_diff(originals[n].splitlines(True), changes[n].splitlines(True), fromfile='a/'+prefix+n, tofile='b/'+prefix+n)) for n in changes)
a.output.with_suffix('.patch').write_text(patch)
a.output.with_suffix('.json').write_text(json.dumps({'collector':a.collector, 'retry_collection':a.retry_collection, 'retry_allocation':a.retry_allocation, 'diagnostics':not a.no_diagnostics, 'source':str(a.source), 'source_sha256':hashlib.sha256(a.source.read_bytes()).hexdigest(), 'output_sha256':hashlib.sha256(a.output.read_bytes()).hexdigest()}, indent=2)+'\n')
print(a.output)
