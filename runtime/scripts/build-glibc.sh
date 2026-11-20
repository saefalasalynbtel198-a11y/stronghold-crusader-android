#!/usr/bin/env bash
# Builds glibc with the Android seccomp compatibility patch.
# Usage: build-glibc.sh aarch64|x86_64
#   aarch64: cross build for the runtime package   -> runtime/out/glibc-aarch64/
#   x86_64 : native build for the CI seccomp test  -> runtime/out/glibc-x86_64/
set -euo pipefail
arch="${1:?aarch64|x86_64}"
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
. "$here/versions.env"
work="$here/out/glibc-work-$arch"
dest="$here/out/glibc-$arch"
rm -rf "$work" "$dest"; mkdir -p "$work" "$dest"

tarball="$here/out/downloads/glibc_${GLIBC_VERSION}.orig.tar.xz"
mkdir -p "$(dirname "$tarball")"
[ -f "$tarball" ] || curl -fL --retry 3 -o "$tarball" "$GLIBC_SRC_URL"
echo "$GLIBC_SRC_SHA256  $tarball" | sha256sum -c -

tar -xJf "$tarball" -C "$work"
src="$work/glibc-$GLIBC_VERSION"
( cd "$src" && patch -p1 --forward < "$here/glibc-android/android-seccomp.patch" )

mkdir -p "$work/build"
cd "$work/build"
common=(--prefix=/usr --disable-werror --enable-kernel=4.4 --disable-nscd --disable-profile
        --disable-timezone-tools --disable-crypt)
if [ "$arch" = aarch64 ]; then
  "$src/configure" "${common[@]}" --host=aarch64-linux-gnu --build=x86_64-linux-gnu \
    --with-headers=/usr/aarch64-linux-gnu/include CC=aarch64-linux-gnu-gcc CXX=no \
    CFLAGS="-O2 -g0" > "$here/out/glibc-configure-$arch.log" 2>&1
else
  "$src/configure" "${common[@]}" CFLAGS="-O2 -g0" > "$here/out/glibc-configure-$arch.log" 2>&1
fi
make -j"$(nproc)" > "$here/out/glibc-make-$arch.log" 2>&1 || { tail -50 "$here/out/glibc-make-$arch.log"; exit 1; }
make install DESTDIR="$dest" > "$here/out/glibc-install-$arch.log" 2>&1 || { tail -50 "$here/out/glibc-install-$arch.log"; exit 1; }

ld=$(find "$dest" -name "ld-linux-*.so.*" -type f | head -1)
[ -n "$ld" ] || { echo "::error::glibc loader not produced"; exit 1; }
echo "glibc $GLIBC_VERSION ($arch, Android seccomp patch) installed in $dest; loader: ${ld#$dest}"
