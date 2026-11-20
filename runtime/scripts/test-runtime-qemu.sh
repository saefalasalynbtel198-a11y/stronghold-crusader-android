#!/usr/bin/env bash
# Runs the BUILD 2 self-test sequence against the packaged runtime on emulated ARM64 Linux (qemu-user).
# This verifies the package itself (loader, libraries, Box64, Wine, symlinks, exec bits, extraction code).
# It is NOT an Android test: Android-specific limits (seccomp, SELinux, page size) are only covered on a device.
# To resemble Android, each command runs in a private mount namespace in which the host's x86-64 libraries
# (/usr/lib/x86_64-linux-gnu, which /lib and /lib64 point into on Ubuntu) are hidden behind an empty tmpfs, so
# Box64 can only use what the package ships. Requires sudo and qemu-aarch64-static.
# Usage: test-runtime-qemu.sh <shc-runtime-*.zip> <work-dir>
set -euo pipefail
ZIP="$(realpath "${1:?runtime zip}")"
WORK="${2:?work dir}"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$WORK/classes"
rt="$WORK/runtime"
rm -rf "$rt"

core="$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core"
javac -d "$WORK/classes" -sourcepath "$repo/app/src/main/java" "$core/RuntimeArchive.java" "$core/ElfInterp.java" "$core/RuntimeLayout.java" \
  "$repo/scripts/ci/ExtractRuntime.java"
java -cp "$WORK/classes" ExtractRuntime "$ZIP" "$rt"

ld="$rt/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
lib="$rt/lib/aarch64-linux-gnu"
mkdir -p "$WORK/home/wine" "$WORK/tmp"
# Same environment as the app (core/RuntimeProbe.baseEnv), including glibc.pthread.rseq=0.
export GLIBC_TUNABLES=glibc.pthread.rseq=0 LD_LIBRARY_PATH="$lib" BOX64_LD_LIBRARY_PATH="$rt/lib/x86_64-linux-gnu" BOX64_PATH="$rt/wine/bin" \
       BOX64_LOG=1 BOX64_NOBANNER=0 WINEPREFIX="$WORK/home/wine" WINEDEBUG=-all HOME="$WORK/home" TMPDIR="$WORK/tmp" LC_ALL=C

QEMU=/usr/bin/qemu-aarch64-static
[ -x "$QEMU" ] || { echo "::error::$QEMU not found (install qemu-user-static)"; exit 1; }
HERMETIC="$repo/runtime/scripts/hermetic-run.sh"

report="$WORK/qemu-selftest.txt"
: > "$report"
step() { # name expected timeout cmd...
  local name="$1" expect="$2" to="$3"; shift 3
  echo "== $name ==" | tee -a "$report"
  echo "\$ $*" >> "$report"
  set +e
  out=$(timeout "$to" "$@" 2>&1); code=$?
  set -e
  echo "$out" | head -n 80 | tee -a "$report"
  if grep -q "Using emulated /usr/lib/x86_64-linux-gnu\|Using emulated /lib/x86_64-linux-gnu\|Using emulated /lib64/" <<<"$out"; then
    echo "-> FAIL (used a host x86-64 library; the test is not hermetic)" | tee -a "$report"
    echo "::error::runtime self-test used a host x86-64 library: $name"; exit 1
  fi
  if [ $code -eq 0 ] && grep -q -- "$expect" <<<"$out"; then
    echo "-> PASS" | tee -a "$report"
  else
    echo "-> FAIL (exit $code)" | tee -a "$report"
    echo "::error::runtime self-test step failed under qemu: $name (exit $code)"
    exit 1
  fi
}
step "glibc loader" "ld.so" 120 "$HERMETIC" "$QEMU" "$ld" --version
step "Seccomp probe runs (qemu has no Android filter; checks the binary only)" "probe done" 60 "$HERMETIC" "$QEMU" "$rt/bin/shc-sysprobe"
step "glibc start-up, threads, fork (patched glibc)" "glibctest ok" 300 "$HERMETIC" "$QEMU" "$ld" --library-path "$lib" "$rt/bin/shc-glibctest" "$WORK/tmp"
step "Box64" "Box64" 300 "$HERMETIC" "$QEMU" "$ld" --library-path "$lib" "$rt/bin/box64" --version
step "Wine (x86-64 WoW64 via Box64)" "wine-" 900 "$HERMETIC" "$QEMU" "$ld" --library-path "$lib" "$rt/bin/box64" "$rt/wine/bin/wine" --version
echo "All runtime self-test steps passed under qemu-aarch64 with host x86-64 libraries hidden (Linux, not Android)." | tee -a "$report"
