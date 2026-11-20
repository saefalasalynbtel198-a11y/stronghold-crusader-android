#!/usr/bin/env bash
# Native x86-64 Linux test of the packaged Wine (with the wineserver-directory patch) and the PE smoke programs.
# This is NOT an Android test: it checks the Wine build, the patch and the test programs before they reach a device.
#  1. wineboot --init creates a prefix; the wineserver directory is <prefix>/.wineserver<uid>, not /tmp/.wine-<uid>
#  2. the 64-bit and 32-bit (WoW64) PE smoke programs run, create a child process, do file I/O and exit with 42
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
. "$here/versions.env"
w="$RUNNER_TEMP/wine-native"; [ -n "${RUNNER_TEMP:-}" ] || w="$(mktemp -d)"
rm -rf "$w"; mkdir -p "$w"
tar -xJf "$here/out/downloads/$WINE_ASSET" -C "$w"
wine_root=$(find "$w" -mindepth 1 -maxdepth 1 -type d -name 'wine-*' | head -1)
[ -x "$wine_root/bin/wine" ] || { echo "::error::wine not found under $w"; ls "$w"; exit 1; }
python3 -I "$here/scripts/patch-wine-tmpdir.py" "$wine_root" | tee "$w/patch.txt"
export WINEPREFIX="$w/prefix" WINEDEBUG=err+all,fixme-all WINEDLLOVERRIDES="mscoree,mshtml=d" DISPLAY=
uid=$(id -u)
rm -rf "/tmp/.wine-$uid"

set +e
timeout 600 "$wine_root/bin/wine" wineboot --init > "$w/wineboot.log" 2>&1; code=$?
set -e
echo "wineboot --init exit $code"; tail -n 20 "$w/wineboot.log"
# wineboot returns while wineserver is still running; system.reg is written when the server exits.
timeout 300 "$wine_root/bin/wineserver" -w; echo "wineserver -w exit $?"
ls -la "$WINEPREFIX" || true
[ $code -eq 0 ] && [ -f "$WINEPREFIX/system.reg" ] || { echo "::error::wineboot did not create the prefix"; exit 1; }
ls -d "$WINEPREFIX"/.wineserver"$uid"/server-* || { echo "::error::wineserver dir not in prefix"; exit 1; }
[ ! -e "/tmp/.wine-$uid" ] || { echo "::error::/tmp/.wine-$uid was still used"; exit 1; }
echo "wineserver directory: $(ls -d "$WINEPREFIX"/.wineserver"$uid"/server-*)"

for a in x86_64 i686; do
  set +e
  out=$(cd "$WINEPREFIX/drive_c" && timeout 300 "$wine_root/bin/wine" "$here/out/win/shc-pe-smoke-$a.exe" 'C:\' 2>&1 | tr -d '\r'); code=$?
  set -e
  echo "== PE smoke $a (exit $code)"; echo "$out" | tee "$w/pe-smoke-$a.log"
  [ $code -eq 42 ] && grep -q "^pe-smoke ok" <<<"$out" || { echo "::error::PE smoke $a failed (exit $code)"; exit 1; }
done
"$wine_root/bin/wineserver" -k || true
echo "native Wine smoke test passed (prefix init, wineserver dir in prefix, 64-bit and 32-bit PE)."
