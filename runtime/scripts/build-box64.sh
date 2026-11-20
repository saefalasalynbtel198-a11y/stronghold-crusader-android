#!/usr/bin/env bash
# Cross-compiles Box64 for aarch64 Linux (glibc) with the ARM64 dynarec.
# Output: runtime/out/bin/box64
# Requires: git, cmake, ninja, gcc-aarch64-linux-gnu
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
. "$here/versions.env"
work="${WORK_DIR:-$here/out/work}"
out="$here/out"
mkdir -p "$work" "$out/bin"

if [ ! -d "$work/box64/.git" ]; then
  git clone --depth 1 --branch "$BOX64_REF" https://github.com/ptitSeb/box64.git "$work/box64"
fi
cmake -S "$work/box64" -B "$work/box64-build" -G Ninja \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
  -DCMAKE_ASM_COMPILER=aarch64-linux-gnu-gcc \
  -DARM_DYNAREC=ON \
  -DARM64=1 \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--dynamic-linker=$SHC_INTERP_PLACEHOLDER"
cmake --build "$work/box64-build" --parallel
cp "$work/box64-build/box64" "$out/bin/box64"
aarch64-linux-gnu-strip --strip-debug "$out/bin/box64"
mkdir -p "$out/licenses" && cp "$work/box64/LICENSE" "$out/licenses/box64-LICENSE"
# x86-64 runtime libraries Box64 ships for emulated programs (libgcc_s, libstdc++, ...). Android has no
# /lib/x86_64-linux-gnu, so they must travel inside the package (BOX64_LD_LIBRARY_PATH points at them).
[ -d "$work/box64/x64lib" ] || { echo "::error::box64 x64lib/ not found"; exit 1; }
rm -rf "$out/x64lib" && mkdir -p "$out/x64lib"
cp -a "$work/box64/x64lib/." "$out/x64lib/"
[ -e "$out/x64lib/libgcc_s.so.1" ] || { echo "::error::libgcc_s.so.1 missing from box64 x64lib"; ls "$out/x64lib"; exit 1; }
ls -l "$out/x64lib"
file "$out/bin/box64" | tee "$out/box64.file.txt"
interp=$(aarch64-linux-gnu-readelf -p .interp "$out/bin/box64" | sed -n 's/^ *\[ *0\] *//p')
[ "$interp" = "$SHC_INTERP_PLACEHOLDER" ] || { echo "::error::box64 PT_INTERP is '$interp', expected the placeholder"; exit 1; }
echo "box64 PT_INTERP placeholder: ${#interp} bytes"
grep -q "ARM aarch64" "$out/box64.file.txt"
echo "box64 $BOX64_REF built: $(stat -c %s "$out/bin/box64") bytes"
