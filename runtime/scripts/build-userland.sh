#!/usr/bin/env bash
# Extracts a minimal Debian arm64 (glibc) userland: the glibc loader and the native libraries Box64
# and Wine's Unix side need. "extract" variant: packages are unpacked only (no maintainer scripts),
# so no ARM emulation is needed on the x86-64 CI runner.
# Output: runtime/out/userland/
# Requires: mmdebstrap (run as root or with fakechroot/unshare support)
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
. "$here/versions.env"
out="$here/out/userland"
rm -rf "$out"

# BUILD 2 needs only libc for `box64 wine --version`. X11 client, font and ALSA libraries are included
# now so the package layout does not change in BUILD 3. OpenGL/Mesa for Android GPUs is decided in BUILD 3.
packages=(
  libc6 libgcc-s1 libstdc++6 zlib1g
  libx11-6 libxext6 libxrender1 libxrandr2 libxi6 libxcursor1 libxfixes3 libxinerama1 libxcomposite1 libxxf86vm1
  libxcb1 libxau6 libxdmcp6 libbsd0 libmd0
  libfreetype6 libpng16-16 libbrotli1 libfontconfig1 libexpat1 libuuid1
  libasound2
)
include=$(IFS=,; echo "${packages[*]}")

# Debian's archive keys (package debian-archive-keyring) so apt verifies the Release signatures.
keyring=/usr/share/keyrings/debian-archive-keyring.gpg
[ -f "$keyring" ] || { echo "::error::$keyring missing (install debian-archive-keyring)"; exit 1; }

mmdebstrap --mode=root --variant=extract --architectures=arm64 --keyring="$keyring" --include="$include" \
  "$DEBIAN_SUITE" "$out" "$DEBIAN_MIRROR"

# Keep licence/copyright files, drop the rest of the documentation and locales.
find "$out/usr/share/doc" -type f ! -name copyright -delete 2>/dev/null || true
rm -rf "$out/usr/share/man" "$out/usr/share/info" "$out/usr/share/locale" "$out/usr/share/lintian"

# The extract variant does not create the merged-/usr symlinks: libc6 lands in lib/aarch64-linux-gnu,
# most other libraries in usr/lib/aarch64-linux-gnu. Merge both into one flat directory and make
# absolute symlinks (e.g. /lib/aarch64-linux-gnu/libz.so.1) relative to it.
merged="$here/out/userland-lib"
rm -rf "$merged"
mkdir -p "$merged"
for d in "$out/lib/aarch64-linux-gnu" "$out/usr/lib/aarch64-linux-gnu"; do
  [ -d "$d" ] && cp -a "$d/." "$merged/"
done
python3 -I - "$merged" <<'PY'
import os, sys
root = sys.argv[1]
prefixes = ("/lib/aarch64-linux-gnu/", "/usr/lib/aarch64-linux-gnu/")
for dirpath, dirnames, filenames in os.walk(root):
    for name in dirnames + filenames:
        p = os.path.join(dirpath, name)
        if not os.path.islink(p):
            continue
        t = os.readlink(p)
        for pre in prefixes:
            if t.startswith(pre):
                rel = os.path.relpath(os.path.join(root, t[len(pre):]), dirpath)
                os.unlink(p)
                os.symlink(rel, p)
                break
PY

ld="$merged/ld-linux-aarch64.so.1"
[ -e "$ld" ] || { echo "::error::glibc loader not found in lib/ or usr/lib/aarch64-linux-gnu"; ls "$out" "$out/lib" 2>/dev/null; exit 1; }
file -L "$ld" | tee "$here/out/ld.file.txt"
grep -q "ARM aarch64" "$here/out/ld.file.txt"
for so in libc.so.6 libX11.so.6 libasound.so.2 libfreetype.so.6; do
  [ -e "$merged/$so" ] || { echo "::error::$so missing from userland"; exit 1; }
done
echo "userland: $(find "$merged" -maxdepth 1 | wc -l) entries, $(du -sh "$merged" | cut -f1)"
