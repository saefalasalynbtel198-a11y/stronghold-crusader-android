#!/usr/bin/env python3
"""Packs a staged runtime tree into the zip format read by core/RuntimeArchive.java.

Usage: package_runtime.py <staged-root> <output.zip> <version>

* Regular files are stored as zip entries (deflated).
* Symlinks are not stored as entries; they are listed in SHC_RUNTIME_MANIFEST as relative links.
  Absolute targets are rewritten relative to the staged root ("/usr/lib/x" -> "../usr/lib/x").
  Links whose target would leave the root, or that dangle, are dropped and reported.
* Files with any execute bit set are listed as executables.
"""
import os
import posixpath
import stat
import sys
import zipfile

MANIFEST = "SHC_RUNTIME_MANIFEST"


def rel_target(root, link_rel, target):
    """Returns a safe relative target, or None if the link must be dropped."""
    link_dir = posixpath.dirname(link_rel)
    if target.startswith("/"):
        target = posixpath.relpath(target.lstrip("/"), link_dir or ".")
    resolved = posixpath.normpath(posixpath.join(link_dir, target))
    if resolved == ".." or resolved.startswith("../") or resolved.startswith("/"):
        return None
    if not os.path.lexists(os.path.join(root, resolved)):
        return None
    return target


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    root, out, version = sys.argv[1], sys.argv[2], sys.argv[3]
    if "\t" in version or "\n" in version:
        sys.exit("invalid version")
    links, exes, dropped, files = [], [], [], 0

    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=True) as z:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames.sort()
            rel_dir = os.path.relpath(dirpath, root).replace(os.sep, "/")
            rel_dir = "" if rel_dir == "." else rel_dir
            # os.walk does not descend into symlinked dirs; record them as links.
            for name in sorted(dirnames + filenames):
                rel = f"{rel_dir}/{name}" if rel_dir else name
                full = os.path.join(dirpath, name)
                if "\t" in rel or "\n" in rel or "\\" in rel:
                    sys.exit(f"unsupported character in path: {rel!r}")
                st = os.lstat(full)
                if stat.S_ISLNK(st.st_mode):
                    t = rel_target(root, rel, os.readlink(full))
                    if t is None:
                        dropped.append(f"{rel} -> {os.readlink(full)}")
                    else:
                        links.append((rel, t))
                elif stat.S_ISDIR(st.st_mode):
                    z.writestr(zipfile.ZipInfo(rel + "/"), b"")
                elif stat.S_ISREG(st.st_mode):
                    z.write(full, rel)
                    files += 1
                    if st.st_mode & 0o111:
                        exes.append(rel)
        lines = ["format\t1", f"version\t{version}"]
        lines += [f"x\t{p}" for p in exes]
        lines += [f"l\t{p}\t{t}" for p, t in links]
        z.writestr(MANIFEST, "\n".join(lines) + "\n")

    print(f"packed {files} files, {len(links)} symlinks, {len(exes)} executables -> {out}")
    for d in dropped:
        print(f"dropped unsafe or dangling symlink: {d}")


if __name__ == "__main__":
    main()
