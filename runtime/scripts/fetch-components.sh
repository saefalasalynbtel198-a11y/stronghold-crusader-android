#!/usr/bin/env bash
# Downloads pinned third-party runtime inputs and verifies their checksums.
# Output: runtime/out/downloads/*, runtime/out/MANIFEST.txt
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
. "$here/versions.env"
dl="$here/out/downloads"
mkdir -p "$dl"
manifest="$here/out/MANIFEST.txt"
: > "$manifest"

fetch() { # url dest expected-sha256(optional)
  local url="$1" dest="$2" want="${3:-}"
  [ -f "$dest" ] || curl -fL --retry 3 -o "$dest" "$url"
  local got
  got=$(sha256sum "$dest" | cut -d' ' -f1)
  if [ -n "$want" ] && [ "$got" != "$want" ]; then
    echo "::error::Checksum mismatch for $(basename "$dest"): got $got, expected $want"
    exit 1
  fi
  [ -n "$want" ] || echo "::warning::$(basename "$dest") has no pinned checksum yet; recorded $got"
  printf '%s  %s  %s\n' "$got" "$(basename "$dest")" "$url" >> "$manifest"
}

fetch "https://github.com/Kron4ek/Wine-Builds/releases/download/${WINE_VERSION}/${WINE_ASSET}" \
      "$dl/$WINE_ASSET" "$WINE_SHA256"
fetch "https://github.com/FunkyFr3sh/cnc-ddraw/releases/download/${CNC_DDRAW_TAG}/cnc-ddraw.zip" \
      "$dl/cnc-ddraw-${CNC_DDRAW_TAG}.zip" "$CNC_DDRAW_SHA256"

# Sanity checks on content layout.
tar -tJf "$dl/$WINE_ASSET" | grep -m1 -E '/bin/wine$' > /dev/null || { echo "::error::wine binary not found in archive"; exit 1; }
unzip -l "$dl/cnc-ddraw-${CNC_DDRAW_TAG}.zip" | grep -qi 'ddraw.dll' || { echo "::error::ddraw.dll not found in cnc-ddraw.zip"; exit 1; }
cat "$manifest"
