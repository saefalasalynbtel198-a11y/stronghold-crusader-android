#!/usr/bin/env bash
# Runs the BUILD 2 self-test sequence against the packaged runtime on emulated ARM64 Linux (qemu-user).
# This verifies the package itself (loader, libraries, Box64, Wine, symlinks, exec bits, extraction code).
# It is NOT an Android test: Android-specific limits (seccomp, SELinux, page size) are only covered on a device.
# Usage: test-runtime-qemu.sh <shc-runtime-*.zip> <work-dir>
set -euo pipefail
ZIP="$(realpath "${1:?runtime zip}")"
WORK="${2:?work dir}"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$WORK/classes"
rt="$WORK/runtime"
rm -rf "$rt"

javac -d "$WORK/classes" "$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core/RuntimeArchive.java" \
  "$repo/scripts/ci/ExtractRuntime.java"
java -cp "$WORK/classes" ExtractRuntime "$ZIP" "$rt"

ld="$rt/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
lib="$rt/lib/aarch64-linux-gnu"
mkdir -p "$WORK/home/wine" "$WORK/tmp"
export LD_LIBRARY_PATH="$lib" BOX64_LD_LIBRARY_PATH="$rt/lib/x86_64-linux-gnu" BOX64_PATH="$rt/wine/bin" \
       BOX64_LOG=1 BOX64_NOBANNER=0 WINEPREFIX="$WORK/home/wine" WINEDEBUG=-all HOME="$WORK/home" TMPDIR="$WORK/tmp" LC_ALL=C

report="$WORK/qemu-selftest.txt"
: > "$report"
step() { # name expected timeout cmd...
  local name="$1" expect="$2" to="$3"; shift 3
  echo "== $name ==" | tee -a "$report"
  echo "\$ $*" >> "$report"
  set +e
  out=$(timeout "$to" "$@" 2>&1); code=$?
  set -e
  echo "$out" | head -n 60 | tee -a "$report"
  if [ $code -eq 0 ] && grep -q -- "$expect" <<<"$out"; then
    echo "-> PASS" | tee -a "$report"
  else
    echo "-> FAIL (exit $code)" | tee -a "$report"
    echo "::error::runtime self-test step failed under qemu: $name (exit $code)"
    exit 1
  fi
}
step "glibc loader" "ld.so" 120 qemu-aarch64 "$ld" --version
step "Box64" "Box64" 300 qemu-aarch64 "$ld" --library-path "$lib" "$rt/bin/box64" --version
step "Wine (x86-64 WoW64 via Box64)" "wine-" 900 qemu-aarch64 "$ld" --library-path "$lib" "$rt/bin/box64" "$rt/wine/bin/wine" --version
echo "All runtime self-test steps passed under qemu-aarch64 (Linux, not Android)." | tee -a "$report"
