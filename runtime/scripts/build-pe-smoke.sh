#!/usr/bin/env bash
# Builds the Windows smoke-test programs with mingw-w64: PE smoke (32-bit and 64-bit), GUI smoke (32-bit).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$here/out/win"
for a in i686 x86_64; do
  "$a-w64-mingw32-gcc" -O2 -Wall -Werror -static -o "$here/out/win/shc-pe-smoke-$a.exe" "$here/pe-smoke/shc-pe-smoke.c"
done
# BUILD 3 phase 3: window with known colour blocks via GDI or DirectDraw (32-bit, like the game)
i686-w64-mingw32-gcc -O2 -Wall -Werror -static -o "$here/out/win/shc-gui-smoke.exe" "$here/pe-smoke/shc-gui-smoke.c" -lgdi32
file "$here"/out/win/*.exe
