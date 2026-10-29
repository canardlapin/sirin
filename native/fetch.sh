#!/usr/bin/env bash
# Fetch the pinned native artifacts for the current platform into native/vendor/.
#
#   macOS arm64    wgpu-native prebuilt + SDL3 xcframework (official release)
#   Linux x86_64   wgpu-native prebuilt + SDL3 built from the release tarball
#                  (console build: headers and library only, no X11/Wayland video)
#   Windows x86_64 wgpu-native prebuilt (MSVC) + SDL3 VC development package
#
# Every download is checked against a pinned SHA-256.
set -euo pipefail
if [ "${1:-}" = "--sirin" ]; then
  exec bash "$(dirname "$0")/fetch-sirin.sh"
fi
if [ "${1:-}" = "--text" ]; then
  exec "$(dirname "$0")/fetch-text.sh"
fi
if [ "${1:-}" = "--unicode" ]; then
  exec "$(dirname "$0")/fetch-unicode.sh"
fi
if [ "${1:-}" = "--thorvg" ]; then
  exec bash "$(dirname "$0")/fetch-thorvg.sh"
fi
if [ "$#" -ne 0 ]; then echo "usage: $0 [--text|--unicode|--thorvg|--sirin]" >&2; exit 1; fi
cd "$(dirname "$0")"
mkdir -p vendor && cd vendor

WGPU=v29.0.1.1
SDL=3.4.18

fetch() { # url file sha256
  [ -f "$2" ] || curl -fsSL -o "$2" "$1"
  if command -v sha256sum >/dev/null; then
    echo "$3  $2" | sha256sum -c -
  else
    echo "$3  $2" | shasum -a 256 -c -
  fi
}

wgpu_url() { echo "https://github.com/gfx-rs/wgpu-native/releases/download/$WGPU/$1"; }
sdl_url()  { echo "https://github.com/libsdl-org/SDL/releases/download/release-$SDL/$1"; }

case "$(uname -s)" in
  Darwin)
    fetch "$(wgpu_url wgpu-macos-aarch64-release.zip)" wgpu-macos-aarch64-release.zip \
      a5797a37b1adf720bcd5dcffb291edbbd5b7b14be0a3874c28e6393a655a7a3e
    fetch "$(sdl_url SDL3-$SDL.dmg)" SDL3-$SDL.dmg \
      c1adf3da91108dbc3e4205da5dacebc203085d283fa89e86eb2c889abd03a853
    [ -d wgpu-macos-aarch64 ] || unzip -q wgpu-macos-aarch64-release.zip -d wgpu-macos-aarch64
    if [ ! -d SDL3.xcframework ]; then
      mnt=$(mktemp -d)
      hdiutil attach -nobrowse -readonly -mountpoint "$mnt" SDL3-$SDL.dmg >/dev/null
      cp -R "$mnt/SDL3.xcframework" .
      hdiutil detach "$mnt" >/dev/null
    fi
    ;;
  Linux)
    fetch "$(wgpu_url wgpu-linux-x86_64-release.zip)" wgpu-linux-x86_64-release.zip \
      95a4d90c071005a98d03eab348beaa6b07e16eb00d1dcdb9f8348f75eb97ec5a
    fetch "$(sdl_url SDL3-$SDL.tar.gz)" SDL3-$SDL.tar.gz \
      9c75cf16330322c217dedd2e0609f1124f1b54b8633e763467b4684d0f4334a3
    [ -d wgpu-linux-x86_64 ] || unzip -q wgpu-linux-x86_64-release.zip -d wgpu-linux-x86_64
    if [ ! -d sdl3-linux ]; then
      tar xzf SDL3-$SDL.tar.gz
      cmake -S SDL3-$SDL -B sdl3-build -DCMAKE_BUILD_TYPE=Release \
        -DSDL_UNIX_CONSOLE_BUILD=ON -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF \
        -DCMAKE_INSTALL_PREFIX="$PWD/sdl3-linux" -DCMAKE_INSTALL_LIBDIR=lib >/dev/null
      cmake --build sdl3-build --parallel >/dev/null
      cmake --install sdl3-build >/dev/null
    fi
    ;;
  MINGW*|MSYS*|CYGWIN*)
    fetch "$(wgpu_url wgpu-windows-x86_64-msvc-release.zip)" wgpu-windows-x86_64-msvc-release.zip \
      7e67d7445c42aeb85e30f88930fd8d7d83ee769e3390aeb1ada75ebf3cf78132
    fetch "$(sdl_url SDL3-devel-$SDL-VC.zip)" SDL3-devel-$SDL-VC.zip \
      78d84602ae616cfe26b33a73b7d3b9b6b56e8a6650e53d92c9c6ca938f747acd
    [ -d wgpu-windows-x86_64 ] || unzip -q wgpu-windows-x86_64-msvc-release.zip -d wgpu-windows-x86_64
    [ -d SDL3-$SDL ] || unzip -q SDL3-devel-$SDL-VC.zip
    ;;
  *) echo "unsupported platform: $(uname -s)" >&2; exit 1 ;;
esac
echo "native/vendor ready for $(uname -s)"
