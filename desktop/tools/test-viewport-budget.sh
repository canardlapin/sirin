#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output="$PWD/desktop/target/native/viewport_budget_test"
mkdir -p "$(dirname "$output")"
clang -std=c11 -Wall -Wextra -Werror -O2 -pthread desktop/native/tests/viewport_budget_test.c -o "$output"
"$output"
