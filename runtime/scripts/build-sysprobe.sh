#!/usr/bin/env bash
# Builds the freestanding seccomp probe for aarch64 (packaged into the runtime as bin/shc-sysprobe).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$here/out/bin"
aarch64-linux-gnu-gcc -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -fno-pie -no-pie \
  -O2 -Wall -Wextra -Werror -o "$here/out/bin/shc-sysprobe" "$here/sysprobe/shc-sysprobe.c"
file "$here/out/bin/shc-sysprobe" | tee "$here/out/sysprobe.file.txt"
grep -q "ARM aarch64.*statically linked" "$here/out/sysprobe.file.txt"
