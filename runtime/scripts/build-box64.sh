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
  -DARM64=1
cmake --build "$work/box64-build" --parallel
cp "$work/box64-build/box64" "$out/bin/box64"
aarch64-linux-gnu-strip --strip-debug "$out/bin/box64"
file "$out/bin/box64" | tee "$out/box64.file.txt"
grep -q "ARM aarch64" "$out/box64.file.txt"
echo "box64 $BOX64_REF built: $(stat -c %s "$out/bin/box64") bytes"
