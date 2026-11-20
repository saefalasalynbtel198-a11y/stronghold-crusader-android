#!/usr/bin/env bash
# Linux x86-64 CI test of the seccomp diagnosis (not an Android test):
#  1. the probe's SIGSYS trap mechanism works (--trap-selftest),
#  2. under a filter that traps rseq, the probe reports rseq as BLOCKED,
#  3. a normal glibc program is killed with exit 159 (128 + SIGSYS) — the device symptom,
#  4. the same program runs with GLIBC_TUNABLES=glibc.pthread.rseq=0 (the fix), including threads.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
w="$(mktemp -d)"
F="-static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -fno-pie -no-pie -O2 -Wall -Wextra -Werror"
gcc $F -o "$w/probe" "$here/sysprobe/shc-sysprobe.c"
gcc -O2 -Wall -o "$w/sim" "$here/sysprobe/sim-android-filter.c"

"$w/probe" --trap-selftest | tee "$w/1.txt"; grep -q "trap-selftest: PASS" "$w/1.txt"
"$w/sim" rseq "$w/probe" | tee "$w/2.txt"
grep -q "^rseq ([0-9]*): BLOCKED by seccomp" "$w/2.txt" || { echo "::error::probe did not report rseq as blocked"; exit 1; }
grep -q "^probe done" "$w/2.txt"

set +e
"$w/sim" rseq /bin/true; code=$?
set -e
echo "glibc program under rseq-trapping filter: exit $code"
[ "$code" = 159 ] || { echo "::error::expected exit 159 (SIGSYS), got $code"; exit 1; }

GLIBC_TUNABLES=glibc.pthread.rseq=0 "$w/sim" rseq /usr/bin/python3 -c \
  "import threading; t=threading.Thread(target=lambda: print('thread ok')); t.start(); t.join()" | tee "$w/4.txt"
grep -q "thread ok" "$w/4.txt"
# The device (Android 16) also blocks set_robust_list: there the tunable alone is NOT enough (device report 2).
set +e
GLIBC_TUNABLES=glibc.pthread.rseq=0 "$w/sim" android16 /bin/true; code=$?
set -e
echo "unpatched glibc + rseq tunable under the Android 16 blocked set: exit $code"
[ "$code" = 159 ] || { echo "::error::expected exit 159 with unpatched glibc under the android16 set, got $code"; exit 1; }
echo "seccomp diagnosis test passed: rseq trap reproduces exit 159; the tunable is insufficient under the Android 16 set."
