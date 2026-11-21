#!/usr/bin/env bash
# Native x86-64 test of the process watcher (runtime/sysprobe/shc-ptrace-watch.c), the diagnostic used on the
# device to see how each Wine process ends. Checks: exit-status propagation, fork/exec events, a fatal signal
# in a grandchild, and a seccomp trap (SIGSYS with the syscall number) under the Android filter simulator.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
t="$(mktemp -d)"
gcc -O2 -Wall -Wextra -Werror -o "$t/watch" "$here/sysprobe/shc-ptrace-watch.c"
gcc -O2 -Wall -o "$t/sim" "$here/sysprobe/sim-android-filter.c"

set +e
out=$("$t/watch" -- bash -c '( sh -c "kill -SEGV \$\$" ); ( exit 3 ); exit 7' 2>&1); code=$?
set -e
echo "$out"
[ $code -eq 7 ] || { echo "::error::exit status not propagated ($code)"; exit 1; }
grep -q "KILLED by SIGSEGV (11)" <<<"$out" || { echo "::error::fatal signal of a grandchild not reported"; exit 1; }
grep -q "exited with code 3" <<<"$out" || { echo "::error::child exit code not reported"; exit 1; }
grep -q "exec: " <<<"$out" || { echo "::error::exec events not reported"; exit 1; }

# set_robust_list is trapped by the Android 16 simulation; the host's (unpatched) glibc calls it at start-up.
set +e
out=$("$t/watch" -- "$t/sim" android16 /bin/true 2>&1); code=$?
set -e
echo "$out"
[ $code -eq 159 ] || { echo "::error::expected 159 (SIGSYS), got $code"; exit 1; }
grep -Eq "SIGSYS: si_code 1 \(seccomp\), syscall (273|334)" <<<"$out" || { echo "::error::seccomp syscall number not reported"; exit 1; }
echo "process watcher test passed"
