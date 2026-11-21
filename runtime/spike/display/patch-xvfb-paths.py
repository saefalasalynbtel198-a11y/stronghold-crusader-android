#!/usr/bin/env python3
"""Copies Debian's Xvfb and points its two compiled-in XKB paths at the directory Xvfb runs in.

    patch-xvfb-paths.py <Xvfb in> <Xvfb out>

Android apps have no /usr/bin and no /tmp or /var/lib. Xvfb compiles its keymap at start by running
"<XKB_BIN_DIRECTORY>/xkbcomp" through /bin/sh and writes the result to XKM_OUTPUT_DIR (falling back to /tmp/);
neither has a command-line option. Same approach as Wine's /tmp patch: the NUL-terminated string literals are
replaced in place by shorter relative paths ("." and "./"), so xkbcomp is taken from, and writes to, Xvfb's
working directory. Each literal must occur exactly once, otherwise nothing is written.
"""
import sys

data = bytearray(open(sys.argv[1], 'rb').read())
PATCHES = [
    (b'/usr/bin', b'.'),            # XKB_BIN_DIRECTORY
    (b'/var/lib/xkb/', b'./'),      # XKM_OUTPUT_DIR (Debian)
]
for old, new in PATCHES:
    needle = b'\0' + old + b'\0'
    hits = []
    i = data.find(needle)
    while i >= 0:
        hits.append(i + 1)
        i = data.find(needle, i + 1)
    if len(hits) != 1:
        sys.exit('expected exactly one "%s" string literal, found %d at %s' % (old.decode(), len(hits), hits))
    off = hits[0]
    data[off:off + len(old)] = new + b'\0' * (len(old) - len(new))
    print('patched "%s" -> "%s" at offset 0x%x' % (old.decode(), new.decode(), off))
open(sys.argv[2], 'wb').write(data)
