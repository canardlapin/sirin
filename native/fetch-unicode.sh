#!/usr/bin/env bash
# Isolated P0.8 artifacts, invoked through native/fetch-macos.sh --unicode.
set -euo pipefail
cd "$(dirname "$0")"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)
    target=aarch64-apple-darwin
    full_sha=2e5f3526ecd72ec99700e2c12651320f4a3800633e1e105abc6d0f317228814d ;;
  Darwin-x86_64)
    target=x86_64-apple-darwin
    full_sha=6516c5083ae4493aa81afd7cad334a2c70587add286ad29033d593252c2c4d69 ;;
  *) echo "Unicode probe artifacts are currently qualified only on macOS" >&2; exit 1 ;;
esac
available_kb=$(df -Pk . | awk 'END {print $4}')
[ "$available_kb" -ge 2097152 ] || { echo "Unicode probe build requires at least 2 GiB free" >&2; exit 1; }
echo "Unicode build: ${available_kb} KiB free before source build"
mkdir -p vendor/unicode
fetch() {
  [ -f "$2" ] || curl -fsSL -o "$2" "$1"
  python3 - "$2" "$3" <<'PY'
from pathlib import Path
import hashlib,sys
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == sys.argv[2], 'Unicode artifact hash mismatch'
PY
}
commit=031d9e89f6a3fd26a14491e090729d46094328fd
fetch "https://codeload.github.com/unicode-org/icu4x/tar.gz/$commit" vendor/unicode/icu4x-source.tar.gz \
  325cc38db7242e614135e55d92cb19b4e693e5953069cd3180581d97c055aaf8
fetch "https://github.com/unicode-org/icu4x/releases/download/icu%402.4.0/icu4x-2-static-with_data-$target" \
  vendor/unicode/libicu4x_full.a "$full_sha"
[ -d "vendor/unicode/icu4x-$commit" ] || tar xzf vendor/unicode/icu4x-source.tar.gz -C vendor/unicode
# ICU4X is the only Rust library in this probe executable. This is not the
# production combined host, and does not waive the one-Rust-library host gate.
cargo build --manifest-path unicode-probe/Cargo.toml --release --locked --target-dir "$PWD/vendor/unicode/target"
cp vendor/unicode/target/release/libsirin_unicode_probe.a vendor/unicode/libicu4x_probe.a
wc -c vendor/unicode/libicu4x_full.a vendor/unicode/libicu4x_probe.a
