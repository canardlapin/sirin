#!/bin/sh
# Builds and runs the pure-C P0.7 harness under ThreadSanitizer.
# Usage: probes/threads/tsan/run.sh [n_per_case]   (default 100000)
# TSAN_OPTIONS=halt_on_error=1 makes any reported race fail the run.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../src/main/resources/scala-native"
out="${TMPDIR:-/tmp}/sirin-mailbox-tsan"
clang -std=c11 -g -O1 -fsanitize=thread -fno-omit-frame-pointer -Wall -Wextra -Werror \
  -DSIRIN_NO_SCALANATIVE_GC -I"$src" \
  "$src/sirin_mailbox.c" "$src/sirin_guard.c" "$src/sirin_foreign.c" "$here/mailbox_tsan.c" \
  -o "$out"
TSAN_OPTIONS="halt_on_error=1 second_deadlock_stack=1 ${TSAN_OPTIONS:-}" "$out" "${1:-100000}"
