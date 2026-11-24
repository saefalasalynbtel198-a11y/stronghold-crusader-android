#!/bin/bash
# Builds the SHC ALSA plugin (app/nativesrc/alsa-shc/pcm_shc.c) for arm64 against Debian trixie's alsa-lib and glibc,
# the same Debian release as runtime r6's native libraries (glibc 2.41, libasound2t64), in a Debian container.
# Usage: build-alsa-plugin.sh <output .so>
set -euo pipefail
out="$(realpath -m "${1:?output .so}")"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$(dirname "$out")"
docker run --rm -v "$repo/app/nativesrc/alsa-shc:/src:ro" -v "$(dirname "$out"):/out" debian:trixie bash -euc "
  dpkg --add-architecture arm64
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends gcc-aarch64-linux-gnu libc6-dev-arm64-cross libasound2-dev:arm64 > /dev/null
  aarch64-linux-gnu-gcc -shared -fPIC -DPIC -O2 -Wall -Werror -o /out/$(basename "$out") /src/pcm_shc.c -lasound
  echo \"alsa-lib: \$(dpkg-query -W -f='\${Version}' libasound2-dev:arm64)\"
  aarch64-linux-gnu-readelf -d /out/$(basename "$out") | grep NEEDED
  echo \"highest glibc symbol version: \$(aarch64-linux-gnu-objdump -T /out/$(basename "$out") | grep -o 'GLIBC_[0-9.]*' | sort -V | tail -1)\"
  chown $(id -u):$(id -g) /out/$(basename "$out")
"
file "$out" 2>/dev/null || true
sha256sum "$out"
