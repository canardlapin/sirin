#!/usr/bin/env bash
# Fetch the pinned macOS native artifacts used by the probes into native/vendor/.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p vendor && cd vendor

fetch() { # url file sha256
  [ -f "$2" ] || curl -fL -o "$2" "$1"
  echo "$3  $2" | shasum -a 256 -c -
}

fetch https://github.com/gfx-rs/wgpu-native/releases/download/v29.0.1.1/wgpu-macos-aarch64-release.zip \
  wgpu-macos-aarch64-release.zip a5797a37b1adf720bcd5dcffb291edbbd5b7b14be0a3874c28e6393a655a7a3e
fetch https://github.com/libsdl-org/SDL/releases/download/release-3.4.18/SDL3-3.4.18.dmg \
  SDL3-3.4.18.dmg c1adf3da91108dbc3e4205da5dacebc203085d283fa89e86eb2c889abd03a853

[ -d wgpu-macos-aarch64 ] || unzip -q wgpu-macos-aarch64-release.zip -d wgpu-macos-aarch64
if [ ! -d SDL3.xcframework ]; then
  mnt=$(mktemp -d)
  hdiutil attach -nobrowse -readonly -mountpoint "$mnt" SDL3-3.4.18.dmg >/dev/null
  cp -R "$mnt/SDL3.xcframework" .
  hdiutil detach "$mnt" >/dev/null
fi
echo "native/vendor ready"
