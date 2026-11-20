#!/usr/bin/env python3
"""Checks qemu-user strace logs (QEMU_STRACE=1) against Android's arm64 app syscall allowlist.

    check-strace.py arm64-app-syscalls.txt <strace files...>

Prints every syscall made that Android's app seccomp filter would trap (SIGSYS kills the process) with the
first line where it occurs; exit status 1 if there is any.
"""
import collections
import re
import sys

allowed = {l.strip() for l in open(sys.argv[1]) if l.strip() and not l.startswith('#')}
bad_total = 0
for path in sys.argv[2:]:
    counts = collections.Counter()
    first = {}
    with open(path, errors='replace') as f:
        for i, line in enumerate(f, 1):
            m = re.match(r'^(\d+) ([a-z_0-9]+)\(', line)
            if m:
                name = m.group(2)
            else:
                m = re.match(r'^(\d+) Unknown syscall (\d+)', line)
                if not m:
                    continue
                name = '#' + m.group(2)
            counts[name] += 1
            first.setdefault(name, (i, line.strip()[:160]))
    bad = sorted((n for n in counts if n not in allowed), key=lambda n: first[n][0])
    print('%s: %d distinct syscalls, %d outside the Android app allowlist' % (path, len(counts), len(bad)))
    for n in bad:
        print('   %s x%d, first at line %d: %s' % (n, counts[n], first[n][0], first[n][1]))
    bad_total += len(bad)
sys.exit(1 if bad_total else 0)
