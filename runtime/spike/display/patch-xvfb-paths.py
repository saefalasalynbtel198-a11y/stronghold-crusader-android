#!/usr/bin/env python3
"""Copies Debian's Xvfb and points its compiled-in XKB and lock-file paths at the directory Xvfb runs in.

    patch-xvfb-paths.py <Xvfb in> <Xvfb out>

Android apps have no /usr/bin and no /tmp or /var/lib. Xvfb compiles its keymap at start by running
"<XKB_BIN_DIRECTORY>/xkbcomp" through /bin/sh and writes the result to XKM_OUTPUT_DIR (falling back to /tmp/);
neither has a command-line option. Its lock file goes to LOCK_DIR "/tmp" (the -nolock option is accepted only
for root). Same approach as Wine's /tmp patch: the NUL-terminated string literals are
replaced in place by shorter relative paths ("." and "./"), so xkbcomp is taken from, and writes to, Xvfb's
working directory. Each literal must occur exactly once, otherwise nothing is written.
"""
import sys

data = bytearray(open(sys.argv[1], 'rb').read())
PATCHES = [
    (b'/usr/bin', b'.'),            # XKB_BIN_DIRECTORY
    (b'/var/lib/xkb/', b'./'),      # XKM_OUTPUT_DIR (Debian)
    (b'/tmp', b'.'),                # LOCK_DIR (os/utils.c); -nolock is root-only
]
def literal_start(off):
    i = off
    while i > 0 and data[i - 1] != 0:
        i -= 1
    return i


for old, new in PATCHES:
    needle = b'\0' + old + b'\0'
    hits = []
    i = data.find(needle)
    while i >= 0:
        hits.append(i + 1)
        i = data.find(needle, i + 1)
    if not hits and old == b'/tmp':
        # The linker may store "/tmp" only as the tail of a longer literal (suffix merging, e.g. "/var/tmp").
        # Then only the tail is patched; the containing string is printed so its change is visible.
        tails = []
        i = data.find(old + b'\0')
        while i >= 0:
            if data[i - 1] != 0:
                tails.append(i)
            i = data.find(old + b'\0', i + 1)
        if len(tails) == 1:
            full = bytes(data[literal_start(tails[0]):tails[0] + len(old)]).decode(errors='replace')
            data[tails[0]:tails[0] + len(old)] = new + b'\0' * (len(old) - len(new))
            after = bytes(data[literal_start(tails[0]):data.index(0, literal_start(tails[0]))]).decode(errors='replace')
            print('patched "%s" (tail of "%s", which becomes "%s") -> "%s" at offset 0x%x'
                  % (old.decode(), full, after, new.decode(), tails[0]))
            continue
        sys.exit('"/tmp" found neither standalone nor as a single string tail (tails: %s)' % tails)
    if len(hits) != 1:
        sys.exit('expected exactly one "%s" string literal, found %d at %s' % (old.decode(), len(hits), hits))
    off = hits[0]
    data[off:off + len(old)] = new + b'\0' * (len(old) - len(new))
    print('patched "%s" -> "%s" at offset 0x%x' % (old.decode(), new.decode(), off))
open(sys.argv[2], 'wb').write(data)
