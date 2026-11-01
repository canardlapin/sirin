#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output="$PWD/desktop/target/native/frame_inventory_test"
mkdir -p "$(dirname "$output")"
clang -std=c11 -Wall -Wextra -Werror -O2 -pthread desktop/native/tests/frame_inventory_test.c -o "$output"
"$output"
