#!/usr/bin/env python3
"""Lists absolute file paths a traced process opened, executed or examined outside the test directories.

    check-paths.py <work dir> <qemu strace files...>

Android apps have no /usr, /var or /tmp. A successful access there in the Linux test means the program would
behave differently on the phone, so it fails the check (exit 1). Failed lookups (ENOENT etc.) are listed for
information only: they fail on Android too, and the program coped with that here. /proc, /dev and /sys exist on
Android and are not reported.
"""
import collections
import re
import sys

work = sys.argv[1].rstrip('/') + '/'
CALLS = ('openat', 'open', 'execve', 'faccessat', 'access', 'newfstatat', 'statx', 'readlinkat', 'mkdirat',
         'unlinkat', 'chdir', 'renameat', 'renameat2')
pat = re.compile(r'^\d+ (%s)\((?:AT_FDCWD,|-?\d+,)?"([^"]*)"' % '|'.join(CALLS))
found = collections.OrderedDict()
for path in sys.argv[2:]:
    for line in open(path, errors='replace'):
        m = pat.match(line)
        if not m:
            continue
        call, p = m.group(1), m.group(2)
        if not p.startswith('/') or p.startswith(work) or p.startswith(('/proc', '/dev', '/sys')):
            continue
        ret = line.rsplit(' = ', 1)[-1].strip() if ' = ' in line else '?'
        ok = not ret.startswith('-')
        key = (call, p)
        if key not in found or ok:
            found[key] = (ok, ret[:40])
bad = 0
for (call, p), (ok, ret) in found.items():
    android_missing = p.startswith(('/usr', '/var', '/tmp'))
    if ok and android_missing:
        bad += 1
        print('  NEEDED (exists here, not on Android): %s %s = %s' % (call, p, ret))
for (call, p), (ok, ret) in found.items():
    if ok and not p.startswith(('/usr', '/var', '/tmp')):
        print('  used, exists on Android too?: %s %s = %s' % (call, p, ret))
for (call, p), (ok, ret) in found.items():
    if not ok:
        print('  looked up, absent here as on Android: %s %s = %s' % (call, p, ret))
print('%d path(s) under /usr, /var or /tmp were needed' % bad)
sys.exit(1 if bad else 0)
