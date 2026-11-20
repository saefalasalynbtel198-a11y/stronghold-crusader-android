#!/usr/bin/env bash
# Linux x86-64 CI test of the Android seccomp glibc patch (not an Android test).
# Runs programs with the patched glibc under a filter trapping the syscalls the probe found BLOCKED on the
# Android 16 device (+ clone3, fchmodat2). Unpatched glibc dies there with exit 159 (see test-sysprobe.sh).
# Usage: test-glibc-android.sh <glibc-x86_64 install dir>
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
g="$(cd "${1:?glibc install dir}" && pwd)"
w="$(mktemp -d)"
ld=$(find "$g" -name "ld-linux-x86-64.so.2" | head -1)
libdir=$(dirname "$(find "$g" -name "libc.so.6" | head -1)")
[ -n "$ld" ] && [ -n "$libdir" ] || { echo "::error::patched glibc not found in $g"; exit 1; }
gcc -O2 -Wall -Werror -o "$w/sim" "$here/sysprobe/sim-android-filter.c"
gcc -O2 -Wall -Werror -pthread -o "$w/glibctest" "$here/sysprobe/shc-glibctest.c"

run() { # description, expected substring, command...
  local d="$1" e="$2"; shift 2
  set +e; out=$("$@" 2>&1); code=$?; set -e
  echo "== $d (exit $code)"; echo "$out" | tail -n 12
  [ $code -eq 0 ] && grep -q -- "$e" <<<"$out" || { echo "::error::$d failed (exit $code)"; exit 1; }
}
# No GLIBC_TUNABLES here on purpose: the patched glibc must not depend on it.
run "glibctest, patched glibc, Android 16 blocked set" "glibctest ok" \
  "$w/sim" android16 "$ld" --library-path "$libdir" "$w/glibctest" "$w"
run "python3 threads + subprocess, patched glibc, Android 16 blocked set" "subprocess ok" \
  "$w/sim" android16 "$ld" --library-path "$libdir" /usr/bin/python3 -c \
  "import threading,subprocess,sys; r=[]; ts=[threading.Thread(target=lambda: r.append(1)) for _ in range(8)]; [t.start() for t in ts]; [t.join() for t in ts]; assert len(r)==8; print('threads ok'); rc=subprocess.run(['$ld','--library-path','$libdir',sys.executable,'-c','print(1)'],capture_output=True).returncode; print('subprocess ok' if rc==0 else 'subprocess rc=%d' % rc)"
# Children must also be started through the patched loader (on Android there is no system glibc at all).
# A child started directly uses the host's unpatched glibc and is killed — shown here as a control:
set +e
"$w/sim" android16 "$ld" --library-path "$libdir" /usr/bin/python3 -c \
  "import subprocess,sys; print('direct child rc', subprocess.run([sys.executable,'-c','1']).returncode)"
set -e
echo "patched glibc passed under the Android 16 blocked syscall set."
