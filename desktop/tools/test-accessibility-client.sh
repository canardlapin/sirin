#!/usr/bin/env bash
# Uses hidden SDL windows and in-process Cocoa AX objects; never activates a user's window.
set -euo pipefail
cd "$(dirname "$0")/../.."
v="$PWD/native/vendor"
output="$PWD/desktop/target/native/accessibility_client_test"
mkdir -p "$(dirname "$output")"
clang -Wall -Wextra -Werror -fobjc-arc -O2 desktop/native/tests/accessibility_client_test.m \
 -I"$v/sirin-sources/accesskit-c-0.23.1/include" \
 -F"$v/SDL3.xcframework/macos-arm64_x86_64" -framework SDL3 \
 -Wl,-rpath,"$v/SDL3.xcframework/macos-arm64_x86_64" \
 "$v/sirin/libsirin_native.a" -framework Cocoa -framework Metal -framework QuartzCore \
 -framework Security -framework IOKit -lc++ -o "$output"
"$output"
