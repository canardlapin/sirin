#!/usr/bin/env bash
# Pinned minimal font stack. Called via native/fetch-macos.sh --text.
# Source builds are deliberate: no dependency on a user's Homebrew font libraries.
set -euo pipefail
cd "$(dirname "$0")"
case "$(uname -s)" in Darwin|Linux) ;; *) echo "text artifacts: Windows build not qualified" >&2; exit 1 ;; esac
for tool in meson ninja python3; do command -v "$tool" >/dev/null || { echo "$tool required" >&2; exit 1; }; done
available_kb=$(df -Pk . | awk 'END {print $4}')
[ "$available_kb" -ge 2097152 ] || { echo "text source build requires at least 2 GiB free" >&2; exit 1; }
echo "text source build: ${available_kb} KiB free (checked before building)"
mkdir -p vendor/text-sources
fetch() {
  [ -f "$2" ] || curl -fsSL -o "$2" "$1"
  python3 - "$2" "$3" <<'PY'
import hashlib,sys
from pathlib import Path
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == sys.argv[2], 'artifact hash mismatch'
PY
}
hb=863d3f7787c6df18d20e4535c5906bf3eb803bd5 # HarfBuzz 14.5.0
ft=37515cf56ba3349656049edbed89ffc6f6466f2d # FreeType 2.15.0
fetch "https://codeload.github.com/harfbuzz/harfbuzz/tar.gz/$hb" vendor/text-sources/harfbuzz.tar.gz \
  35d6211b9310646c8d6b7c06d697439efd26d787ec8fff2508e6b936836ca492
fetch "https://codeload.github.com/freetype/freetype/tar.gz/$ft" vendor/text-sources/freetype.tar.gz \
  9efee8b0508e142f38d1c7e2e192d3e5041aa4bbf6c2ab1a901518211f76a914
prefix="$PWD/vendor/text"
stamp="$prefix/recipe-v1-$(uname -s)-$(uname -m)"
if [ ! -f "$stamp" ]; then
  tar xzf vendor/text-sources/harfbuzz.tar.gz -C vendor/text-sources
  tar xzf vendor/text-sources/freetype.tar.gz -C vendor/text-sources
  meson setup --reconfigure vendor/text-ft-build "vendor/text-sources/freetype-$ft" \
    --prefix="$prefix" --libdir=lib --buildtype=release --default-library=static --wrap-mode=nofallback \
    -Dbrotli=disabled -Dbzip2=disabled -Dharfbuzz=disabled -Dmmap=disabled -Dpng=disabled \
    -Dzlib=disabled -Dhvf=disabled -Dtests=disabled
  meson compile -C vendor/text-ft-build -j 2
  meson install -C vendor/text-ft-build
  meson setup --reconfigure vendor/text-hb-build "vendor/text-sources/harfbuzz-$hb" \
    --prefix="$prefix" --libdir=lib --buildtype=release --default-library=static --wrap-mode=nofallback \
    -Dauto_features=disabled -Dtests=disabled -Dutilities=disabled -Ddocs=disabled \
    -Dfreetype=disabled -Dglib=disabled -Dgobject=disabled -Dicu=disabled \
    -Dsubset=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled
  meson compile -C vendor/text-hb-build -j 2
  meson install -C vendor/text-hb-build
  mkdir -p "$prefix/licenses"
  cp "vendor/text-sources/harfbuzz-$hb/COPYING" "$prefix/licenses/HarfBuzz-COPYING"
  cp "vendor/text-sources/freetype-$ft/LICENSE.TXT" "$prefix/licenses/FreeType-LICENSE.TXT"
  cp "vendor/text-sources/freetype-$ft/docs/FTL.TXT" "$prefix/licenses/FreeType-FTL.TXT"
  printf '%s\n%s\n' "$hb" "$ft" > "$stamp"
fi
echo "font artifacts ready: $prefix (HarfBuzz 14.5.0 / FreeType 2.15.0)"
