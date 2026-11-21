#!/usr/bin/env bash
# Combines the CI-built/fetched inputs into the runtime package imported by the app.
# Inputs (under runtime/out/): bin/box64, bin/shc-sysprobe, bin/shc-glibctest, bin/shc-ptrace-watch, win/*.exe, glibc-aarch64/, x64lib/, licenses/, downloads/<wine>.tar.xz, downloads/cnc-ddraw-*.zip, userland/ (+ userland-lib/)
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
install -m 0755 "$o/bin/shc-ptrace-watch" "$stage/bin/shc-ptrace-watch"
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
install -m 0644 "$o/win/shc-pe-smoke-i686.exe" "$o/win/shc-pe-smoke-x86_64.exe" "$o/win/shc-gui-smoke.exe" "$stage/win/"

# X server (runtime r6, BUILD 3 phase 3): Debian's Xvfb with its XKB and lock-file paths pointed at its working
# directory (Android has no /usr/bin, /var/lib, /tmp), xkbcomp with a wrapper that starts it through the runtime's
# loader, xkb data (must stay writable: xkbcomp writes the compiled keymap into it), xdpyinfo for diagnostics.
u="$o/userland"
mkdir -p "$stage/x11/bin" "$stage/x11/share/X11"
python3 -I "$here/scripts/patch-xvfb-paths.py" "$u/usr/bin/Xvfb" "$stage/x11/Xvfb" | tee "$o/xvfb-patch.txt"
chmod 0755 "$stage/x11/Xvfb"
install -m 0755 "$u/usr/bin/xkbcomp" "$stage/x11/bin/xkbcomp"
install -m 0755 "$u/usr/bin/xdpyinfo" "$stage/x11/bin/xdpyinfo"
install -m 0755 "$here/x11/xkbcomp" "$stage/x11/xkbcomp"
cp -a "$u/usr/share/X11/xkb" "$stage/x11/share/X11/xkb"
# every library the X programs need, directly or through other libraries, must be in the runtime (Mesa was
# removed from the userland)
python3 -I - "$stage/lib/aarch64-linux-gnu" "$stage/x11/Xvfb" "$stage/x11/bin/xkbcomp" "$stage/x11/bin/xdpyinfo" <<'PY' || exit 1
import os, re, subprocess, sys
libdir, todo, seen, missing = sys.argv[1], list(sys.argv[2:]), set(), []
while todo:
    f = todo.pop()
    out = subprocess.run(['readelf', '-d', f], capture_output=True, text=True).stdout
    for lib in re.findall(r'Shared library: \[(.*?)\]', out):
        if lib in seen:
            continue
        seen.add(lib)
        p = os.path.join(libdir, lib)
        if os.path.exists(p):
            todo.append(os.path.realpath(p))
        else:
            missing.append('%s (needed by %s)' % (lib, os.path.basename(f)))
for m in missing:
    print('::error::missing from the runtime: ' + m)
print('X programs: %d libraries resolved, %d missing' % (len(seen), len(missing)))
sys.exit(1 if missing else 0)
PY
echo "X programs: all linked libraries present"

# Licences and source pointers (Wine is LGPL: point to the exact source).
cp -a "$o/licenses/." "$stage/licenses/"
cp "$here/glibc-android/android-seccomp.patch" "$stage/licenses/glibc-android-seccomp.patch"
cp "$here/glibc-android/README.md" "$stage/licenses/glibc-android-README.md"
{ echo "x11/Xvfb: Debian's xvfb (MIT/X11 licence) with these string literals changed in the binary (see"
  echo "scripts/patch-xvfb-paths.py in the project repository); no code changed:"; sed -n '2,12p' "$here/scripts/patch-xvfb-paths.py"
  cat "$o/xvfb-patch.txt"; } > "$stage/licenses/XVFB-MODIFICATIONS.txt"
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
  x11/: Debian xvfb (path strings patched: XVFB-MODIFICATIONS.txt), x11-xkb-utils (xkbcomp), xkb-data, x11-utils (xdpyinfo)
glibc $GLIBC_VERSION (LGPL-2.1-or-later) rebuilt from $GLIBC_SRC_URL (sha256 $GLIBC_SRC_SHA256)
  with patch licenses/glibc-android-seccomp.patch (see licenses/glibc-android-README.md)
SRC

echo "$version" > "$stage/RUNTIME_VERSION"

for f in bin/box64 bin/shc-sysprobe bin/shc-glibctest bin/shc-ptrace-watch win/shc-pe-smoke-i686.exe win/shc-pe-smoke-x86_64.exe win/shc-gui-smoke.exe x11/Xvfb x11/xkbcomp x11/bin/xkbcomp x11/bin/xdpyinfo x11/share/X11/xkb/rules/evdev lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 lib/x86_64-linux-gnu/libgcc_s.so.1 wine/bin/wine cnc-ddraw/ddraw.dll RUNTIME_VERSION; do
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
for x in x11/Xvfb x11/xkbcomp x11/bin/xkbcomp; do
  grep -q $'^x\t'"$x"'$' <<<"$manifest" || { echo "::error::$x not marked executable in manifest"; exit 1; }
done
