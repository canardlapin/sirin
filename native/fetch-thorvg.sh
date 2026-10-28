#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
test "$(uname -s)" = Darwin && test "$(uname -m)" = arm64 || { echo 'ThorVG probe recipe supports macOS arm64 only' >&2; exit 1; }
available=$(df -Pk . | awk 'NR==2 {print $4}')
test "$available" -ge 2097152 || { echo 'Need 2 GiB free before building ThorVG' >&2; exit 1; }
dir="$PWD/vendor/thorvg"
mkdir -p "$dir/pkgconfig"
archive="$dir/thorvg-1.1.3.tar.xz"
test -f "$archive" || curl -fL https://github.com/thorvg/thorvg/releases/download/v1.1.3/thorvg-1.1.3.tar.xz -o "$archive"
echo "ef97785ed6d0f25ab84d6b4a0eed4325c68c4d80b1c2a61ea0b4029192fea746  $archive" | shasum -a 256 -c -
test -d "$dir/thorvg-1.1.3" || tar -xJf "$archive" -C "$dir"
wgpu="$PWD/vendor/wgpu-macos-aarch64"
test -f "$wgpu/lib/libwgpu_native.dylib" || { echo 'Fetch the existing wgpu artifact first' >&2; exit 1; }
cat > "$dir/pkgconfig/wgpu_native.pc" <<EOF
Name: wgpu_native
Description: Sirin pinned wgpu-native v29.0.1.1
Version: 29.0.1.1
Libs: -L$wgpu/lib -lwgpu_native -Wl,-rpath,$wgpu/lib
Cflags: -I$wgpu/include -I$wgpu/include/webgpu
EOF
export PKG_CONFIG_PATH="$dir/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
if test ! -d "$dir/build"; then
  meson setup "$dir/build" "$dir/thorvg-1.1.3" --prefix="$dir/install" --libdir=lib \
    --buildtype=release --default-library=static -Dengines=wg -Dloaders= -Dbindings=capi \
    -Dthreads=false -Dsimd=false -Dtools= -Dtests=false -Dstatic=true -Dfile=false -Dextra=
fi
meson setup --reconfigure --clearcache "$dir/build" "$dir/thorvg-1.1.3" >/dev/null
meson compile -C "$dir/build" -j 4
meson install -C "$dir/build"
echo 'ThorVG 1.1.3 installed for the isolated comparison probe'
