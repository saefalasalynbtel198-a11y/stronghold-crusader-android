#!/usr/bin/env bash
# Combines the CI-built/fetched inputs into the runtime package imported by the app.
# Inputs (under runtime/out/): bin/box64, bin/shc-sysprobe, bin/shc-glibctest, glibc-aarch64/, x64lib/, licenses/, downloads/<wine>.tar.xz, downloads/cnc-ddraw-*.zip, userland/ (+ userland-lib/)
# Output: runtime/out/shc-runtime-<version>.zip  (+ .sha256)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
. "$here/versions.env"
o="$here/out"
stage="$o/stage"
version="r${SHC_RUNTIME_REVISION}-box64-${BOX64_REF}-wine-${WINE_VERSION}-cncddraw-${CNC_DDRAW_TAG}-${DEBIAN_SUITE}-glibc${GLIBC_VERSION}android"
rm -rf "$stage"
mkdir -p "$stage/bin" "$stage/lib/x86_64-linux-gnu" "$stage/wine" "$stage/cnc-ddraw" "$stage/licenses"

install -m 0755 "$o/bin/box64" "$stage/bin/box64"
install -m 0755 "$o/bin/shc-sysprobe" "$stage/bin/shc-sysprobe"
install -m 0755 "$o/bin/shc-glibctest" "$stage/bin/shc-glibctest"
cp -a "$o/x64lib/." "$stage/lib/x86_64-linux-gnu/"
cp -a "$o/userland-lib" "$stage/lib/aarch64-linux-gnu"
# Replace Debian's glibc with the Android-patched build (runtime/glibc-android). Only ELF shared objects are
# taken (glibc also installs linker scripts named *.so), flattened into the single library directory.
g="$o/glibc-aarch64"
[ -d "$g" ] || { echo "::error::patched glibc missing ($g)"; exit 1; }
n=0
while IFS= read -r -d '' f; do
  if head -c 4 "$f" | grep -q $'\x7fELF'; then cp -f "$f" "$stage/lib/aarch64-linux-gnu/$(basename "$f")"; n=$((n+1)); fi
done < <(find "$g" \( -path '*/gconv/*' -o -path '*/libexec/*' -o -path '*/audit/*' \) -prune -o -type f -name '*.so*' -print0)
ldsrc=$(find "$g" -name 'ld-linux-aarch64.so.1' -type f | head -1)
cmp -s "$ldsrc" "$stage/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" || { echo "::error::patched loader not installed"; exit 1; }
chmod 0755 "$stage/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
echo "patched glibc: $n shared objects installed"
tar -xJf "$o/downloads/$WINE_ASSET" -C "$stage/wine" --strip-components=1
unzip -q -j -o "$o/downloads/cnc-ddraw-${CNC_DDRAW_TAG}.zip" 'ddraw.dll' -d "$stage/cnc-ddraw"
# Wine: use "<prefix>/.wineserver<uid>" instead of /tmp (absent on Android). See patch-wine-tmpdir.py.
python3 -I "$here/scripts/patch-wine-tmpdir.py" "$stage/wine" | tee "$o/wine-tmpdir-patch.txt"
# Windows smoke-test programs (BUILD 3 phase 2).
mkdir -p "$stage/win"
install -m 0644 "$o/win/shc-pe-smoke-i686.exe" "$o/win/shc-pe-smoke-x86_64.exe" "$stage/win/"

# Licences and source pointers (Wine is LGPL: point to the exact source).
cp -a "$o/licenses/." "$stage/licenses/"
cp "$here/glibc-android/android-seccomp.patch" "$stage/licenses/glibc-android-seccomp.patch"
cp "$here/glibc-android/README.md" "$stage/licenses/glibc-android-README.md"
{ echo "Wine modifications in this package (LGPL-2.1-or-later):"; sed -n '2,15p' "$here/scripts/patch-wine-tmpdir.py"; cat "$o/wine-tmpdir-patch.txt"; } > "$stage/licenses/WINE-MODIFICATIONS.txt"
mkdir -p "$stage/licenses/debian"
( cd "$o/userland/usr/share/doc" && for d in *; do [ -f "$d/copyright" ] && cp "$d/copyright" "$stage/licenses/debian/$d.copyright"; done ) || true
cat > "$stage/licenses/SOURCES.txt" <<SRC
Box64 $BOX64_REF (MIT): https://github.com/ptitSeb/box64/tree/$BOX64_REF
  lib/x86_64-linux-gnu/: x86-64 runtime libraries from Box64 x64lib/ (GCC libraries: GPL-3.0 with GCC Runtime Library Exception)
Wine $WINE_VERSION (LGPL-2.1-or-later), binary build by Kron4ek: https://github.com/Kron4ek/Wine-Builds/releases/tag/$WINE_VERSION
  Wine source: https://dl.winehq.org/wine/source/ (version $WINE_VERSION)
cnc-ddraw $CNC_DDRAW_TAG (MIT): https://github.com/FunkyFr3sh/cnc-ddraw/releases/tag/$CNC_DDRAW_TAG
Debian $DEBIAN_SUITE arm64 libraries: https://packages.debian.org/$DEBIAN_SUITE/ (copyright files in debian/)
glibc $GLIBC_VERSION (LGPL-2.1-or-later) rebuilt from $GLIBC_SRC_URL (sha256 $GLIBC_SRC_SHA256)
  with patch licenses/glibc-android-seccomp.patch (see licenses/glibc-android-README.md)
SRC

echo "$version" > "$stage/RUNTIME_VERSION"

for f in bin/box64 bin/shc-sysprobe bin/shc-glibctest win/shc-pe-smoke-i686.exe win/shc-pe-smoke-x86_64.exe lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 lib/x86_64-linux-gnu/libgcc_s.so.1 wine/bin/wine cnc-ddraw/ddraw.dll RUNTIME_VERSION; do
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
