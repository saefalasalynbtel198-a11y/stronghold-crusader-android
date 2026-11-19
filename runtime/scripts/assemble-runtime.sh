#!/usr/bin/env bash
# Combines the CI-built/fetched inputs into the runtime package imported by the app.
# Inputs (under runtime/out/): bin/box64, x64lib/, licenses/, downloads/<wine>.tar.xz, downloads/cnc-ddraw-*.zip, userland/ (+ userland-lib/)
# Output: runtime/out/shc-runtime-<version>.zip  (+ .sha256)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
. "$here/versions.env"
o="$here/out"
stage="$o/stage"
version="r${SHC_RUNTIME_REVISION}-box64-${BOX64_REF}-wine-${WINE_VERSION}-cncddraw-${CNC_DDRAW_TAG}-${DEBIAN_SUITE}"
rm -rf "$stage"
mkdir -p "$stage/bin" "$stage/lib/x86_64-linux-gnu" "$stage/wine" "$stage/cnc-ddraw" "$stage/licenses"

install -m 0755 "$o/bin/box64" "$stage/bin/box64"
cp -a "$o/x64lib/." "$stage/lib/x86_64-linux-gnu/"
cp -a "$o/userland-lib" "$stage/lib/aarch64-linux-gnu"
tar -xJf "$o/downloads/$WINE_ASSET" -C "$stage/wine" --strip-components=1
unzip -q -j -o "$o/downloads/cnc-ddraw-${CNC_DDRAW_TAG}.zip" 'ddraw.dll' -d "$stage/cnc-ddraw"

# Licences and source pointers (Wine is LGPL: point to the exact source).
cp -a "$o/licenses/." "$stage/licenses/"
mkdir -p "$stage/licenses/debian"
( cd "$o/userland/usr/share/doc" && for d in *; do [ -f "$d/copyright" ] && cp "$d/copyright" "$stage/licenses/debian/$d.copyright"; done ) || true
cat > "$stage/licenses/SOURCES.txt" <<SRC
Box64 $BOX64_REF (MIT): https://github.com/ptitSeb/box64/tree/$BOX64_REF
  lib/x86_64-linux-gnu/: x86-64 runtime libraries from Box64 x64lib/ (GCC libraries: GPL-3.0 with GCC Runtime Library Exception)
Wine $WINE_VERSION (LGPL-2.1-or-later), binary build by Kron4ek: https://github.com/Kron4ek/Wine-Builds/releases/tag/$WINE_VERSION
  Wine source: https://dl.winehq.org/wine/source/ (version $WINE_VERSION)
cnc-ddraw $CNC_DDRAW_TAG (MIT): https://github.com/FunkyFr3sh/cnc-ddraw/releases/tag/$CNC_DDRAW_TAG
Debian $DEBIAN_SUITE arm64 libraries: https://packages.debian.org/$DEBIAN_SUITE/ (copyright files in debian/)
SRC

echo "$version" > "$stage/RUNTIME_VERSION"

for f in bin/box64 lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 lib/x86_64-linux-gnu/libgcc_s.so.1 wine/bin/wine cnc-ddraw/ddraw.dll RUNTIME_VERSION; do
  [ -e "$stage/$f" ] || { echo "::error::missing $f in staged runtime"; exit 1; }
done

zip_out="$o/shc-runtime-${version}.zip"
rm -f "$zip_out"
python3 -I "$here/scripts/package_runtime.py" "$stage" "$zip_out" "$version"
( cd "$o" && sha256sum "$(basename "$zip_out")" > "$(basename "$zip_out").sha256" )
ls -l "$zip_out"
manifest=$(unzip -p "$zip_out" SHC_RUNTIME_MANIFEST)
grep -q $'^x\tlib/aarch64-linux-gnu/ld-linux-aarch64.so.1$' <<<"$manifest" \
  || { echo "::error::loader not marked executable in manifest"; exit 1; }
grep -q $'^x\tbin/box64$' <<<"$manifest" \
  || { echo "::error::box64 not marked executable in manifest"; exit 1; }
