#!/usr/bin/env python3
"""Computes the syscall names Android allows for app processes on arm64 from AOSP bionic's sources.

    gen-allowlist.py <bionic checkout>/libc > arm64-app-syscalls.txt

Android generates its app seccomp filter (bionic/libc/tools/genseccomp.py) from
SYSCALLS.TXT + SECCOMP_ALLOWLIST_COMMON.TXT + SECCOMP_ALLOWLIST_APP.TXT
minus SECCOMP_BLOCKLIST_COMMON.TXT and SECCOMP_BLOCKLIST_APP.TXT. Syscalls outside the result are
trapped with SIGSYS. This reproduces that set for arm64 by name.
"""
import os
import sys


def parse(path):
    names = set()
    for line in open(path):
        line = line.split('#')[0].strip()
        if not line or '(' not in line:
            continue
        head = line.split('(')[0].strip().split()[-1]      # [func:]syscall[|alias]
        arches = set(line.split()[-1].split(','))
        if not arches & {'all', 'lp64', 'arm64'}:
            continue
        if ':' in head:
            head = head.split(':', 1)[1]
        names.add(head.split('|')[0])
    return names


def main():
    d = sys.argv[1]
    f = lambda n: parse(os.path.join(d, n))
    allowed = (f('SYSCALLS.TXT') | f('SECCOMP_ALLOWLIST_COMMON.TXT') | f('SECCOMP_ALLOWLIST_APP.TXT')) \
        - f('SECCOMP_BLOCKLIST_COMMON.TXT') - f('SECCOMP_BLOCKLIST_APP.TXT')
    print('\n'.join(sorted(allowed)))


if __name__ == '__main__':
    main()
