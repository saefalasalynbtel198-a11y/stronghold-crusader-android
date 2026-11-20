#!/usr/bin/env bash
# Reproduces the device's Wine path on emulated ARM64 Linux (qemu-user), without Android:
#   Box64 executed directly (PT_INTERP patched by the app's own ElfInterp code, as the installer does)
#   -> wine wineboot --init -> wine auto-starts wineserver (fork + exec through Box64)
# This is the path that failed on the Android 16 device with "wine client error:0: recvmsg: Connection reset
# by peer". It has never run in CI before (the native Wine test runs x86-64 Wine without Box64).
#   A: wineboot --init with an auto-started wineserver (the app's path). Every syscall of every process is
#      recorded (qemu strace) and checked against Android's arm64 app allowlist (runtime/android/): a syscall
#      outside it is trapped with SIGSYS on a phone, so the test fails even though Linux allowed it.
#   B: only if A fails: wineserver started in the foreground first (-f -d1), then wineboot --init
# Runs as the normal user. Host x86-64 libraries are not hidden (the shell tools need them); any use of one
# by Box64 is reported at the end.
# NOT an Android test: no seccomp, no SELinux, different kernel and address-space size.
# Usage: test-box64-wine-qemu.sh <shc-runtime-*.zip> <work-dir>
set -uo pipefail
ZIP="$(realpath "${1:?runtime zip}")"
WORK="$(mkdir -p "${2:?work dir}" && realpath "$2")"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
core="$repo/app/src/main/java/io/github/saefalasalynbtel198/shcport/core"
rt="$WORK/runtime"
rm -rf "$rt" "$WORK/classes"; mkdir -p "$WORK/classes"
javac -d "$WORK/classes" -sourcepath "$repo/app/src/main/java" "$core/RuntimeArchive.java" "$core/ElfInterp.java" "$core/RuntimeLayout.java" \
  "$repo/scripts/ci/ExtractRuntime.java" || exit 1
java -cp "$WORK/classes" ExtractRuntime "$ZIP" "$rt" patch-interp || exit 1

[ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ] || sudo update-binfmts --enable qemu-aarch64 || true
echo "binfmt: $(head -3 /proc/sys/fs/binfmt_misc/qemu-aarch64 2>/dev/null | tr '\n' ' ')"

cat > "$WORK/inner.sh" <<'EOS'
set -u
rt="$1"; W="$2"; REPO="$3"
lib="$rt/lib/aarch64-linux-gnu"
box64="$rt/bin/box64"; wine="$rt/wine/bin/wine"; wineserver="$rt/wine/bin/wineserver"
# Same environment as the app (core/RuntimeProbe.baseEnv + WineTestPlan.wineEnv), Box64 logging on.
export GLIBC_TUNABLES=glibc.pthread.rseq=0 LD_LIBRARY_PATH="$lib" BOX64_LD_LIBRARY_PATH="$rt/lib/x86_64-linux-gnu" \
       BOX64_PATH="$rt/wine/bin" BOX64_LOG=1 BOX64_SHOWSEGV=1 BOX64_NOBANNER=0 BOX64_CPUNAME="qemu aarch64" \
       HOME="$W/home" TMPDIR="$W/tmp" LC_ALL=C DISPLAY= \
       WINEDLLOVERRIDES="mscoree,mshtml=d;winemenubuilder.exe=d" WINEDEBUG=err+all,fixme-all
mkdir -p "$W/home" "$W/tmp"
show() { echo "---- $1 (first 60 / last 80 lines) ----"; head -n 60 "$1"; echo "[…]"; tail -n 80 "$1"; echo "----"; }
prefix_state() {
  echo "prefix files:"; ls -la "$WINEPREFIX" 2>&1 | head -20
  find "$WINEPREFIX" -maxdepth 3 -path '*/.wineserver*' -exec ls -ld {} + 2>&1 | head -20
}

echo "== A: wineboot --init, wineserver auto-started by wine (device path), all syscalls recorded =="
export WINEPREFIX="$W/prefixA"
mkdir -p "$W/strace"
t=$(date +%s)
QEMU_STRACE=1 QEMU_LOG_FILENAME="$W/strace/p.%d" \
  timeout 3000 "$box64" "$wine" wineboot --init > "$W/A-wineboot.log" 2>&1; a=$?
echo "wineboot exit $a after $(( $(date +%s) - t )) s"
show "$W/A-wineboot.log"
timeout 600 "$box64" "$wineserver" -w > "$W/A-wait.log" 2>&1; echo "wineserver -w exit $?"
prefix_state
echo "== Android seccomp check of every recorded syscall =="
python3 "$REPO/runtime/android/check-strace.py" "$REPO/runtime/android/arm64-app-syscalls.txt" "$W"/strace/p.* \
  > "$W/A-seccomp-check.txt"; sc=$?
cat "$W/A-seccomp-check.txt"
rm -rf "$W/strace"   # hundreds of MB; the check result is kept
if [ $sc -ne 0 ]; then
  echo "RESULT A: FAIL - syscalls outside Android's app allowlist (SIGSYS on a phone), listed above"
  exit 1
fi
if [ $a -eq 0 ] && [ -f "$WINEPREFIX/system.reg" ] && [ -d "$WINEPREFIX/drive_c/windows" ]; then
  echo "RESULT A: PASS (prefix created through Box64 with an auto-started wineserver; no syscall outside Android's allowlist)"
  exit 0
fi
echo "RESULT A: FAIL"
if grep -q "recvmsg: Connection reset by peer" "$W/A-wineboot.log"; then
  echo "RESULT A: reproduces the device error (recvmsg: Connection reset by peer)"
fi

echo "== B: wineserver started first in the foreground (-f -d1 -p), then wineboot --init =="
export WINEPREFIX="$W/prefixB"; mkdir -p "$WINEPREFIX"
"$box64" "$wineserver" -f -d1 -p > "$W/B-wineserver.log" 2>&1 &
srv=$!
for i in $(seq 1 600); do
  ls "$WINEPREFIX"/.wineserver*/server-*/socket >/dev/null 2>&1 && break
  kill -0 $srv 2>/dev/null || break
  sleep 1
done
echo "socket after ${i}s: $(ls "$WINEPREFIX"/.wineserver*/server-*/socket 2>&1)"
timeout 3000 "$box64" "$wine" wineboot --init > "$W/B-wineboot.log" 2>&1; b=$?
echo "wineboot exit $b"
show "$W/B-wineboot.log"
if kill -0 $srv 2>/dev/null; then
  echo "wineserver still running"; "$box64" "$wineserver" -k >/dev/null 2>&1; wait $srv; echo "wineserver exit $? after -k"
else
  wait $srv; echo "wineserver had ended, exit status $?"
fi
show "$W/B-wineserver.log"
prefix_state
if [ $b -eq 0 ] && [ -f "$WINEPREFIX/system.reg" ]; then echo "RESULT B: PASS"; else echo "RESULT B: FAIL"; fi
exit 1
EOS

bash "$WORK/inner.sh" "$rt" "$WORK" "$repo" 2>&1 | tee "$WORK/box64-wine-qemu.txt"
code=${PIPESTATUS[0]}
if grep -h "Using emulated /usr/lib/x86_64-linux-gnu\|Using emulated /lib/x86_64-linux-gnu\|Using emulated /lib64/" "$WORK"/*.log | sort -u | head; then
  echo "::warning::Box64 used host x86-64 libraries in this run (not hermetic)"
fi
exit "$code"
