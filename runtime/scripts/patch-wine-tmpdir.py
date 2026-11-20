#!/usr/bin/env python3
"""Makes the Linux Wine build usable inside an Android app, where /tmp does not exist.

Upstream Wine 11.0 builds the wineserver directory as "/tmp/.wine-%u" in two places
(server/request.c create_server_dir, dlls/ntdll/unix/server.c init_server_dir); only builds with
__ANDROID__ use "<prefix>/.wineserver" instead. In both places the current directory is the Wine prefix
at that moment (chdir(config_dir) in the server; setup_config_dir() in ntdll before chdir(server_dir)).

This replaces the 13-byte format prefix "/tmp/.wine-%u" with the same-length ".wineserver%u", which makes
the directory "<prefix>/.wineserver<uid>/server-<dev>-<ino>" relative to the prefix: the Android behaviour.
Same length, so offsets and the "%u" argument are unchanged.

Usage: patch-wine-tmpdir.py <wine root>   (fails unless exactly the two expected files change once each)
"""
import os
import sys

OLD = b"/tmp/.wine-%u"
NEW = b".wineserver%u"
assert len(OLD) == len(NEW)

root = sys.argv[1]
changed = {}
for dirpath, _, files in os.walk(root):
    for f in files:
        p = os.path.join(dirpath, f)
        if os.path.islink(p) or not os.path.isfile(p):
            continue
        data = open(p, "rb").read()
        n = data.count(OLD)
        if n:
            open(p, "wb").write(data.replace(OLD, NEW))
            changed[os.path.relpath(p, root)] = n

for k, v in sorted(changed.items()):
    print(f"patched {k}: {v} occurrence(s)")
names = {os.path.basename(k) for k in changed}
if names != {"wineserver", "ntdll.so"} or any(v != 1 for v in changed.values()):
    sys.exit(f"unexpected patch result: {changed}")
