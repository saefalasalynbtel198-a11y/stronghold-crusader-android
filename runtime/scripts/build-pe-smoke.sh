#!/usr/bin/env bash
# Builds the Windows smoke-test program (32-bit and 64-bit) with mingw-w64.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$here/out/win"
for a in i686 x86_64; do
  "$a-w64-mingw32-gcc" -O2 -Wall -Werror -static -o "$here/out/win/shc-pe-smoke-$a.exe" "$here/pe-smoke/shc-pe-smoke.c"
done
file "$here"/out/win/*.exe
