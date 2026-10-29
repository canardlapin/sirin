#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
test "$(uname -s)-$(uname -m)" = Darwin-arm64 || { echo 'Combined runtime currently supports macOS arm64' >&2; exit 1; }
available_kb=$(df -Pk . | awk 'END {print $4}')
test "$available_kb" -ge 8388608 || { echo 'Combined runtime build requires 8 GiB free' >&2; exit 1; }
echo "Combined runtime: $available_kb KiB free before build"
mkdir -p vendor/sirin-sources vendor/sirin
fetch() {
 test -f "$2" || curl -fLsS "$1" -o "$2"
 python3 - "$2" "$3" <<'PY'
from pathlib import Path
import hashlib,sys
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()==sys.argv[2],sys.argv[1]
PY
}
fetch https://codeload.github.com/gfx-rs/wgpu-native/tar.gz/refs/tags/v29.0.1.1 vendor/sirin-sources/wgpu.tar.gz 6008651e1d10022d4ded15b69405b4ba88577be5d623938ba98ad241b2cbf818
fetch https://github.com/AccessKit/accesskit-c/releases/download/0.23.1/accesskit-c-0.23.1.zip vendor/sirin-sources/accesskit.zip 35b7ca8a6f1e038b5da35e1e9e5a0adaed9bfcf21e1496d29598fbbadcc7043f
test -d vendor/sirin-sources/wgpu-native-29.0.1.1 || tar xzf vendor/sirin-sources/wgpu.tar.gz -C vendor/sirin-sources
test -d vendor/sirin-sources/accesskit-c-0.23.1 || unzip -q vendor/sirin-sources/accesskit.zip -d vendor/sirin-sources
# Upstream packages expose only final native artifacts; rlib enables one graph.
python3 - <<'PY'
from pathlib import Path
for name in ['wgpu-native-29.0.1.1','accesskit-c-0.23.1']:
 p=Path('vendor/sirin-sources')/name/'Cargo.toml'
 s=p.read_text().replace('["cdylib", "staticlib"]','["rlib"]')
 p.write_text(s)
PY
mkdir -p vendor/sirin-sources/wgpu-native-29.0.1.1/ffi/webgpu-headers
python3 - <<'CHECK_HEADER'
from pathlib import Path
import hashlib
p=Path('vendor/wgpu-macos-aarch64/include/webgpu/webgpu.h')
assert hashlib.sha256(p.read_bytes()).hexdigest()=='a483031c3fed05ea5dd1c74082a71676c46c5b2b820ccca10da515c033efc997',p
CHECK_HEADER
cp vendor/wgpu-macos-aarch64/include/webgpu/webgpu.h vendor/sirin-sources/wgpu-native-29.0.1.1/ffi/webgpu-headers/webgpu.h
# Sources and header are the same pinned versions used by the independent probes.
cargo build --manifest-path sirin-native/Cargo.toml --release --locked --target-dir "$PWD/vendor/sirin/target"
cp vendor/sirin/target/release/libsirin_native.a vendor/sirin/libsirin_native.a
