#!/usr/bin/env bash
# Uses hidden SDL windows to check the Cocoa field editor without showing or activating a window.
set -euo pipefail
cd "$(dirname "$0")/../.."
v="$PWD/native/vendor"
output="$PWD/desktop/target/native/text_input_owner_test"
mkdir -p "$(dirname "$output")"
clang -Wall -Wextra -Werror -fobjc-arc -O2 desktop/native/tests/text_input_owner_test.m \
 -F"$v/SDL3.xcframework/macos-arm64_x86_64" -framework SDL3 \
 -Wl,-rpath,"$v/SDL3.xcframework/macos-arm64_x86_64" -framework Cocoa -o "$output"
"$output" "$@"
